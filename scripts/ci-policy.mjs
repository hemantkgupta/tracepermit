import { execFileSync } from "node:child_process";
import { appendFileSync } from "node:fs";
import { pathToFileURL } from "node:url";

export const lanes = [
  "core",
  "postgres",
  "mariadb",
  "operations-postgres",
  "operations-mariadb106",
  "operations-mariadb114",
];

export function classify(paths) {
  if (!Array.isArray(paths) || paths.length === 0) return "full";
  let consoleChanged = false;
  for (const path of paths) {
    if (
      typeof path !== "string" ||
      !path ||
      path.includes("\ufffd") ||
      path.includes("\\") ||
      path.split("/").some((part) => part === ".." || part === ".")
    )
      return "full";
    if (
      /^(README|CONTRIBUTING|SECURITY)\.md$/.test(path) ||
      /^docs\/.+\.md$/.test(path) ||
      path.startsWith("site/")
    )
      continue;
    if (
      path.startsWith("apps/console/src/") ||
      path.startsWith("apps/console/public/") ||
      path === "apps/console/index.html"
    ) {
      consoleChanged = true;
      continue;
    }
    return "full";
  }
  return consoleChanged ? "frontend" : "docs";
}

export function plan(base, head, cwd = process.cwd()) {
  if (![base, head].every((sha) => /^[a-f0-9]{40,64}$/.test(sha ?? "")))
    return "full";
  try {
    const names = execFileSync(
      "git",
      [
        "diff",
        "--no-ext-diff",
        "--no-textconv",
        "--name-only",
        "--no-renames",
        "-z",
        `${base}...${head}`,
        "--",
      ],
      { cwd, maxBuffer: 8 * 1024 * 1024, stdio: ["ignore", "pipe", "ignore"] },
    );
    const decoded = new TextDecoder("utf-8", { fatal: true }).decode(names);
    if (!decoded.endsWith("\0")) return "full";
    return classify(decoded.slice(0, -1).split("\0"));
  } catch {
    return "full";
  }
}

export function verify(needs) {
  if (needs?.changes?.result !== "success")
    throw new Error("Change detection did not succeed");
  const mode = needs.changes.outputs?.mode;
  if (!["full", "frontend", "docs"].includes(mode))
    throw new Error("Unknown CI plan");
  const required = {
    docs: true,
    "site-browser": true,
    core: mode !== "docs",
    packaging: mode !== "docs",
    "browser-approvals": mode !== "docs",
    databases: mode === "full",
    "operation-repository": mode === "full",
    coverage: mode === "full",
    qualification: mode === "full",
  };
  for (const [job, selected] of Object.entries(required)) {
    const result = needs[job]?.result;
    if (
      selected ? result !== "success" : !["success", "skipped"].includes(result)
    ) {
      throw new Error(`CI job ${job} did not satisfy the ${mode} plan`);
    }
  }
  return mode;
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  if (process.argv[2] === "plan") {
    const mode = plan(process.argv[3], process.argv[4]);
    appendFileSync(process.env.GITHUB_OUTPUT, `mode=${mode}\n`);
    console.log(`Selected CI plan: ${mode}`);
  } else if (process.argv[2] === "verify") {
    console.log(
      `All jobs satisfy the ${verify(JSON.parse(process.env.NEEDS_JSON))} plan.`,
    );
  } else {
    throw new Error("Unknown CI policy command");
  }
}
