import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

const read = (path) =>
  readFileSync(new URL(`../${path}`, import.meta.url), "utf8");
const guide = read("site/guide/what-is-tracepermit.md");
const assets = ["intro-request-flow", "intro-core-pillars"];

for (const asset of assets) {
  test(`${asset} is a self-contained accessible static SVG with an HTML equivalent`, () => {
    const svg = read(`site/public/images/${asset}.svg`);
    assert.match(svg, /role="img" aria-labelledby="title desc"/);
    assert.match(svg, /<title id="title">[^<]+<\/title>/);
    assert.match(svg, /<desc id="desc">[^<]+<\/desc>/);
    assert.doesNotMatch(
      svg,
      /<script\b|<foreignObject\b|<image\b|<animate\b|<set\b|\bon\w+\s*=|(?:href|src)\s*=|<!DOCTYPE|<!ENTITY|@import|url\((?!#[a-z])/i,
    );
    assert.match(svg, /simulator/i);
    assert.ok(guide.includes(`src="/images/${asset}.svg" alt=""`));
    assert.ok(guide.includes(`](/images/${asset}.svg)`));
  });
}

test("request flow retains each policy branch and execution-before-effect boundary", () => {
  const svg = read("site/public/images/intro-request-flow.svg");
  for (const label of [
    "TracePermit Java gateway",
    "BLOCK",
    "No permit. No dispatch.",
    "SANITIZE",
    "Re-evaluate; no authority yet.",
    "pending, not executed",
    "independent review",
    "explicit requester resume",
    "current policy",
    "claim one-use authority",
    "process-local",
    "PostgreSQL",
    "Evidence distinguishes decision from outcome",
  ]) {
    assert.ok(svg.includes(label), label);
  }
  assert.ok(
    svg.indexOf("claim one-use authority") < svg.indexOf("Before effect:"),
  );
});

test("assurance limits appear in both the page and pillar visual", () => {
  const svg = read("site/public/images/intro-core-pillars.svg");
  assert.match(svg, /not managed attestation/i);
  assert.match(svg, /durability depends on the profile/);
  assert.match(svg, /No automatic browser-launcher worker/);
  assert.match(guide, /not an automatically running recovery service/);
  assert.match(guide, /not managed-device or hardware-backed attestation/);
  assert.match(guide, /the website is documentation, not a hosted\nbackend/);
  assert.doesNotMatch(guide, /┌|\[ AI Agent|Verify Attestation/);
});

test("mobile uses semantic text instead of shrinking the diagrams", () => {
  const css = read("site/.vitepress/theme/style.css");
  assert.equal((guide.match(/<ol class="intro-text"/g) ?? []).length, 2);
  assert.equal((guide.match(/<li><strong>/g) ?? []).length, 10);
  assert.equal(
    (guide.match(/class="intro-visual" aria-hidden="true"/g) ?? []).length,
    2,
  );
  assert.match(css, /@media \(max-width: 639px\)/);
  assert.match(css, /\.vp-doc \.intro-visual \{\s+display: none;/);
  assert.match(css, /@media \(min-width: 640px\)/);
  assert.match(css, /clip-path: inset\(50%\)/);
  assert.ok(
    read(".github/workflows/ci.yml").includes(
      "node --test scripts/intro-visuals.test.mjs",
    ),
  );
});
