import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import { dirname, extname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { targetExists } from "./doc-link-target.mjs";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const markdownRoots = [
  "README.md",
  "CONTRIBUTING.md",
  "SECURITY.md",
  "docs",
  "site",
];
const failures = [];
let checked = 0;

function markdownFiles(entry) {
  const absolute = join(root, entry);
  if (!existsSync(absolute)) return [];
  if (statSync(absolute).isFile())
    return extname(absolute) === ".md" ? [absolute] : [];
  return readdirSync(absolute, { withFileTypes: true }).flatMap((item) => {
    if (item.name.startsWith(".") || item.name === "node_modules") return [];
    return markdownFiles(join(entry, item.name));
  });
}

for (const source of markdownRoots.flatMap(markdownFiles)) {
  const content = readFileSync(source, "utf8");
  const linkPattern = /!?\[[^\]]*\]\((<[^>]+>|[^\s)]+)(?:\s+"[^"]*")?\)/g;
  for (const match of content.matchAll(linkPattern)) {
    const href = match[1].replace(/^<|>$/g, "");
    checked += 1;
    if (!targetExists(root, source, href)) {
      const line = content.slice(0, match.index).split("\n").length;
      failures.push(`${source.slice(root.length + 1)}:${line}: ${href}`);
    }
  }
}

if (failures.length) {
  console.error(`Broken local documentation links (${failures.length}):`);
  for (const failure of failures) console.error(`  ${failure}`);
  process.exitCode = 1;
} else {
  console.log(`Checked ${checked} Markdown links; all local targets exist.`);
}
