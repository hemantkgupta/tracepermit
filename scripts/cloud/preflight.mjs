import { spawn } from "node:child_process";
import { constants, accessSync, readFileSync, statfsSync } from "node:fs";
import { freemem } from "node:os";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

export const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
export const images = [
  "postgres:16.4-alpine",
  "mariadb:11.4.13",
  "mariadb:10.6.25",
];

// The selector remains the source of truth. Suppress its sole filesystem
// mutation in this child shell; environment changes cannot escape the child.
export const javaProbe = `
mkdir() { :; }
source "$1/scripts/configure-java-environment.sh" >/dev/null 2>&1 || exit 42
command -v javac >/dev/null || exit 43
javac -version || exit 44
java -version || exit 45
`;

export const probeEnvironment = {
  ...process.env,
  COREPACK_ENABLE_NETWORK: "0",
  COREPACK_ENABLE_AUTO_PIN: "0",
  npm_config_manage_package_manager_versions: "false",
};

// Bound both time and captured output. Only this probe's process group is
// terminated; no daemon, container or unrelated process is ever stopped.
export function command(cmd, args, timeoutMs = 5000) {
  return new Promise((done) => {
    let output = "";
    let state;
    const child = spawn(cmd, args, {
      cwd: root,
      env: probeEnvironment,
      stdio: ["ignore", "pipe", "pipe"],
      detached: true,
    });
    const stop = (reason) => {
      state = reason;
      if (child.pid) {
        try {
          process.kill(-child.pid, "SIGKILL");
        } catch {
          // The bounded child may already have exited.
        }
      }
    };
    const timer = setTimeout(() => stop("TIMEOUT"), timeoutMs);
    for (const stream of [child.stdout, child.stderr]) {
      stream.on("data", (data) => {
        if (output.length + data.length > 65536) stop("OUTPUT_LIMIT");
        else output += data.toString();
      });
    }
    child.on("error", (error) => {
      clearTimeout(timer);
      done({ state: error.code === "ENOENT" ? "MISSING" : "FAILED" });
    });
    child.on("close", (code) => {
      clearTimeout(timer);
      done({ state: state ?? (code === 0 ? "OK" : "FAILED"), output });
    });
  });
}

function wrapperReady() {
  try {
    accessSync(resolve(root, "java/gradlew"), constants.X_OK);
    accessSync(resolve(root, "java/gradle/wrapper/gradle-wrapper.jar"));
    const properties = readFileSync(
      resolve(root, "java/gradle/wrapper/gradle-wrapper.properties"),
      "utf8",
    );
    return (
      properties.includes(
        "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.14.3-bin.zip",
      ) &&
      properties.includes(
        "distributionSha256Sum=bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531",
      ) &&
      properties.includes("validateDistributionUrl=true")
    );
  } catch {
    return false;
  }
}

export async function inspect({ run = command, wrapper = wrapperReady } = {}) {
  const checks = [];
  const record = (id, result, accepted, fallback, version) => {
    const code =
      result.state === "TIMEOUT" || result.state === "OUTPUT_LIMIT"
        ? result.state
        : accepted
          ? "OK"
          : fallback;
    // Raw output and arbitrary exception text never enter the report.
    checks.push({ id, code, ...(code === "OK" && version ? { version } : {}) });
  };
  const node = await run("node", ["--version"]);
  const nodeVersion = node.output?.trim().match(/^v(\d+)\.\d+\.\d+$/)?.[0];
  record(
    "NODE",
    node,
    node.state === "OK" && Number(nodeVersion?.slice(1).split(".")[0]) >= 22,
    node.state === "MISSING" ? "MISSING" : "VERSION_REQUIRED",
    nodeVersion,
  );
  const pnpm = await run("pnpm", ["--version"]);
  record(
    "PNPM",
    pnpm,
    pnpm.state === "OK" && pnpm.output?.trim() === "11.25.0",
    pnpm.state === "MISSING" ? "MISSING" : "VERSION_REQUIRED",
    "11.25.0",
  );
  const java = await run("bash", ["-c", javaProbe, "_", root]);
  const compiler = java.output?.match(/^javac (21(?:\.\d+)*)(?:\s|$)/m)?.[1];
  const runtime = java.output?.match(
    /(?:openjdk|java) version "(21(?:\.\d+)*)(?:[+\-"])/,
  )?.[1];
  record(
    "JAVA",
    java,
    java.state === "OK" && !!compiler && !!runtime,
    "JDK21_REQUIRED",
    compiler,
  );
  checks.push({ id: "GRADLE_WRAPPER", code: wrapper() ? "OK" : "INVALID" });
  const docker = await run("docker", ["--version"]);
  const dockerVersion = docker.output
    ?.trim()
    .match(/^Docker version (\d+\.\d+\.\d+)/)?.[1];
  record(
    "DOCKER_CLIENT",
    docker,
    docker.state === "OK" && !!dockerVersion,
    docker.state === "MISSING" ? "MISSING" : "UNAVAILABLE",
    dockerVersion,
  );
  if (checks.at(-1).code === "OK") {
    const daemon = await run("docker", [
      "version",
      "--format",
      "{{.Server.Version}}",
    ]);
    const version = daemon.output
      ?.trim()
      .match(/^(\d+\.\d+\.\d+)(?:[-+][\w.+-]+)?$/)?.[1];
    record(
      "DOCKER_DAEMON",
      daemon,
      daemon.state === "OK" && !!version,
      "UNRESPONSIVE",
      version,
    );
  } else {
    checks.push({ id: "DOCKER_DAEMON", code: "CLIENT_REQUIRED" });
  }
  for (const image of images) {
    if (checks.find((check) => check.id === "DOCKER_DAEMON").code !== "OK") {
      checks.push({ id: image, code: "DAEMON_REQUIRED" });
      continue;
    }
    const result = await run("docker", [
      "image",
      "inspect",
      image,
      "--format",
      "{{.Id}}",
    ]);
    record(
      image,
      result,
      result.state === "OK" &&
        /^sha256:[a-f0-9]{64}\s*$/.test(result.output ?? ""),
      "IMAGE_REQUIRED",
    );
  }
  return { ready: checks.every((check) => check.code === "OK"), checks };
}

if (
  process.argv[1] &&
  resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  const result = await inspect();
  const disk = statfsSync(root);
  console.log(
    JSON.stringify(
      {
        ...result,
        resources: {
          diskFreeBytes: Number(disk.bavail) * Number(disk.bsize),
          memoryAvailableBytes: freemem(),
        },
      },
      null,
      2,
    ),
  );
  process.exitCode = result.ready ? 0 : 1;
}
