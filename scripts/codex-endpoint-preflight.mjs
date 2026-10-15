// Local demo tooling only. Java remains the authentication/enforcement boundary.
import { execFileSync } from "node:child_process";
import {
  constants,
  accessSync,
  lstatSync,
  readFileSync,
  realpathSync,
} from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

export const demoUrls = Object.freeze({
  gatewayUrl: "http://127.0.0.1:8080/mcp",
  tokenUrl: "http://127.0.0.1:8080/dev/oauth/token",
  controlPlaneUrl: "http://127.0.0.1:8090",
  consoleUrl: "http://127.0.0.1:5173",
});

function stop(code) {
  throw new Error(code);
}

function privatePath(path, directory = false) {
  const stat = lstatSync(path);
  if (
    stat.isSymbolicLink() ||
    (directory ? !stat.isDirectory() : !stat.isFile())
  )
    stop("DEMO_STATE_UNAVAILABLE");
  if ((stat.mode & 0o077) !== 0 || (stat.mode & 0o400) === 0)
    stop("DEMO_STATE_PERMISSIONS");
  if (process.getuid && stat.uid !== process.getuid())
    stop("DEMO_STATE_OWNER_MISMATCH");
  return stat;
}

export function readLoopbackJson(url) {
  // Literal caller-owned URLs only; no curlrc, proxy, redirect or automatic retry.
  const value = execFileSync(
    "curl",
    [
      "--disable",
      "--noproxy",
      "*",
      "--proto",
      "=http",
      "--silent",
      "--show-error",
      "--fail",
      "--connect-timeout",
      "2",
      "--max-time",
      "5",
      "--max-filesize",
      "16384",
      url,
    ],
    {
      encoding: "utf8",
      timeout: 6000,
      maxBuffer: 16384,
      stdio: ["ignore", "pipe", "pipe"],
    },
  );
  return JSON.parse(value);
}

export function checkEndpointDemo(
  root,
  codexPath,
  {
    readJson = readLoopbackJson,
    commit = execFileSync("git", ["-C", root, "rev-parse", "HEAD"], {
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
      timeout: 5000,
    }).trim(),
  } = {},
) {
  if (!/^[a-f0-9]{40}$/.test(commit)) stop("CHECKOUT_COMMIT_UNAVAILABLE");
  const cache = join(root, ".cache");
  if (!lstatSync(cache).isDirectory() || lstatSync(cache).isSymbolicLink())
    stop("DEMO_STATE_UNAVAILABLE");
  const stateDir = join(cache, "demo");
  privatePath(stateDir, true);
  const statePath = join(stateDir, "session.json");
  if (privatePath(statePath).size > 16384) stop("DEMO_STATE_INVALID");
  const state = JSON.parse(readFileSync(statePath, "utf8"));
  if (state.schemaVersion !== "1.0" || state.mode !== "simulated-loopback")
    stop("DEMO_PROFILE_MISMATCH");
  for (const [key, value] of Object.entries(demoUrls))
    if (state[key] !== value) stop("DEMO_PROFILE_MISMATCH");
  const keyPath = join(stateDir, "endpoint-key.pk8");
  if (
    state.endpointPrivateKeyPath !== keyPath ||
    state.endpointIssuer !== "tracepermit-endpoint:demo-local" ||
    state.endpointKeyId !== "endpoint-key-demo-local" ||
    state.endpointDeviceId !== "device-demo-local"
  )
    stop("DEMO_PROFILE_MISMATCH");
  const key = privatePath(keyPath);
  if (key.size === 0 || key.size > 16384) stop("DEMO_KEY_UNAVAILABLE");
  accessSync(keyPath, constants.R_OK);
  accessSync(
    join(
      root,
      "java/endpoint-connector/build/install/endpoint-connector/bin/endpoint-connector",
    ),
    constants.X_OK,
  );
  const codex = realpathSync(codexPath);
  if (!lstatSync(codex).isFile() || /[\r\n]/.test(codex))
    stop("CODEX_EXECUTABLE_UNAVAILABLE");
  accessSync(codex, constants.X_OK);

  const info = readJson(`${demoUrls.controlPlaneUrl}/api/v1/system-info`);
  const version = JSON.parse(
    readFileSync(join(root, "package.json"), "utf8"),
  ).version;
  if (
    info.backend !== "java" ||
    info.mode !== "simulated-loopback" ||
    info.simulated !== true ||
    !/^21\./.test(info.javaVersion) ||
    info.productVersion !== version
  )
    stop("BACKEND_PROFILE_MISMATCH");
  if (
    !/^[a-f0-9]{7,40}$/.test(info.buildCommit) ||
    !commit.startsWith(info.buildCommit)
  )
    stop("BACKEND_BUILD_MISMATCH");
  const ready = readJson("http://127.0.0.1:8080/ready");
  if (
    ready.ready !== true ||
    ready.mode !== "simulated-loopback" ||
    ![
      "authentication",
      "policy",
      "permit_signer",
      "replay_store",
      "evidence_store",
    ].every((name) => ready.dependencies?.[name] === true)
  )
    stop("BACKEND_NOT_READY");
  return codex;
}

export function parseSyntheticToken(body) {
  let token;
  try {
    token = JSON.parse(body).access_token;
  } catch {
    stop("SYNTHETIC_TOKEN_UNAVAILABLE");
  }
  if (
    typeof token !== "string" ||
    !token ||
    token.length > 16384 ||
    /[\r\n]/.test(token)
  )
    stop("SYNTHETIC_TOKEN_UNAVAILABLE");
  return token;
}

if (
  process.argv[1] &&
  realpathSync(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  try {
    if (process.argv[2] === "--token" && process.argv.length === 3) {
      process.stdout.write(parseSyntheticToken(readFileSync(0, "utf8")));
    } else if (process.argv.length === 3) {
      const root = fileURLToPath(new URL("../", import.meta.url));
      process.stdout.write(checkEndpointDemo(root, process.argv[2]));
    } else stop("INVALID_ARGUMENTS");
  } catch (error) {
    const code = /^[A-Z][A-Z_]+$/.test(error.message)
      ? error.message
      : "PREFLIGHT_FAILED";
    console.error(
      `TracePermit native endpoint demo: ${code}. Start pnpm demo from this checkout; see the connector runbook.`,
    );
    process.exitCode = 2;
  }
}
