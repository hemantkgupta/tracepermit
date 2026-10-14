import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import {
  chmodSync,
  copyFileSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

const source = fileURLToPath(
  new URL("./demo-cleanup-guardian.mjs", import.meta.url),
);

function fixture(mode = 0o700) {
  const root = mkdtempSync(join(tmpdir(), "tracepermit-guardian-test-"));
  const scripts = join(root, "scripts");
  const cache = join(root, ".cache/demo");
  mkdirSync(scripts);
  mkdirSync(cache, { recursive: true, mode });
  chmodSync(cache, mode);
  const guardian = join(scripts, "demo-cleanup-guardian.mjs");
  copyFileSync(source, guardian);
  const key = join(cache, "endpoint-key.pk8");
  const state = join(cache, "session.json");
  writeFileSync(key, "synthetic private key fixture", { mode: 0o600 });
  writeFileSync(state, "synthetic state fixture", { mode: 0o600 });
  return { root, guardian, key, state };
}

test("refuses cleanup when the fixed cache directory is not private", () => {
  const { root, guardian, key } = fixture(0o755);
  try {
    const result = spawnSync(process.execPath, [guardian, "/tmp/arbitrary"], {
      encoding: "utf8",
    });
    assert.equal(result.status, 2);
    assert.equal(existsSync(key), true);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("cleans only its fixed key after the launcher exits", async () => {
  const { root, guardian, key, state } = fixture();
  const unrelated = join(root, "unrelated.txt");
  writeFileSync(unrelated, "preserve this synthetic fixture");
  try {
    const launcher = spawnSync(
      process.execPath,
      [
        "-e",
        `const {spawn}=require("node:child_process");
         const watcher=spawn(process.execPath,[${JSON.stringify(guardian)},${JSON.stringify(unrelated)}],
           {detached:true,stdio:"ignore"});
         watcher.unref();
         setTimeout(()=>process.exit(0),400);`,
      ],
      { encoding: "utf8" },
    );
    assert.equal(launcher.status, 0);
    for (let attempt = 0; attempt < 20 && existsSync(key); attempt += 1) {
      await new Promise((resolve) => setTimeout(resolve, 100));
    }
    assert.equal(existsSync(key), false);
    assert.equal(existsSync(state), false);
    assert.equal(existsSync(unrelated), true);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
