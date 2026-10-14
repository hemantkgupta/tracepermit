import assert from "node:assert/strict";
import { test } from "node:test";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { targetExists } from "./doc-link-target.mjs";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const source = resolve(root, "site/guide/audit-evidence.md");

test("resolves local Markdown links", () => {
  assert.equal(targetExists(root, source, "../../README.md"), true);
  assert.equal(targetExists(root, source, "../../missing.md"), false);
});

test("checks GitHub links into this repository", () => {
  assert.equal(
    targetExists(
      root,
      source,
      "https://github.com/hemantkgupta/tracepermit/blob/main/README.md",
    ),
    true,
  );
  assert.equal(
    targetExists(
      root,
      source,
      "https://github.com/hemantkgupta/tracepermit/tree/main/docs/demo-scenarios",
    ),
    true,
  );
  assert.equal(
    targetExists(
      root,
      source,
      "https://github.com/hemantkgupta/tracepermit/blob/main/docs/specs/does-not-exist.md",
    ),
    false,
  );
});

test("leaves external destinations outside the local check", () => {
  assert.equal(targetExists(root, source, "https://example.com/path"), true);
});
