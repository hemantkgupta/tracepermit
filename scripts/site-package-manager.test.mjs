import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import {
  copyFileSync,
  mkdirSync,
  mkdtempSync,
  realpathSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { test } from "node:test";

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const manifest = JSON.parse(readFileSync(join(root, "package.json"), "utf8"));
const site = JSON.parse(readFileSync(join(root, "site/package.json"), "utf8"));

test("root pnpm and separately locked npm site declare distinct managers", () => {
  assert.equal(manifest.packageManager, "pnpm@11.25.0");
  assert.match(site.packageManager, /^npm@11\.9\.0\+sha512\.[0-9a-f]{128}$/);
  assert.equal(site.engines.node, ">=22.9.0");
  assert.equal(site.scripts.dev, "vitepress dev --host 127.0.0.1");
  assert.equal(
    site.scripts.preview,
    "vite preview --host 127.0.0.1 --outDir .vitepress/dist --strictPort",
  );
  assert.equal(site.devDependencies.vite, "6.4.4");
  assert.equal(
    JSON.parse(readFileSync(join(root, "site/package-lock.json"), "utf8"))
      .lockfileVersion,
    3,
  );
  assert.match(manifest.scripts.check, /pnpm test:site-tooling/);
});

test("actual site command strings cross the manager boundary before invoking npm", (t) => {
  const fixture = realpathSync(
    mkdtempSync(join(tmpdir(), "tp site boundary with spaces ")),
  );
  t.after(() => rmSync(fixture, { recursive: true, force: true }));
  const bin = join(fixture, "bin");
  mkdirSync(bin);
  mkdirSync(join(fixture, "site"));
  copyFileSync(join(root, "package.json"), join(fixture, "package.json"));
  copyFileSync(
    join(root, "site/package.json"),
    join(fixture, "site/package.json"),
  );
  // This stub models Corepack's nearest-manifest check, not an npm installation.
  // Real Corepack and actual VitePress are qualified separately; no network here.
  const probe = join(fixture, "npm-probe.mjs");
  writeFileSync(
    probe,
    `import { existsSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
let directory = process.cwd();
let manager;
for (;;) {
  const file = join(directory, "package.json");
  if (existsSync(file)) {
    const declared = JSON.parse(readFileSync(file, "utf8")).packageManager;
    if (declared) { manager = declared; break; }
  }
  const parent = dirname(directory);
  if (parent === directory) break;
  directory = parent;
}
if (!manager?.startsWith("npm@")) {
  process.stderr.write("SITE_WRONG_PACKAGE_MANAGER");
  process.exit(23);
}
process.stdout.write(JSON.stringify({ cwd: process.cwd(), args: process.argv.slice(2) }));
`,
  );
  writeFileSync(
    join(bin, "npm"),
    '#!/bin/sh\nexec "$TP_SITE_TEST_NODE" "$TP_SITE_TEST_PROBE" "$@"\n',
    { mode: 0o700 },
  );
  const invoke = (command) =>
    spawnSync(command, {
      cwd: fixture,
      shell: "/bin/sh",
      encoding: "utf8",
      timeout: 5_000,
      maxBuffer: 16_384,
      env: {
        PATH: `${bin}:/usr/bin:/bin`,
        TP_SITE_TEST_NODE: process.execPath,
        TP_SITE_TEST_PROBE: probe,
      },
    });

  for (const [name, args] of Object.entries({
    "site:install": ["ci"],
    "site:audit": ["audit", "--audit-level=low"],
    "site:dev": ["run", "dev"],
    "site:build": ["run", "build"],
    "site:preview": ["run", "preview"],
  })) {
    const result = invoke(manifest.scripts[name]);
    assert.equal(result.error, undefined, name);
    assert.equal(result.status, 0, name);
    assert.deepEqual(JSON.parse(result.stdout), {
      cwd: join(fixture, "site"),
      args,
    });
  }
  const forwarded = invoke(
    `${manifest.scripts["site:preview"]} -- --host 127.0.0.1 --port 4173`,
  );
  assert.equal(forwarded.status, 0);
  assert.deepEqual(JSON.parse(forwarded.stdout).args, [
    "run",
    "preview",
    "--",
    "--host",
    "127.0.0.1",
    "--port",
    "4173",
  ]);

  const oldCommand = invoke("npm --prefix site run build");
  assert.equal(oldCommand.status, 23);
  assert.equal(oldCommand.stderr, "SITE_WRONG_PACKAGE_MANAGER");
  writeFileSync(join(fixture, "site/package.json"), '{"name":"no-manager"}');
  const missingBoundary = invoke(manifest.scripts["site:build"]);
  assert.equal(missingBoundary.status, 23);
  assert.equal(missingBoundary.stderr, "SITE_WRONG_PACKAGE_MANAGER");
});

test("CI and copyable documentation use the same manager-safe entry points", () => {
  const workflow = readFileSync(join(root, ".github/workflows/ci.yml"), "utf8");
  assert.equal(workflow.includes("npm --prefix site"), false);
  assert.equal(workflow.match(/pnpm site:install/g)?.length, 2);
  assert.equal(workflow.match(/corepack enable npm/g)?.length, 2);
  assert.ok(workflow.includes("pnpm site:audit"));
  for (const file of [
    "docs/runbooks/site-dependency-maintenance.md",
    "site/guide/deployment.md",
  ]) {
    const content = readFileSync(join(root, file), "utf8");
    assert.ok(content.includes("pnpm site:install"), file);
    assert.ok(content.includes("pnpm site:build"), file);
  }
});
