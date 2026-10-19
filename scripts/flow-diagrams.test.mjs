import assert from "node:assert/strict";
import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
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
