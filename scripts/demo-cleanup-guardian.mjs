import { createHash } from "node:crypto";
import { lstatSync, readFileSync, rmSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const parentPid = process.ppid;
const demoCache = resolve(
  dirname(fileURLToPath(import.meta.url)),
  "..",
  ".cache/demo",
);
const privateKey = join(demoCache, "endpoint-key.pk8");
const stateFile = join(demoCache, "session.json");

if (!Number.isSafeInteger(parentPid) || parentPid <= 1) process.exit(2);

// The launcher may receive Ctrl+C, but this watcher must survive long enough
// to remove the exact key it observed before the parent exited.
process.on("SIGINT", () => {});
process.on("SIGHUP", () => {});
process.on("SIGTERM", () => {});

const directory = lstatSync(demoCache);
const key = lstatSync(privateKey);
if (
  !directory.isDirectory() ||
  directory.isSymbolicLink() ||
  !key.isFile() ||
  key.isSymbolicLink() ||
  key.nlink !== 1 ||
  (typeof process.getuid === "function" &&
    (directory.uid !== process.getuid() || key.uid !== process.getuid())) ||
  (directory.mode & 0o777) !== 0o700 ||
  (key.mode & 0o777) !== 0o600
) {
  process.exit(2);
}
const keyDigest = digest(privateKey);

while (processExists(parentPid)) {
  await new Promise((resolve) => setTimeout(resolve, 250));
}

try {
  const currentDirectory = lstatSync(demoCache);
  const currentKey = lstatSync(privateKey);
  if (
    currentDirectory.dev !== directory.dev ||
    currentDirectory.ino !== directory.ino ||
    currentKey.dev !== key.dev ||
    currentKey.ino !== key.ino ||
    digest(privateKey) !== keyDigest
  ) {
    process.exit(0);
  }
} catch (error) {
  if (error?.code === "ENOENT") process.exit(0);
  throw error;
}
rmSync(privateKey, { force: true });
rmSync(stateFile, { force: true });

function digest(path) {
  return createHash("sha256").update(readFileSync(path)).digest("hex");
}

function processExists(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    if (error?.code === "ESRCH") return false;
    throw error;
  }
}
