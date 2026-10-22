import assert from "node:assert/strict";
import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import test from "node:test";
import {
  generatedFiles,
  mermaid,
  repositoryRoot,
  staleFiles,
  svg,
  validateCatalog,
  wrap,
} from "./generate-flow-diagrams.mjs";

const catalog = JSON.parse(
  await readFile(
    path.join(repositoryRoot, "docs/design/flow-diagrams.json"),
    "utf8",
  ),
);

test("catalog covers the overview and twelve stable action/operator flows", () => {
  assert.equal(validateCatalog(catalog), catalog);
  assert.equal(catalog.diagrams.length, 13);
  assert.equal(catalog.diagrams[0].id, "central-invariant");
  catalog.diagrams
    .slice(1)
    .forEach((diagram, index) =>
      assert.ok(diagram.id.startsWith(`flow-${index + 1}-`)),
    );
});

test("multi-digit flow IDs are supported without accepting zero or leading zeroes", () => {
  for (const id of [
    "flow-10-browser-history",
    "flow-11-package-startup",
    "flow-12-recovery-quarantine",
  ]) {
    const copy = structuredClone(catalog);
    copy.diagrams = [{ ...copy.diagrams[0], id }];
    assert.equal(validateCatalog(copy), copy);
  }
  for (const id of [
    "flow-0-invalid",
    "flow-01-invalid",
    "flow--1-invalid",
    "flow-1-../invalid",
  ]) {
    const copy = structuredClone(catalog);
    copy.diagrams[0].id = id;
    assert.throws(() => validateCatalog(copy), /flow ID/);
  }
});

test("invalid references, duplicates, multiline labels and unsupported kinds fail closed", () => {
  const invalid = [
    (value) => {
      value.schemaVersion = 2;
    },
    (value) => {
      value.diagrams.push(value.diagrams[0]);
    },
    (value) => {
      value.diagrams[0].steps[0][1] = "Z";
    },
    (value) => {
      value.diagrams[0].steps[0][3] = "execute";
    },
    (value) => {
      value.diagrams[0].participants[0][1] = "new\nparticipant X";
    },
    (value) => {
      value.diagrams[0].steps = [];
    },
    (value) => {
      value.diagrams[0].steps[0][2] = "call; unsafe DSL";
    },
    (value) => {
      value.diagrams[0].participants.push(value.diagrams[0].participants[0]);
    },
  ];
  for (const mutate of invalid) {
    const copy = structuredClone(catalog);
    mutate(copy);
    assert.throws(() => validateCatalog(copy));
  }
});

test("Mermaid and accessible native SVG preserve the same ordered messages", () => {
  for (const diagram of catalog.diagrams) {
    const source = mermaid(diagram),
      vector = svg(diagram);
    assert.equal(svg(diagram), vector);
    assert.match(source, /^sequenceDiagram\n  autonumber/);
    assert.match(vector, /role="img" aria-labelledby=/);
    assert.doesNotMatch(
      vector,
      /<script|<foreignObject|https?:\/\/(?!www\.w3\.org)/,
    );
    let offset = 0;
    for (const [, , message] of diagram.steps) {
      const position = source.indexOf(message, offset);
      assert.ok(position >= offset);
      offset = position + message.length;
      assert.ok(
        vector.includes(
          message
            .replaceAll("&", "&amp;")
            .replaceAll('"', "&quot;")
            .replaceAll("'", "&apos;"),
        ),
        `Accessible description must contain ${message}`,
      );
    }
    assert.equal(
      (vector.match(/<circle /g) ?? []).length,
      diagram.steps.filter((step) => step[3] !== "note").length,
    );
  }
});

test("SVG escapes text, wraps labels, and supports self and return arrows", () => {
  const diagram = {
    id: "flow-1-test",
    title: 'A & "B"',
    participants: [
      ["A", "First"],
      ["B", "Second"],
    ],
    steps: [
      ["A", "A", "Self & 'quote'", "call"],
      ["B", "A", "Return", "return"],
    ],
  };
  const vector = svg(diagram);
  assert.ok(vector.includes("A &amp; &quot;B&quot;"));
  assert.ok(vector.includes("Self &amp; &apos;quote&apos;"));
  assert.ok(vector.includes("h30 v17 h-30"));
  assert.ok(vector.includes('stroke-dasharray="6 4"'));
  assert.deepEqual(wrap("one two three four", 8), ["one two", "three", "four"]);
});

test("generated artifacts cover both guides and every public SVG", async () => {
  const files = await generatedFiles();
  assert.equal(files.size, 15);
  for (const filename of ["docs/flows-guide.md", "site/guide/core-flows.md"]) {
    const guide = files.get(filename);
    for (const diagram of catalog.diagrams)
      assert.ok(guide.includes(mermaid(diagram)));
  }
});

test("drift check rejects altered, missing or stale artifacts without modifying them", async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), "tracepermit-flow-test-"));
  try {
    const files = new Map([
      ["a.svg", "expected"],
      ["b.md", "expected"],
    ]);
    await writeFile(path.join(root, "a.svg"), "expected");
    await writeFile(path.join(root, "b.md"), "expected");
    assert.deepEqual(await staleFiles(files, root), []);
    await writeFile(path.join(root, "a.svg"), "altered");
    await rm(path.join(root, "b.md"));
    assert.deepEqual(await staleFiles(files, root), ["a.svg", "b.md"]);
    assert.equal(await readFile(path.join(root, "a.svg"), "utf8"), "altered");
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("missing, duplicate and unknown guide markers are rejected", async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), "tracepermit-flow-test-"));
  try {
    await mkdir(path.join(root, "docs/design"), { recursive: true });
    await mkdir(path.join(root, "site/guide"), { recursive: true });
    await writeFile(
      path.join(root, "docs/design/flow-diagrams.json"),
      JSON.stringify(catalog),
    );
    const markers = catalog.diagrams
      .map(({ id }) => `<!-- diagram:${id} -->\n<!-- /diagram:${id} -->`)
      .join("\n");
    await writeFile(path.join(root, "site/guide/core-flows.md"), markers);
    for (const broken of [
      "",
      markers + "\n<!-- diagram:central-invariant -->",
      markers + "\n<!-- /diagram:central-invariant -->",
      markers + "\n<!-- diagram:unknown -->",
    ]) {
      await writeFile(path.join(root, "docs/flows-guide.md"), broken);
      await assert.rejects(generatedFiles(root), /marker/);
    }
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("documented control tokens match the current policy and approval adapter", async () => {
  const policy = await readFile(
    path.join(repositoryRoot, "policies/aws-web-readonly.json"),
    "utf8",
  );
  assert.ok(policy.includes("BOUNDED_LOG_READ"));
  assert.ok(policy.includes("AGENT_EC2_TERMINATION_BLOCKED"));
  const adapter = await readFile(
    path.join(
      repositoryRoot,
      "java/adapters-http/src/main/java/io/tracepermit/adapters/http/ApprovalMcpAdapter.java",
    ),
    "utf8",
  );
  for (const name of [
    "tracepermit_request_simulated_change",
    "tracepermit_get_approval",
    "tracepermit_resume_approval",
  ])
    assert.ok(adapter.includes(name));
});

test("operator diagrams use commands supported by the actual local launcher", async () => {
  const main = await readFile(
    path.join(
      repositoryRoot,
      "java/adapters-http/src/main/java/io/tracepermit/adapters/http/SimulatorApprovalMain.java",
    ),
    "utf8",
  );
  for (const id of ["flow-11-package-startup", "flow-12-recovery-quarantine"]) {
    const diagram = catalog.diagrams.find((item) => item.id === id);
    assert.ok(diagram);
    for (const command of mermaid(diagram).match(/--[a-z-]+/g) ?? []) {
      assert.ok(
        main.includes(`"${command}"`),
        `Unsupported documented command ${command}`,
      );
    }
  }
});

test("fresh Mac page includes one canonical checklist with supported scripts and fixture roles", async () => {
  const page = await readFile(
    path.join(repositoryRoot, "site/guide/fresh-macbook-evaluation.md"),
    "utf8",
  );
  const guide = await readFile(
    path.join(repositoryRoot, "docs/runbooks/fresh-macbook-evaluation.md"),
    "utf8",
  );
  const manifest = JSON.parse(
    await readFile(path.join(repositoryRoot, "package.json"), "utf8"),
  );
  const fixture = await readFile(
    path.join(
      repositoryRoot,
      "java/adapters-http/src/test/java/io/tracepermit/adapters/http/SimulatorApprovalBrowserQualificationMain.java",
    ),
    "utf8",
  );
  assert.ok(
    page.includes(
      "<!--@include: ../../docs/runbooks/fresh-macbook-evaluation.md-->",
    ),
  );
  for (const script of [
    "check",
    "format:check",
    "demo:approvals:browser",
    "build:distribution",
  ]) {
    assert.ok(manifest.scripts[script]);
    assert.ok(guide.includes(`pnpm ${script}`));
  }
  for (const role of [
    "alice=requester",
    "bob=reviewer",
    "carol=viewer",
    "dave=requester+reviewer",
  ]) {
    assert.ok(fixture.includes(role));
    assert.ok(guide.includes(role));
  }
  assert.ok(guide.includes("source-based fresh-machine evaluation"));
  assert.ok(guide.includes("not** a completed qualification"));
});

test("fresh Mac commands keep source builds separate from installed lifecycle and match shipped output", async () => {
  const read = (name) => readFile(path.join(repositoryRoot, name), "utf8");
  const [guide, main, launcher, build, fixture, configuration] =
    await Promise.all([
      read("docs/runbooks/fresh-macbook-evaluation.md"),
      read(
        "java/adapters-http/src/main/java/io/tracepermit/adapters/http/SimulatorApprovalMain.java",
      ),
      read(
        "java/adapters-http/src/main/scripts/tracepermit-simulator-approval",
      ),
      read("java/adapters-http/build.gradle.kts"),
      read(
        "java/adapters-http/src/test/java/io/tracepermit/adapters/http/SimulatorApprovalBrowserFixture.java",
      ),
      read("config/simulator-approvals.example.json"),
    ]);
  const installed = guide
    .split("### 4. Check offline, initialize once, then run")[1]
    .split("## Path C")[0];
  const commands = [...installed.matchAll(/```bash\n([\s\S]*?)```/g)]
    .map((match) => match[1])
    .join("\n");
  const modes = [
    ...commands.matchAll(
      /\.\/bin\/tracepermit-simulator-approval (--[a-z-]+)/g,
    ),
  ].map((match) => match[1]);
  assert.deepEqual(modes, ["--check", "--init-db", "--run"]);
  for (const mode of modes) {
    assert.ok(main.includes(`"${mode}"`));
    assert.ok(launcher.includes(`"${mode}"`));
  }
  assert.doesNotMatch(commands, /\b(?:pnpm|node|gradlew|gradle|git)\b/);
  for (const marker of [
    "APPROVAL_CONFIGURATION_VALID: offline only; no database, IdP or listener checked.",
    "APPROVAL_DATABASE_INITIALIZED: fresh simulator schema only; no listener or target started.",
    "TracePermit simulator approval API listening on http://127.0.0.1:",
  ]) {
    assert.ok(main.includes(marker));
    assert.ok(guide.replaceAll(/\s+/g, " ").includes(marker));
  }
  assert.equal(JSON.parse(configuration).browser, undefined);
  assert.match(guide, /has \*\*no\n?browser object\*\*/);
  assert.ok(
    build.includes('archiveBaseName.set("tracepermit-simulator-approval")'),
  );
  assert.ok(build.includes('archiveVersion.set("0.1.0")'));
  assert.ok(build.includes('buildDirectory.dir("distributions")'));
  assert.ok(build.includes('docsDir.resolve("setup-runbook.md")'));
  assert.ok(guide.includes("docs/setup-runbook.md"));
  assert.match(
    guide,
    /java\/adapters-http\/build\/distributions\/tracepermit-simulator-approval-0\.1\.0\.tar\.gz/,
  );
  assert.ok(build.includes(String.raw`\"schemaVersion\": \"1.2\"`));
  assert.match(guide, /manifest schema `1\.2`/);
  assert.ok(fixture.includes('.put("exp", now + 290)'));
  assert.match(guide, /\*\*290 seconds\*\*/);
  assert.match(
    guide,
    /Existing compatible\ninstallations \*\*skip --init-db\*\*/,
  );
  assert.match(guide, /in-memory target is reconstructed on process restart/);
});

test("fresh Mac documentation retains safe commands, finite observation and private fact-only evidence", async () => {
  const guide = await readFile(
    path.join(repositoryRoot, "docs/runbooks/fresh-macbook-evaluation.md"),
    "utf8",
  );
  const blocks = [...guide.matchAll(/```bash\n([\s\S]*?)```/g)].map(
    (match) => match[1],
  );
  for (const block of blocks) {
    const syntax = spawnSync("bash", ["-n"], {
      input: block,
      encoding: "utf8",
      timeout: 2_000,
    });
    assert.equal(
      syntax.status,
      0,
      "Documented Bash must parse without executing it",
    );
    assert.doesNotMatch(
      block,
      /\bsudo\b|curl[^\n]*\|\s*(?:ba)?sh|--no-frozen-lockfile|\b(?:env|printenv)\s*$/m,
    );
    for (const line of block
      .split("\n")
      .filter((line) => line.startsWith("curl "))) {
      assert.match(line, /--connect-timeout 2 --max-time 5/);
      assert.match(line, /--noproxy '\*'/);
    }
  }
  assert.match(
    guide,
    /corepack enable --install-directory "\$HOME\/\.local\/bin" pnpm/,
  );
  assert.match(
    guide,
    /npm install --global --prefix "\$HOME\/\.local" corepack@0\.34\.6/,
  );
  assert.match(guide, /\{1\.\.10\}/);
  assert.match(guide, /Require `PORT_RELEASED` \*\*and\*\* launcher return/);
  assert.match(guide, /verified-empty dedicated schema/);
  assert.match(guide, /\*\*not a signature\*\*/);
  assert.match(guide, /Day 3 remains\n\*\*pending\*\*/);
  const record = guide.split("## What to report back")[1];
  assert.ok(record);
  const template = record.match(/```text\n([\s\S]*?)```/)[1];
  assert.ok(template.length < 1_500);
  assert.doesNotMatch(
    template,
    /(?:token|cookie|password|operator JSON|provider ID|subject|callback|serial|email)\s*:/i,
  );
  for (const forbidden of [
    "PKCE material",
    "provider IDs",
    "environment dumps",
    "personal\naccount data",
  ]) {
    assert.ok(record.includes(forbidden));
  }
  assert.match(record, /Do not attach raw logs/);
  assert.match(guide, /Path C — live external-IdP and manual qualification/);
});
