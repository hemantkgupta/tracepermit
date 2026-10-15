import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import {
  chmodSync,
  copyFileSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  realpathSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import {
  checkEndpointDemo,
  demoUrls,
  parseSyntheticToken,
} from "./codex-endpoint-preflight.mjs";

const source = dirname(fileURLToPath(import.meta.url));
const commit = "abcdef0123456789abcdef0123456789abcdef01";
const token = "synthetic-unit-fixture-token-not-a-credential";

function fixture(t) {
  // Spaces exercise shell/TOML quoting, not just ordinary repository paths.
  const root = realpathSync(
    mkdtempSync(join(tmpdir(), "tracepermit endpoint launcher-")),
  );
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const stateDir = join(root, ".cache/demo");
  mkdirSync(stateDir, { recursive: true, mode: 0o700 });
  chmodSync(stateDir, 0o700);
  const state = {
    schemaVersion: "1.0",
    mode: "simulated-loopback",
    ...demoUrls,
    endpointIssuer: "tracepermit-endpoint:demo-local",
    endpointKeyId: "endpoint-key-demo-local",
    endpointDeviceId: "device-demo-local",
    endpointPrivateKeyPath: join(stateDir, "endpoint-key.pk8"),
  };
  const statePath = join(stateDir, "session.json");
  const save = () =>
    writeFileSync(statePath, JSON.stringify(state), { mode: 0o600 });
  save();
  writeFileSync(state.endpointPrivateKeyPath, "synthetic-key-placeholder", {
    mode: 0o600,
  });
  writeFileSync(join(root, "package.json"), '{"version":"0.1.0"}');
  const connectorDir = join(
    root,
    "java/endpoint-connector/build/install/endpoint-connector/bin",
  );
  mkdirSync(connectorDir, { recursive: true });
  writeFileSync(
    join(connectorDir, "endpoint-connector"),
    "#!/bin/sh\nexit 0\n",
    { mode: 0o700 },
  );
  const codex = join(root, "native-codex.cjs");
  writeFileSync(codex, "#!/bin/sh\nexit 0\n", { mode: 0o700 });
  const alias = join(root, "codex-alias");
  symlinkSync(codex, alias);
  const info = {
    backend: "java",
    mode: "simulated-loopback",
    simulated: true,
    javaVersion: "21.0.12.1",
    productVersion: "0.1.0",
    buildCommit: commit.slice(0, 7),
  };
  const ready = {
    ready: true,
    mode: "simulated-loopback",
    dependencies: Object.fromEntries(
      [
        "authentication",
        "policy",
        "permit_signer",
        "replay_store",
        "evidence_store",
      ].map((k) => [k, true]),
    ),
  };
  const calls = [];
  const check = () =>
    checkEndpointDemo(root, alias, {
      commit,
      readJson: (url) => {
        calls.push(url);
        return url.endsWith("/ready") ? ready : info;
      },
    });
  return {
    root,
    stateDir,
    state,
    statePath,
    save,
    codex,
    alias,
    info,
    ready,
    calls,
    check,
  };
}

test("preflight accepts current simulator and resolves the final executable symlink", (t) => {
  const f = fixture(t);
  assert.equal(f.check(), realpathSync(f.codex));
  assert.deepEqual(f.calls, [
    "http://127.0.0.1:8090/api/v1/system-info",
    "http://127.0.0.1:8080/ready",
  ]);
});

for (const [label, mutate, code] of [
  [
    "stale build",
    (f) => {
      f.info.buildCommit = "7654321";
    },
    "BACKEND_BUILD_MISMATCH",
  ],
  [
    "non-simulator backend",
    (f) => {
      f.info.simulated = false;
    },
    "BACKEND_PROFILE_MISMATCH",
  ],
  [
    "wrong Java version",
    (f) => {
      f.info.javaVersion = "25.0.1";
    },
    "BACKEND_PROFILE_MISMATCH",
  ],
  [
    "wrong product version",
    (f) => {
      f.info.productVersion = "9.0.0";
    },
    "BACKEND_PROFILE_MISMATCH",
  ],
  [
    "unready authentication",
    (f) => {
      f.ready.dependencies.authentication = false;
    },
    "BACKEND_NOT_READY",
  ],
  [
    "unready policy",
    (f) => {
      f.ready.dependencies.policy = false;
    },
    "BACKEND_NOT_READY",
  ],
  [
    "unready evidence",
    (f) => {
      f.ready.dependencies.evidence_store = false;
    },
    "BACKEND_NOT_READY",
  ],
  [
    "foreign key path",
    (f) => {
      f.state.endpointPrivateKeyPath = join(f.root, "other.pk8");
      f.save();
    },
    "DEMO_PROFILE_MISMATCH",
  ],
  [
    "Markdown URL",
    (f) => {
      f.state.gatewayUrl =
        "[http://127.0.0.1:8080/mcp](http://127.0.0.1:8080/mcp)";
      f.save();
    },
    "DEMO_PROFILE_MISMATCH",
  ],
  [
    "non-loopback URL",
    (f) => {
      f.state.tokenUrl = "https://example.invalid/token";
      f.save();
    },
    "DEMO_PROFILE_MISMATCH",
  ],
  [
    "foreign issuer",
    (f) => {
      f.state.endpointIssuer = "other-issuer";
      f.save();
    },
    "DEMO_PROFILE_MISMATCH",
  ],
  [
    "public key permissions",
    (f) => {
      chmodSync(f.state.endpointPrivateKeyPath, 0o644);
    },
    "DEMO_STATE_PERMISSIONS",
  ],
  [
    "public session permissions",
    (f) => {
      chmodSync(f.statePath, 0o644);
    },
    "DEMO_STATE_PERMISSIONS",
  ],
  [
    "public directory permissions",
    (f) => {
      chmodSync(f.stateDir, 0o755);
    },
    "DEMO_STATE_PERMISSIONS",
  ],
  [
    "empty key",
    (f) => {
      writeFileSync(f.state.endpointPrivateKeyPath, "");
    },
    "DEMO_KEY_UNAVAILABLE",
  ],
]) {
  test(`preflight refuses ${label}`, (t) => {
    const f = fixture(t);
    mutate(f);
    assert.throws(f.check, new RegExp(code));
  });
}

for (const target of ["statePath", "key"]) {
  test(`preflight refuses symlinked ${target}`, (t) => {
    const f = fixture(t);
    const path =
      target === "statePath" ? f.statePath : f.state.endpointPrivateKeyPath;
    const saved = `${path}.saved`;
    copyFileSync(path, saved);
    rmSync(path);
    symlinkSync(saved, path);
    assert.throws(f.check, /DEMO_STATE_UNAVAILABLE/);
  });
}

test("missing session/key and malformed state stop without contacting a backend", (t) => {
  const f = fixture(t);
  writeFileSync(f.statePath, "not-json");
  assert.throws(f.check);
  rmSync(f.statePath);
  assert.throws(f.check);
  f.save();
  rmSync(f.state.endpointPrivateKeyPath);
  assert.throws(f.check);
  assert.deepEqual(f.calls, []);
});

test("token parser accepts only a bounded single-line opaque value", () => {
  assert.equal(
    parseSyntheticToken(JSON.stringify({ access_token: token })),
    token,
  );
  for (const body of [
    "not-json",
    "{}",
    '{"access_token":42}',
    '{"access_token":""}',
    JSON.stringify({ access_token: "x\ny" }),
    JSON.stringify({ access_token: "x".repeat(16385) }),
  ])
    assert.throws(() => parseSyntheticToken(body));
});

function shellFixture(t) {
  const f = fixture(t);
  const scripts = join(f.root, "scripts");
  const bin = join(f.root, "bin");
  mkdirSync(scripts);
  mkdirSync(bin);
  for (const name of [
    "run-codex-endpoint-demo.sh",
    "codex-endpoint-preflight.mjs",
  ])
    copyFileSync(join(source, name), join(scripts, name));
  writeFileSync(
    join(f.root, "package.json"),
    '{"type":"module","version":"0.1.0"}',
  );
  writeFileSync(
    join(scripts, "configure-java-environment.sh"),
    'export JAVA_HOME="$TRACEPERMIT_TEST_JDK"\n',
  );
  writeFileSync(join(bin, "git"), `#!/bin/bash\nprintf '%s\\n' '${commit}'\n`, {
    mode: 0o700,
  });
  writeFileSync(
    join(bin, "curl"),
    `#!/bin/bash
    printf '%s\\n' "$*" >> "$TRACEPERMIT_TEST_ROOT/curl.args"
    case "\${!#}" in
      */system-info) printf '%s' "$TRACEPERMIT_TEST_INFO" ;;
      */ready) printf '%s' "$TRACEPERMIT_TEST_READY" ;;
      */token) printf '%s' "$TRACEPERMIT_TEST_TOKEN_RESPONSE" ;;
      *) exit 2 ;;
    esac\n`,
    { mode: 0o700 },
  );
  writeFileSync(
    f.codex,
    `#!${process.execPath}
    const fs = require('node:fs');
    const {execFileSync} = require('node:child_process');
    fs.writeFileSync(process.env.TRACEPERMIT_TEST_ROOT+'/native-call.json', JSON.stringify({
      args: process.argv.slice(2), javaHome: process.env.JAVA_HOME,
      token: process.env.TRACEPERMIT_CONNECTOR_BEARER_TOKEN,
      gateway: process.env.TRACEPERMIT_GATEWAY_URL,
      keyPath: process.env.TRACEPERMIT_ENDPOINT_PRIVATE_KEY_PKCS8_PATH,
      parentCommand: execFileSync('/bin/ps', ['-p', String(process.ppid), '-o', 'comm='], {encoding:'utf8'}).trim(),
      targetCredentialPresent: ['AWS_ACCESS_KEY_ID','AWS_SECRET_ACCESS_KEY','AWS_SESSION_TOKEN','JIRA_TOKEN'].some(k => !!process.env[k])
    }));
    process.exit(Number(process.env.TRACEPERMIT_TEST_EXIT ?? 0));\n`,
    { mode: 0o700 },
  );
  const env = {
    ...process.env,
    PATH: `${bin}:${process.env.PATH}`,
    TRACEPERMIT_CODEX_BIN: f.alias,
    TRACEPERMIT_TEST_ROOT: f.root,
    TRACEPERMIT_TEST_JDK: join(f.root, "selected-jdk21"),
    TRACEPERMIT_TEST_INFO: JSON.stringify(f.info),
    TRACEPERMIT_TEST_READY: JSON.stringify(f.ready),
    TRACEPERMIT_TEST_TOKEN_RESPONSE: JSON.stringify({ access_token: token }),
    AWS_ACCESS_KEY_ID: "synthetic-unit-ambient-value",
    JIRA_TOKEN: "synthetic-unit-ambient-value",
  };
  const run = (args) =>
    spawnSync(
      "/bin/bash",
      [join(scripts, "run-codex-endpoint-demo.sh"), ...args],
      {
        cwd: tmpdir(),
        env,
        encoding: "utf8",
        timeout: 15000,
      },
    );
  return {
    ...f,
    env,
    run,
    nativeCall: join(f.root, "native-call.json"),
    curlArgs: join(f.root, "curl.args"),
  };
}

test("check-only validates without issuing token or launching native client", (t) => {
  const f = shellFixture(t);
  const r = f.run(["--check"]);
  assert.equal(r.status, 0, r.stderr);
  assert.equal(existsSync(f.nativeCall), false);
  assert.doesNotMatch(readFileSync(f.curlArgs, "utf8"), /\/token/);
});

test("launcher forwards Java and secret via environment, never argv/output/config", (t) => {
  const f = shellFixture(t);
  const r = f.run([]);
  assert.equal(r.status, 0, r.stderr);
  const call = JSON.parse(readFileSync(f.nativeCall, "utf8"));
  assert.equal(call.javaHome, f.env.TRACEPERMIT_TEST_JDK);
  assert.equal(call.token, token);
  assert.equal(call.gateway, demoUrls.gatewayUrl);
  assert.equal(call.keyPath, f.state.endpointPrivateKeyPath);
  assert.match(call.parentCommand, /(?:^|\/)bash$/);
  assert.equal(call.targetCredentialPresent, false);
  assert.deepEqual(call.args.slice(0, 5), [
    "--no-daemon",
    "--sandbox",
    "read-only",
    "-m",
    "gpt-5.6-sol",
  ]);
  assert.match(call.args[6], /env_vars=\["JAVA_HOME"/);
  assert.match(call.args[6], /command=.*run-java-endpoint-connector\.sh/);
  assert.match(
    call.args[6],
    /required=true.*default_tools_approval_mode="prompt"/,
  );
  assert.doesNotMatch(
    JSON.stringify(call.args) + r.stdout + r.stderr,
    new RegExp(token),
  );
  assert.equal(existsSync(join(f.root, ".codex")), false);
  const curl = readFileSync(f.curlArgs, "utf8");
  assert.equal(curl.split("\n").filter((x) => x.includes("/token")).length, 1);
  for (const line of curl.trim().split("\n")) {
    assert.match(line, /^--disable --noproxy \* --proto =http /);
    assert.doesNotMatch(line, /--location|--retry/);
  }
});

test("mismatch prevents token issue and native launch", (t) => {
  const f = shellFixture(t);
  f.env.TRACEPERMIT_TEST_INFO = JSON.stringify({
    ...f.info,
    buildCommit: "7654321",
  });
  const r = f.run([]);
  assert.equal(r.status, 2);
  assert.match(r.stderr, /BACKEND_BUILD_MISMATCH/);
  assert.equal(existsSync(f.nativeCall), false);
  assert.doesNotMatch(readFileSync(f.curlArgs, "utf8"), /\/token/);
});

test("malformed token response is redacted and prevents native launch", (t) => {
  const f = shellFixture(t);
  f.env.TRACEPERMIT_TEST_TOKEN_RESPONSE = token;
  const r = f.run([]);
  assert.equal(r.status, 2);
  assert.match(r.stderr, /SYNTHETIC_TOKEN_UNAVAILABLE/);
  assert.equal(existsSync(f.nativeCall), false);
  assert.doesNotMatch(r.stderr + r.stdout, new RegExp(token));
  assert.match(readFileSync(f.curlArgs, "utf8"), /\/token/);
});

test("native exit status propagates without an automatic restart", (t) => {
  const f = shellFixture(t);
  f.env.TRACEPERMIT_TEST_EXIT = "19";
  assert.equal(f.run([]).status, 19);
  assert.equal(
    readFileSync(f.curlArgs, "utf8")
      .split("\n")
      .filter((x) => x.includes("/token")).length,
    1,
  );
});

test("unexpected arguments are rejected before preflight or token issue", (t) => {
  const f = shellFixture(t);
  assert.equal(f.run(["--gateway", "https://example.invalid"]).status, 2);
  assert.equal(existsSync(f.curlArgs), false);
  assert.equal(existsSync(f.nativeCall), false);
});
