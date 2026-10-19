import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

const repo = dirname(dirname(fileURLToPath(import.meta.url)));
const page = readFileSync(join(repo, "site/guide/security-domains.md"), "utf8");
const threatModel = readFileSync(
  join(repo, "docs/specs/10-security-threat-model.md"),
  "utf8",
);

const registerIds = new Set(
  [...threatModel.matchAll(/^\| (T-\d{3}) \|/gm)].map((m) => m[1]),
);

test("page frames the threat register as a requirements baseline, not a supremacy rule", () => {
  assert.doesNotMatch(page, /threat model wins/i);
  assert.match(page, /security-requirements baseline/i);
  assert.match(page, /not a status report/i);
});

test("page carries status vocabulary, an at-a-glance matrix, and nine bounded domains", () => {
  for (const status of [
    "Implemented (tested)",
    "Optional foundation",
    "Live-qualified (named profile)",
    "Future gate",
  ]) {
    assert.ok(
      page.includes(`**${status}:**`),
      `missing legend entry: ${status}`,
    );
  }
  assert.match(page, /Implemented now \(simulator POC\)/i);
  assert.match(page, /Optional foundation/i);
  assert.match(page, /Future deployment gate/i);
  const sections = [...page.matchAll(/^## \d\. /gm)].length;
  const residualLimits = [...page.matchAll(/\*\*Residual limit:\*\*/g)].length;
  assert.equal(sections, 9);
  assert.equal(residualLimits, 9);
  assert.match(
    page,
    /\*\*As of:\*\* main `[0-9a-f]{7,40}` \(\d{4}-\d{2}-\d{2}\)/,
  );
});

test("every cited threat ID exists in the threat register", () => {
  const cited = [...page.matchAll(/T-\d{3}/g)].map((m) => m[0]);
  assert.ok(cited.length >= 30, "page should cite the register broadly");
  assert.ok(registerIds.size >= 40, "register parsing should be exhaustive");
  for (const id of new Set(cited)) {
    assert.ok(
      registerIds.has(id),
      `${id} is cited on the page but absent from the threat register`,
    );
  }
});

test("every evidence link resolves inside this repository", () => {
  const githubLinks = [
    ...page.matchAll(
      /https:\/\/github\.com\/hemantkgupta\/tracepermit\/(blob|tree)\/main\/([^\s)\]]+)/g,
    ),
  ].map((m) => m[2].replace(/#[^#]*$/, ""));
  assert.ok(
    githubLinks.length >= 25,
    "implemented claims should carry source/test evidence links",
  );
  for (const path of githubLinks) {
    assert.ok(existsSync(join(repo, path)), `missing repository path: ${path}`);
  }
  const relativeLinks = [
    ...page.matchAll(/\]\(((?!https?:)[^)#\s]+\.md)\)/g),
  ].map((m) => m[1]);
  for (const path of relativeLinks) {
    assert.ok(
      existsSync(join(repo, "site/guide", path)),
      `missing guide page: ${path}`,
    );
  }
});

test("evidence anchoring is stated as target design, not deployed custody", () => {
  assert.match(
    page,
    /no independently operated (external )?(anchor|checkpoint)/i,
  );
  assert.match(page, /no KMS or HSM signing custody is deployed/i);
  assert.match(page, /(do|does) not prove truth or non-repudiation/i);
});

test("publication states at-least-once delivery with receiver-side dedup", () => {
  assert.match(page, /at-least-once/i);
  assert.match(page, /receiver-side dedup/i);
  assert.match(page, /(non-idempotent|non idempotent) sink/i);
  assert.match(page, /observe duplicate/i);
});

test("outbox leasing stays separate from atomic permit claims and default stores stay process-local", () => {
  assert.match(page, /SKIP LOCKED/i);
  assert.match(page, /different mechanism from the atomic/i);
  assert.match(page, /not durable across restart/i);
  assert.match(
    page,
    /durable, cross-restart, cross-replica replay or abuse state/i,
  );
});

test("canonical binding scope and crash-uncertainty limits are stated", () => {
  assert.match(
    page,
    /not byte-for-byte identity of the whole MCP wire request/i,
  );
  assert.match(page, /single-use authority does not by itself resolve/i);
});

test("identity claims are scoped to named profiles and adapter-derived facts", () => {
  assert.match(page, /live-qualified on \d{4}-\d{2}-\d{2}/i);
  assert.match(page, /not managed-device or hardware-backed attestation/i);
  assert.match(page, /source proof does not authorize an action/i);
});

test("network controls are bounded to configured destinations, not host egress", () => {
  assert.match(page, /not host-wide egress enforcement/i);
  assert.match(page, /no real AWS or Jira credential broker/i);
});

test("injection classification and redaction claims stay bounded", () => {
  assert.match(page, /not detection of all prompt injection/i);
  assert.match(page, /trusted computing base/i);
  assert.match(page, /cannot guarantee that arbitrary caller-controlled text/i);
});

test("release gates are framed as obligations, not current state", () => {
  assert.match(page, /obligations before any real AWS or Jira credential/i);
  assert.match(page, /not statements about today's tree/i);
});
