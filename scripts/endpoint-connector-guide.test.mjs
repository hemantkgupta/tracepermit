import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

const root = new URL("../", import.meta.url);
const read = (path) => readFileSync(new URL(path, root), "utf8");
const guide = read("site/guide/endpoint-connector.md");
const launcher = read("scripts/run-codex-endpoint-demo.sh");
const preflight = read("scripts/codex-endpoint-preflight.mjs");
const runbook = read("docs/runbooks/java-mcp-endpoint-connector.md");

test("connector guide invokes actual package commands, not invented installers", () => {
  const { scripts, packageManager, engines } = JSON.parse(read("package.json"));
  for (const name of [
    "demo",
    "demo:endpoint:codex",
    "build:java-endpoint",
    "test:endpoint-launcher",
    "test:endpoint-connector-e2e",
  ]) {
    assert.ok(scripts[name], name);
    assert.ok(guide.includes(`pnpm ${name}`), name);
  }
  assert.equal(packageManager, "pnpm@11.25.0");
  assert.equal(engines.node, ">=22");
  assert.match(guide, /Java 21 \*\*JDK\*\*, Node\.js 22\+/);
  assert.match(
    guide,
    /not yet a published, independently qualified connector installer/,
  );
  assert.match(guide, /not bundle a JVM/);
  assert.match(
    guide,
    /pnpm demo:endpoint:codex --check\npnpm demo:endpoint:codex/,
  );
  assert.match(launcher, /\[\[.*--check.*\]\]/);
});

test("connector settings in the guide are actual required Java configuration", () => {
  const main = read(
    "java/endpoint-connector/src/main/java/io/tracepermit/endpoint/EndpointConnectorMain.java",
  );
  const credential = read(
    "java/endpoint-connector/src/main/java/io/tracepermit/endpoint/EnvironmentConnectorCredentialProvider.java",
  );
  const documented = Array.from(
    guide.matchAll(/^\| `(TRACEPERMIT_[A-Z0-9_]+)`\s+\|/gm),
    (match) => match[1],
  );
  assert.equal(documented.length, 6);
  for (const name of documented) {
    assert.ok((main + credential).includes(`"${name}"`), name);
    assert.ok(launcher.includes(name), name);
  }
  for (const setting of [
    "required=true",
    "startup_timeout_sec=30",
    'default_tools_approval_mode="prompt"',
  ]) {
    assert.ok(guide.includes(setting));
    assert.ok(launcher.replaceAll('\\"', '"').includes(setting));
  }
  assert.match(guide, /env_vars/);
  assert.match(guide, /never paste it into TOML/);
});

test("local-only address and source trust claims match the transport", () => {
  const transport = read(
    "java/endpoint-connector/src/main/java/io/tracepermit/endpoint/LoopbackHttpGatewayTransport.java",
  );
  assert.match(transport, /LOOPBACK_HOST = "127\.0\.0\.1"/);
  assert.match(transport, /!"http"\.equals\(uri\.getScheme\(\)\)/);
  assert.match(transport, /!"\/mcp"\.equals\(uri\.getRawPath\(\)\)/);
  assert.match(transport, /HttpClient\.Redirect\.NEVER/);
  assert.match(transport, /HttpClient\.Builder\.NO_PROXY/);
  assert.match(
    guide,
    /Remote backend URLs, including HTTPS URLs, are rejected/,
  );
  assert.match(
    guide,
    /`tracepermit\.org` serves documentation, not an enforcement API/,
  );
  assert.match(guide, /JSON-RPC request ID is deliberately excluded/);
  for (const url of [
    "http://127.0.0.1:8080/mcp",
    "http://127.0.0.1:8080/ready",
    "http://127.0.0.1:8090/api/v1",
    "http://127.0.0.1:5173",
  ]) {
    assert.ok(guide.includes(url));
    assert.ok(
      (launcher + preflight + read("scripts/run-demo.sh")).includes(url),
    );
  }
});

test("copyable model prompts preserve the qualified arguments and stop rules", () => {
  const prompts = Array.from(
    guide.matchAll(/```text\n([\s\S]*?)\n```/g),
    (match) => match[1],
  ).filter((text) => text.startsWith("Use only"));
  assert.equal(prompts.length, 2);
  for (const [index, args] of [
    [0, ["aws.logs.filter_log_events", "/app/staging", "ERROR", "limit: 2"]],
    [
      1,
      [
        "aws.ec2.terminate_instances",
        'instanceIds: ["i-simulated-native-codex"]',
        "region: us-east-1",
      ],
    ],
  ]) {
    for (const arg of args) {
      assert.ok(prompts[index].includes(arg));
      assert.ok(runbook.includes(arg));
    }
    assert.match(prompts[index], /tracepermit_endpoint/);
    assert.match(prompts[index], /exactly once/);
    assert.match(prompts[index], /Do not retry/);
    assert.match(
      prompts[index].replaceAll(/\s+/g, " "),
      /Do not display credentials or source proofs/,
    );
    assert.match(prompts[index], /Stop after reporting the result/);
  }
  const evidence = read(
    "docs/demo-scenarios/evidence/codex-native-endpoint-2026-10-15.md",
  );
  for (const value of ["BOUNDED_LOG_READ", "AGENT_EC2_TERMINATION_BLOCKED"]) {
    assert.ok(guide.includes(value));
    assert.ok(evidence.includes(value));
  }
});

test("guide is discoverable and its static diagram has an accessible local-only boundary", () => {
  const config = read("site/.vitepress/config.mts");
  // The guide is linked from both the grouped evaluation menu and sidebar.
  assert.equal(
    Array.from(
      config.matchAll(
        /text:\s*"Connector for Codex",\s*link:\s*"\/guide\/endpoint-connector"/g,
      ),
    ).length,
    2,
  );
  assert.match(guide, /tabindex="0" role="region"/);
  assert.match(guide, /\/images\/endpoint-connector-overview\.svg/);
  const svg = read("site/public/images/endpoint-connector-overview.svg");
  assert.match(svg, /role="img" aria-labelledby="title desc"/);
  assert.match(svg, /<title id="title">/);
  assert.match(svg, /<desc id="desc">/);
  assert.match(svg, /No remote backend is supported by this profile/);
  assert.match(svg, /No dispatch/);
  assert.doesNotMatch(
    svg,
    /<script|<foreignObject|https?:\/\/(?!www\.w3\.org)/,
  );
});
