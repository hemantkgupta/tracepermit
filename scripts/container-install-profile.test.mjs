import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { createHash, generateKeyPairSync, randomBytes } from "node:crypto";
import {
  chmodSync,
  copyFileSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import net from "node:net";
import os, { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";

const repoRoot = new URL("..", import.meta.url).pathname;
const composeFile = join(repoRoot, "deploy/container/compose.yaml");

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: repoRoot,
    encoding: "utf8",
    timeout: options.timeout ?? 180_000,
    env: options.env ?? process.env,
  });
  if (options.allowFailure) return result;
  assert.equal(
    result.status,
    0,
    `${command} ${args.join(" ")} failed:\n${result.stdout}\n${result.stderr}`,
  );
  return result;
}

function privateFile(path, value) {
  writeFileSync(path, value, { mode: 0o600 });
  chmodSync(path, 0o600);
}

async function freePort() {
  return await new Promise((resolve, reject) => {
    const server = net.createServer();
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const { port } = server.address();
      server.close((failure) => (failure ? reject(failure) : resolve(port)));
    });
  });
}

async function http(path, port) {
  return await fetch(`http://127.0.0.1:${port}${path}`, {
    signal: AbortSignal.timeout(5_000),
    redirect: "error",
  });
}

async function canConnect(address, port) {
  return await new Promise((resolve) => {
    const socket = net.createConnection({
      host: address,
      port,
      timeout: 1_000,
    });
    socket.once("connect", () => {
      socket.destroy();
      resolve(true);
    });
    socket.once("timeout", () => {
      socket.destroy();
      resolve(false);
    });
    socket.once("error", () => resolve(false));
  });
}

test(
  "container install profile preserves loopback, private configuration, and lifecycle boundaries",
  { timeout: 300_000 },
  async (t) => {
    const sourceCommit = execFileSync("git", ["rev-parse", "HEAD"], {
      cwd: repoRoot,
      encoding: "utf8",
    }).trim();
    const shortCommit = sourceCommit.slice(0, 12);
    const image =
      process.env.TRACEPERMIT_CONTAINER_IMAGE ??
      `tracepermit/simulator-approval:0.1.0-${shortCommit}`;
    run("docker", ["image", "inspect", image]);

    const privateDirectory = mkdtempSync(
      join(tmpdir(), "tracepermit container private "),
    );
    chmodSync(privateDirectory, 0o700);
    const applicationPort = await freePort();
    const postgresPort = await freePort();
    const password = `synthetic-${randomBytes(24).toString("hex")}`;
    const browserSecret = `synthetic-${randomBytes(24).toString("hex")}`;
    const keys = generateKeyPairSync("ed25519");
    privateFile(join(privateDirectory, "database.secret"), password);
    privateFile(join(privateDirectory, "browser.secret"), browserSecret);
    privateFile(
      join(privateDirectory, "permit.pk8"),
      keys.privateKey.export({ format: "der", type: "pkcs8" }),
    );
    privateFile(
      join(privateDirectory, "permit.spki"),
      keys.publicKey.export({ format: "der", type: "spki" }),
    );
    const operator = {
      schemaVersion: "1.0",
      port: applicationPort,
      postgres: {
        host: "127.0.0.1",
        port: postgresPort,
        database: "tracepermit_approvals",
        user: "tracepermit_approvals",
        passwordFile: "database.secret",
      },
      oidc: {
        issuer: "https://idp.container.example.invalid",
        audience: "https://approval.container.example.invalid",
        jwksUri: "https://idp.container.example.invalid/.well-known/jwks.json",
        clientId: "container-qualification-client",
        tenantClaim: "org_id",
        tenantValue: "container-qualification-tenant",
        tenantId: "simulated-container",
      },
      memberships: [
        { subject: "synthetic-requester", human: true, roles: ["REQUESTER"] },
        { subject: "synthetic-reviewer", human: true, roles: ["REVIEWER"] },
      ],
      permit: {
        keyId: "container-qualification-key",
        privateKeyFile: "permit.pk8",
        publicKeyFile: "permit.spki",
      },
      browser: {
        authorizationUri: "https://idp.container.example.invalid/authorize",
        tokenUri: "https://idp.container.example.invalid/oauth/token",
        clientSecretFile: "browser.secret",
        consoleDirectory: "/opt/tracepermit/console",
      },
    };
    privateFile(
      join(privateDirectory, "operator.json"),
      `${JSON.stringify(operator, null, 2)}\n`,
    );

    const project = `tp-container-${process.pid}-${randomBytes(4).toString("hex")}`;
    const environment = {
      ...process.env,
      TRACEPERMIT_CONTAINER_IMAGE: image,
      TRACEPERMIT_PRIVATE_DIR: privateDirectory,
      TRACEPERMIT_APPROVAL_PORT: String(applicationPort),
      TRACEPERMIT_POSTGRES_PORT: String(postgresPort),
      TRACEPERMIT_POSTGRES_DATABASE: "tracepermit_approvals",
      TRACEPERMIT_POSTGRES_USER: "tracepermit_approvals",
    };
    const composeArgs = ["compose", "-p", project, "-f", composeFile];
    const compose = (args, options = {}) =>
      run("docker", [...composeArgs, ...args], {
        ...options,
        env: environment,
      });
    t.after(() => {
      compose(["--profile", "setup", "down", "--volumes", "--remove-orphans"], {
        allowFailure: true,
      });
      rmSync(privateDirectory, { recursive: true, force: true });
    });

    const configured = JSON.parse(
      compose(["--profile", "setup", "config", "--format", "json"]).stdout,
    );
    for (const name of ["tracepermit", "check", "init"]) {
      const service = configured.services[name];
      assert.equal(service.network_mode, "host");
      assert.equal(service.user, "10001:10001");
      assert.equal(service.read_only, true);
      assert.deepEqual(service.cap_drop, ["ALL"]);
      assert.deepEqual(service.security_opt, ["no-new-privileges:true"]);
      assert.equal(service.ports, undefined);
    }
    assert.equal(configured.services.postgres.network_mode, "host");
    assert.equal(configured.services.postgres.ports, undefined);
    assert.ok(
      configured.services.postgres.command.includes(
        "listen_addresses=127.0.0.1",
      ),
    );
    assert.equal(
      configured.services.postgres.environment.POSTGRES_PASSWORD_FILE,
      "/run/secrets/postgres_password",
    );
    const rendered = JSON.stringify(configured);
    assert.ok(!rendered.includes(password));
    assert.ok(!rendered.includes(browserSecret));

    const imageInspect = JSON.parse(
      run("docker", ["image", "inspect", image]).stdout,
    )[0];
    assert.equal(imageInspect.Config.User, "10001:10001");
    assert.equal(Object.keys(imageInspect.Config.ExposedPorts ?? {}).length, 0);
    assert.equal(
      imageInspect.Config.Labels["org.opencontainers.image.revision"],
      sourceCommit,
    );
    assert.equal(
      imageInspect.Config.Labels["io.tracepermit.profile"],
      "simulator-approval-container-foundation",
    );
    const packagedManifest = JSON.parse(
      run("docker", [
        "run",
        "--rm",
        "--entrypoint",
        "/bin/cat",
        image,
        "/opt/tracepermit/manifest.json",
      ]).stdout,
    );
    assert.equal(packagedManifest.sourceCommit, sourceCommit);
    run("docker", [
      "run",
      "--rm",
      "--entrypoint",
      "/bin/bash",
      image,
      "-c",
      "set -eu; for tool in node pnpm gradle git; do ! command -v \"$tool\"; done; ! find /opt/tracepermit -type f \\( -name '*.java' -o -name '*.kt' -o -name '*.ts' -o -name '*.tsx' \\) -print -quit | grep .; test ! -e /opt/tracepermit/node_modules",
    ]);

    const archive = join(
      repoRoot,
      "java/adapters-http/build/distributions/tracepermit-simulator-approval-0.1.0.tar.gz",
    );
    const archiveHash = createHash("sha256")
      .update(readFileSync(archive))
      .digest("hex");
    const rejectedContext = mkdtempSync(
      join(tmpdir(), "tracepermit rejected container build "),
    );
    t.after(() => rmSync(rejectedContext, { recursive: true, force: true }));
    copyFileSync(archive, join(rejectedContext, "distribution.tar.gz"));
    for (const name of [
      "Dockerfile",
      "tracepermit-container-prepare",
      "tracepermit-container-healthcheck",
    ]) {
      copyFileSync(
        join(repoRoot, "deploy/container", name),
        join(rejectedContext, name),
      );
    }
    const rejectedBuild = (commit, hash) =>
      run(
        "docker",
        [
          "build",
          "--build-arg",
          `TRACEPERMIT_SOURCE_COMMIT=${commit}`,
          "--build-arg",
          `TRACEPERMIT_ARCHIVE_SHA256=${hash}`,
          rejectedContext,
        ],
        { allowFailure: true },
      );
    assert.notEqual(rejectedBuild(sourceCommit, "0".repeat(64)).status, 0);
    const wrongCommit = sourceCommit.replace(
      /^./,
      sourceCommit[0] === "0" ? "1" : "0",
    );
    assert.notEqual(rejectedBuild(wrongCommit, archiveHash).status, 0);

    chmodSync(privateDirectory, 0o750);
    const insecureDirectory = compose(
      ["--profile", "setup", "run", "--rm", "prepare"],
      { allowFailure: true },
    );
    assert.notEqual(insecureDirectory.status, 0);
    assert.match(
      `${insecureDirectory.stdout}${insecureDirectory.stderr}`,
      /CONTAINER_PREPARE_FAILED/,
    );
    chmodSync(privateDirectory, 0o700);
    chmodSync(join(privateDirectory, "database.secret"), 0o640);
    const insecureInput = compose(
      ["--profile", "setup", "run", "--rm", "prepare"],
      { allowFailure: true },
    );
    assert.notEqual(insecureInput.status, 0);
    assert.match(
      `${insecureInput.stdout}${insecureInput.stderr}`,
      /CONTAINER_PREPARE_FAILED/,
    );
    assert.ok(
      !`${insecureInput.stdout}${insecureInput.stderr}`.includes(password),
    );
    chmodSync(join(privateDirectory, "database.secret"), 0o600);

    const prepared = compose(["--profile", "setup", "run", "--rm", "prepare"]);
    assert.match(prepared.stdout, /CONTAINER_PRIVATE_CONFIGURATION_PREPARED/);
    const checked = compose(["--profile", "setup", "run", "--rm", "check"]);
    assert.match(checked.stdout, /APPROVAL_CONFIGURATION_VALID/);
    assert.ok(!`${checked.stdout}${checked.stderr}`.includes(password));

    const originalPrivateKey = readFileSync(
      join(privateDirectory, "permit.pk8"),
    );
    privateFile(join(privateDirectory, "permit.pk8"), randomBytes(64));
    const replacement = compose(
      ["--profile", "setup", "run", "--rm", "prepare"],
      { allowFailure: true },
    );
    assert.notEqual(replacement.status, 0);
    assert.match(
      `${replacement.stdout}${replacement.stderr}`,
      /CONTAINER_PREPARE_FAILED/,
    );
    assert.ok(!`${replacement.stdout}${replacement.stderr}`.includes(password));
    privateFile(join(privateDirectory, "permit.pk8"), originalPrivateKey);
    compose(["--profile", "setup", "run", "--rm", "check"]);

    compose(["up", "--detach", "--wait", "--wait-timeout", "90", "postgres"]);
    const initialized = compose(["--profile", "setup", "run", "--rm", "init"]);
    assert.match(initialized.stdout, /APPROVAL_DATABASE_INITIALIZED/);
    compose([
      "up",
      "--detach",
      "--wait",
      "--wait-timeout",
      "90",
      "tracepermit",
    ]);

    const health = await http("/health", applicationPort);
    assert.equal(health.status, 200);
    assert.deepEqual(await health.json(), {
      schemaVersion: "1.0",
      service: "tracepermit-simulator-approval",
      status: "live",
    });
    const ready = await http("/ready", applicationPort);
    assert.equal(ready.status, 200);
    assert.equal((await ready.json()).status, "ready");
    const consoleResponse = await http("/approvals", applicationPort);
    assert.equal(consoleResponse.status, 200);
    assert.match(await consoleResponse.text(), /<html/i);

    const appId = compose(["ps", "--quiet", "tracepermit"]).stdout.trim();
    const appInspect = JSON.parse(run("docker", ["inspect", appId]).stdout)[0];
    assert.equal(appInspect.HostConfig.NetworkMode, "host");
    assert.equal(appInspect.HostConfig.ReadonlyRootfs, true);
    assert.deepEqual(appInspect.HostConfig.CapDrop, ["ALL"]);
    assert.equal(
      Object.keys(appInspect.HostConfig.PortBindings ?? {}).length,
      0,
    );
    assert.ok(
      appInspect.Mounts.some(
        (mount) =>
          mount.Destination === "/run/tracepermit" && mount.RW === false,
      ),
    );
    assert.ok(!JSON.stringify(appInspect.Config.Env).includes(password));
    assert.ok(!JSON.stringify(appInspect.Config.Env).includes(browserSecret));

    const sockets = run("ss", ["-ltnH"]).stdout;
    assert.match(
      sockets,
      new RegExp(
        `(?:127\\.0\\.0\\.1|\\[::ffff:127\\.0\\.0\\.1\\]):${applicationPort}\\b`,
      ),
    );
    assert.match(sockets, new RegExp(`127\\.0\\.0\\.1:${postgresPort}\\b`));
    assert.doesNotMatch(
      sockets,
      new RegExp(`(?:0\\.0\\.0\\.0|\\[::\\]):${applicationPort}\\b`),
    );
    const remote = Object.values(os.networkInterfaces())
      .flat()
      .find(
        (address) => address && address.family === "IPv4" && !address.internal,
      );
    if (remote)
      assert.equal(await canConnect(remote.address, applicationPort), false);

    compose(["stop", "postgres"]);
    assert.equal((await http("/health", applicationPort)).status, 200);
    const unavailable = await http("/ready", applicationPort);
    assert.equal(unavailable.status, 503);
    assert.deepEqual(await unavailable.json(), {
      schemaVersion: "1.0",
      service: "tracepermit-simulator-approval",
      status: "not_ready",
    });
  },
);
