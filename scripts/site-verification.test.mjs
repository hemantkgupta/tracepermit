import assert from "node:assert/strict";
import { existsSync, readFileSync, readdirSync } from "node:fs";
import { test } from "node:test";

const root = new URL("../", import.meta.url);
const read = (path) => readFileSync(new URL(path, root), "utf8");
const dist = "site/.vitepress/dist/";
const guide = read("site/guide/verification.md");
const tags = (html, name) =>
  [...html.matchAll(new RegExp(`<${name}\\b[^>]*>`, "g"))].map(
    (match) => match[0],
  );
const attribute = (tag, name) =>
  tag.match(new RegExp(`\\b${name}="([^"]*)"`))?.[1];
const meta = (html, key) =>
  tags(html, "meta")
    .filter(
      (tag) =>
        attribute(tag, "property") === key || attribute(tag, "name") === key,
    )
    .map((tag) => attribute(tag, "content"));

function assertMetadata(html, canonical) {
  const links = tags(html, "link")
    .filter((tag) => attribute(tag, "rel") === "canonical")
    .map((tag) => attribute(tag, "href"));
  assert.deepEqual(links, [canonical]);
  assert.deepEqual(meta(html, "og:url"), [canonical]);
  for (const key of ["og:title", "og:description"]) {
    const values = meta(html, key);
    assert.equal(values.length, 1, key);
    assert.ok(values[0]?.trim(), key);
  }
  assert.deepEqual(meta(html, "og:image"), [
    "https://tracepermit.org/images/tracepermit-social.png",
  ]);
  assert.deepEqual(meta(html, "twitter:card"), ["summary_large_image"]);
}

test("every built public page has unique apex canonical and share metadata", () => {
  const pages = readdirSync(new URL(dist, root), { recursive: true }).filter(
    (name) => name.endsWith(".html") && name !== "404.html",
  );
  assert.ok(
    pages.length >= 18,
    "Inspect the actual built guide set, not a single fixture.",
  );
  for (const name of pages) {
    const path = name
      .replace(/(^|\/)index\.html$/, "$1")
      .replace(/\.html$/, "");
    assertMetadata(
      read(dist + name),
      new URL(path, "https://tracepermit.org/").href,
    );
  }
  const verification = read(dist + "guide/verification.html");
  assert.deepEqual(meta(verification, "og:title"), [
    "Verification and compatibility | TracePermit",
  ]);
  const notFound = read(dist + "404.html");
  assert.equal(
    tags(notFound, "link").filter(
      (tag) => attribute(tag, "rel") === "canonical",
    ).length,
    0,
  );

  // Negative controls: the same validator must reject stale and duplicate metadata.
  assert.throws(() =>
    assertMetadata(
      verification.replace(
        'rel="canonical" href="https://tracepermit.org/guide/verification"',
        'rel="canonical" href="https://wrong.invalid/"',
      ),
      "https://tracepermit.org/guide/verification",
    ),
  );
  assert.throws(() =>
    assertMetadata(
      verification.replace(
        "</head>",
        '<meta property="og:title" content="stale"></head>',
      ),
      "https://tracepermit.org/guide/verification",
    ),
  );
});

test("sitemap lists actual clean public routes, excludes 404 and uses the apex", () => {
  const sitemap = read(dist + "sitemap.xml");
  const locations = [...sitemap.matchAll(/<loc>([^<]+)<\/loc>/g)].map(
    (match) => match[1],
  );
  assert.ok(locations.includes("https://tracepermit.org/"));
  assert.ok(locations.includes("https://tracepermit.org/guide/verification"));
  assert.equal(new Set(locations).size, locations.length);
  for (const location of locations) {
    const url = new URL(location);
    assert.equal(url.origin, "https://tracepermit.org");
    assert.doesNotMatch(url.pathname, /404|\.html$/);
    assert.ok(
      existsSync(
        new URL(
          dist +
            (url.pathname === "/"
              ? "index.html"
              : `${url.pathname.slice(1)}.html`),
          root,
        ),
      ),
      location,
    );
  }
  for (const name of readdirSync(new URL(dist + "guide/", root)).filter(
    (name) => name.endsWith(".html"),
  )) {
    assert.ok(
      locations.includes(`https://tracepermit.org/guide/${name.slice(0, -5)}`),
      name,
    );
  }
});

test("social image is a committed 1200 by 630 PNG with a simulator-only vector source", () => {
  const png = readFileSync(
    new URL("site/public/images/tracepermit-social.png", root),
  );
  assert.equal(png.subarray(0, 8).toString("hex"), "89504e470d0a1a0a");
  assert.equal(png.readUInt32BE(16), 1200);
  assert.equal(png.readUInt32BE(20), 630);
  const svg = read("site/public/images/tracepermit-social.svg");
  assert.match(svg, /SIMULATOR-ONLY EVALUATION PREVIEW/);
  assert.match(svg, /Still not executed/);
  assert.doesNotMatch(svg, /<script|<foreignObject|(?:href|src)=|@import/i);
  assert.ok(existsSync(new URL(dist + "images/tracepermit-social.png", root)));
});

test("evidence is pinned, case-specific and does not promote an unreleased connector", () => {
  const links = [
    ...guide.matchAll(
      /https:\/\/github\.com\/hemantkgupta\/tracepermit\/blob\/([a-f0-9]{40})\/([^\s)]+)/g,
    ),
  ];
  assert.ok(links.length >= 10);
  for (const [, commit, path] of links) {
    assert.equal(commit, "47130339f4c3880f28a99aaff551f58d1f32a0f2");
    assert.ok(existsSync(new URL(path, root)), path);
  }
  for (const name of [
    "browser-consumed-succeeded.png",
    "replay-rejected.png",
  ]) {
    assert.ok(
      guide.includes(`/codex-model-driven-approval-2026-10-15/${name}`),
    );
  }
  assert.match(
    guide,
    /Both show \*\*Case B\*\*, request prefix \*\*`53d10b56`\*\*/,
  );
  assert.match(
    guide,
    /pending\/approved screenshots were \*\*not retained\*\*/,
  );
  assert.match(guide, /not an independently signed audit ledger/);
  assert.match(guide, /no installable endpoint connector release claim/);
  assert.match(
    guide,
    /does not rerun them or qualify the current release on another machine/,
  );
  assert.match(read("site/index.md"), /illustration is a console concept/);
});

test("protocol identifiers match source but are not presented as full conformance", () => {
  for (const path of [
    "java/adapters-remote/src/main/java/io/tracepermit/adapters/remote/HandshakeOnlyMcpDispatcher.java",
    "java/adapters-http/src/main/java/io/tracepermit/adapters/http/ApprovalMcpAdapter.java",
  ]) {
    const identifiers = new Set(
      [...read(path).matchAll(/"(20\d\d-\d\d-\d\d)"/g)].map(
        (match) => match[1],
      ),
    );
    assert.equal(identifiers.size, 4);
    for (const identifier of identifiers)
      assert.ok(guide.includes(`\`${identifier}\``));
  }
  assert.match(guide, /not full protocol conformance/);
  const mcp = read("site/guide/mcp-gateway.md");
  assert.match(mcp, /optional authorization framework for HTTP transports/);
  assert.match(
    mcp,
    /modelcontextprotocol.io\/specification\/2025-06-18\/basic\/authorization/,
  );
  assert.doesNotMatch(
    mcp,
    /raw MCP servers have no built-in authorization|strictly mediates every phase/,
  );
  assert.match(
    mcp.replaceAll(/\s+/g, " "),
    /not attest arbitrary remote tool implementation code/,
  );
});

test("contact routes use the approved address without changing private vulnerability reporting", () => {
  const home = read(dist + "index.html");
  const links = tags(home, "a").map((tag) => attribute(tag, "href"));
  assert.ok(
    links.includes(
      "mailto:hemant@tracepermit.org?subject=TracePermit%20simulator%20demo",
    ),
  );
  assert.ok(
    links.includes(
      "https://github.com/hemantkgupta/tracepermit/security/advisories/new",
    ),
  );
  assert.match(guide, /Do not send credentials, private keys,/);
  assert.match(guide, /No response, support or remediation SLA is promised/);
});
