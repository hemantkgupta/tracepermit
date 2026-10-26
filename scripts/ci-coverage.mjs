import { createHash } from "node:crypto";
import {
  copyFileSync,
  existsSync,
  lstatSync,
  mkdirSync,
  readdirSync,
  readFileSync,
  writeFileSync,
} from "node:fs";
import { join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { lanes } from "./ci-policy.mjs";

const databaseModules = [
  "persistence-postgres",
  "persistence-mariadb",
  "persistence-operations",
];
export function modulesFor(lane, root) {
  if (!lanes.includes(lane)) throw new Error("Unknown coverage lane");
  if (lane === "postgres") return ["persistence-postgres"];
  if (lane === "mariadb") return ["persistence-mariadb"];
  if (lane.startsWith("operations-")) return ["persistence-operations"];
  return readdirSync(join(root, "java"), { withFileTypes: true })
    .filter(
      (entry) =>
        entry.isDirectory() &&
        existsSync(join(root, "java", entry.name, "build.gradle.kts")),
    )
    .map((entry) => entry.name)
    .filter((name) => !databaseModules.includes(name))
    .concat("persistence-operations")
    .sort();
}
function digest(file) {
  if (!lstatSync(file).isFile() || lstatSync(file).isSymbolicLink())
    throw new Error("Coverage must be a regular file");
  const bytes = readFileSync(file);
  if (!bytes.length) throw new Error("Empty coverage data");
  return createHash("sha256").update(bytes).digest("hex");
}
function identity(sha, run) {
  if (!/^[a-f0-9]{40,64}$/.test(sha ?? "") || !/^[0-9]+$/.test(run ?? ""))
    throw new Error("Missing coverage run identity");
}
export function exportCoverage(root, destination, lane, sha, run) {
  identity(sha, run);
  const dir = join(destination, lane);
  mkdirSync(dir, { recursive: true });
  const modules = modulesFor(lane, root);
  const files = {};
  for (const module of modules) {
    const source = join(root, "java", module, "build/jacoco/test.exec");
    files[module] = digest(source);
    copyFileSync(source, join(dir, `${module}.exec`));
  }
  writeFileSync(
    join(dir, "manifest.json"),
    JSON.stringify({ version: 1, lane, sha, run, files }, null, 2) + "\n",
  );
}
export function validateCoverage(root, destination, sha, run) {
  identity(sha, run);
  const actual = readdirSync(destination).sort();
  if (JSON.stringify(actual) !== JSON.stringify([...lanes].sort()))
    throw new Error("Missing or unexpected coverage shards");
  for (const lane of lanes) {
    const dir = join(destination, lane);
    if (lstatSync(dir).isSymbolicLink())
      throw new Error("Invalid coverage directory");
    digest(join(dir, "manifest.json"));
    const manifest = JSON.parse(
      readFileSync(join(dir, "manifest.json"), "utf8"),
    );
    if (
      manifest.version !== 1 ||
      manifest.lane !== lane ||
      manifest.sha !== sha ||
      manifest.run !== run
    )
      throw new Error("Stale or mismatched coverage identity");
    const modules = modulesFor(lane, root);
    if (
      JSON.stringify(Object.keys(manifest.files).sort()) !==
      JSON.stringify([...modules].sort())
    )
      throw new Error("Missing coverage module");
    if (
      JSON.stringify(readdirSync(dir).sort()) !==
      JSON.stringify(
        ["manifest.json", ...modules.map((name) => `${name}.exec`)].sort(),
      )
    )
      throw new Error("Unexpected coverage file");
    for (const module of modules)
      if (manifest.files[module] !== digest(join(dir, `${module}.exec`)))
        throw new Error("Corrupt coverage shard");
  }
}
if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  const root = resolve(fileURLToPath(new URL("..", import.meta.url)));
  const destination = join(root, "java/build/ci-coverage");
  if (process.argv[2] === "export")
    exportCoverage(
      root,
      destination,
      process.argv[3],
      process.env.GITHUB_SHA,
      process.env.GITHUB_RUN_ID,
    );
  else if (process.argv[2] === "validate")
    validateCoverage(
      root,
      destination,
      process.env.GITHUB_SHA,
      process.env.GITHUB_RUN_ID,
    );
  else throw new Error("Unknown coverage command");
}
