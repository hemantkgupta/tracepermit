import { readFile, writeFile, mkdir } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";

export const repositoryRoot = fileURLToPath(new URL("../", import.meta.url));
const escapeXml = (text) =>
  text.replace(
    /[&<>"']/g,
    (char) =>
      ({
        "&": "&amp;",
        "<": "&lt;",
        ">": "&gt;",
        '"': "&quot;",
        "'": "&apos;",
      })[char],
  );

export function validateCatalog(catalog) {
  if (
    !catalog ||
    catalog.schemaVersion !== 1 ||
    !Array.isArray(catalog.diagrams) ||
    !catalog.diagrams.length
  )
    throw new Error("Invalid flow catalog version.");
  const ids = new Set();
  for (const diagram of catalog.diagrams) {
    if (
      !diagram ||
      !/^(central-invariant|flow-[1-9]-[a-z-]+)$/.test(diagram.id) ||
      ids.has(diagram.id)
    )
      throw new Error("Invalid or duplicate flow ID.");
    ids.add(diagram.id);
    if (
      !Array.isArray(diagram.participants) ||
      diagram.participants.some(
        (item) => !Array.isArray(item) || item.length !== 2,
      )
    )
      throw new Error("Invalid participants.");
    const participants = new Set(diagram.participants.map(([id]) => id));
    if (
      participants.size !== diagram.participants.length ||
      participants.size < 2 ||
      participants.size > 5
    )
      throw new Error("Invalid participants.");
    for (const [id, label] of diagram.participants) {
      if (
        !/^[A-Z]$/.test(id) ||
        typeof label !== "string" ||
        !label ||
        label.length > 40 ||
        /[\n\r<>;]/.test(label) ||
        wrap(label, 19).length > 2
      )
        throw new Error("Invalid participant label.");
    }
    if (
      typeof diagram.title !== "string" ||
      diagram.title.length > 95 ||
      !diagram.title ||
      /[\n\r<>]/.test(diagram.title)
    )
      throw new Error("Invalid diagram title.");
    if (
      !Array.isArray(diagram.steps) ||
      !diagram.steps.length ||
      diagram.steps.length > 40 ||
      diagram.steps.some((item) => !Array.isArray(item) || item.length !== 4)
    )
      throw new Error("Invalid diagram steps.");
    for (const [from, to, text, kind] of diagram.steps) {
      if (
        !new Set(["call", "return", "note"]).has(kind) ||
        typeof text !== "string" ||
        !text ||
        text.length > 180 ||
        /[\n\r<>;]/.test(text)
      )
        throw new Error("Invalid diagram step.");
      if (kind !== "note" && (!participants.has(from) || !participants.has(to)))
        throw new Error("Unknown participant reference.");
      if (kind === "note" && (from !== "" || to !== ""))
        throw new Error("Notes must span the diagram.");
    }
  }
  return catalog;
}

export function mermaid(diagram) {
  const lines = ["sequenceDiagram", "  autonumber"];
  for (const [id, label] of diagram.participants)
    lines.push(`  participant ${id} as ${label}`);
  const first = diagram.participants[0][0];
  const last = diagram.participants.at(-1)[0];
  for (const [from, to, text, kind] of diagram.steps) {
    lines.push(
      kind === "note"
        ? `  Note over ${first},${last}: ${text}`
        : `  ${from}${kind === "return" ? "-->>" : "->>"}${to}: ${text}`,
    );
  }
  return lines.join("\n");
}

export function wrap(text, maximum) {
  const lines = [];
  let line = "";
  for (const word of text.split(/\s+/)) {
    if (line && line.length + word.length + 1 > maximum) {
      lines.push(line);
      line = "";
    }
    line += (line ? " " : "") + word;
  }
  if (line) lines.push(line);
  return lines;
}

export function svg(diagram) {
  const width = 1000;
  const positions = new Map(
    diagram.participants.map(([id], index) => [
      id,
      122 + (index * (width - 244)) / (diagram.participants.length - 1),
    ]),
  );
  const rows = diagram.steps.map((step) => ({
    step,
    lines: wrap(step[2], 102),
  }));
  const height =
    144 + rows.reduce((sum, row) => sum + 48 + row.lines.length * 20, 0);
  const output = [
    `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" role="img" aria-labelledby="${diagram.id}-title ${diagram.id}-desc">`,
    `<title id="${diagram.id}-title">${escapeXml(diagram.title)}</title>`,
    `<desc id="${diagram.id}-desc">${escapeXml(diagram.steps.map((step) => step[2]).join(". "))}</desc>`,
    "<!-- Generated from docs/design/flow-diagrams.json. Do not edit this SVG directly. -->",
    '<defs><marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M1 1 L9 5 L1 9" fill="none" stroke="#087b87" stroke-width="1.7"/></marker></defs>',
    '<style>text{font-family:system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;fill:#17243b}.label{font-size:16px}.heading{font-size:15px;font-weight:650}.step{font-size:13px;font-weight:700;fill:white}.life{stroke:#cbd5e1;stroke-dasharray:5 5}.arrow{stroke:#087b87;stroke-width:1.8;fill:none;marker-end:url(#arrow)}</style>',
    `<rect x="1" y="1" width="998" height="${height - 2}" rx="12" fill="white" stroke="#dbe3ed"/>`,
    `<text x="28" y="34" font-size="19" font-weight="700">${escapeXml(diagram.title)}</text>`,
  ];
  for (const [id, label] of diagram.participants) {
    const x = positions.get(id);
    output.push(
      `<line x1="${x}" y1="109" x2="${x}" y2="${height - 24}" class="life"/>`,
      `<rect x="${x - 84}" y="54" width="168" height="55" rx="8" fill="#eff8fa" stroke="#b6d9de"/>`,
    );
    const lines = wrap(label, 19);
    lines.forEach((line, index) =>
      output.push(
        `<text x="${x}" y="${77 + index * 19}" text-anchor="middle" class="heading">${escapeXml(line)}</text>`,
      ),
    );
  }
  let y = 130;
  let messageNumber = 0;
  rows.forEach(({ step: [from, to, , kind], lines }) => {
    const rowHeight = 48 + lines.length * 20;
    if (kind === "note")
      output.push(
        `<rect x="55" y="${y - 3}" width="920" height="${rowHeight - 15}" rx="8" fill="#f1f5f9" stroke="#dbe3ed"/>`,
      );
    if (kind !== "note") {
      messageNumber++;
      output.push(
        `<circle cx="28" cy="${y + 10}" r="12" fill="#087b87"/>`,
        `<text x="28" y="${y + 15}" text-anchor="middle" class="step">${messageNumber}</text>`,
      );
    }
    lines.forEach((line, lineIndex) =>
      output.push(
        `<text x="68" y="${y + 16 + lineIndex * 20}" class="label">${escapeXml(line)}</text>`,
      ),
    );
    if (kind !== "note") {
      const source = positions.get(from),
        destination = positions.get(to),
        arrowY = y + lines.length * 20 + 10;
      const dash = kind === "return" ? ' stroke-dasharray="6 4"' : "";
      output.push(
        source === destination
          ? `<path d="M${source} ${arrowY} h30 v17 h-30" class="arrow"${dash}/>`
          : `<path d="M${source} ${arrowY} H${destination}" class="arrow"${dash}/>`,
      );
    }
    y += rowHeight;
  });
  output.push("</svg>");
  return output.join("\n") + "\n";
}

export async function generatedFiles(root = repositoryRoot) {
  const catalog = validateCatalog(
    JSON.parse(
      await readFile(path.join(root, "docs/design/flow-diagrams.json"), "utf8"),
    ),
  );
  const files = new Map();
  for (const diagram of catalog.diagrams)
    files.set(`site/public/images/flows/${diagram.id}.svg`, svg(diagram));
  for (const filename of ["docs/flows-guide.md", "site/guide/core-flows.md"]) {
    let markdown = await readFile(path.join(root, filename), "utf8");
    for (const diagram of catalog.diagrams) {
      const start = `<!-- diagram:${diagram.id} -->`,
        end = `<!-- /diagram:${diagram.id} -->`;
      const startIndex = markdown.indexOf(start),
        endIndex = markdown.indexOf(end);
      if (
        startIndex < 0 ||
        endIndex < startIndex ||
        markdown.indexOf(start, startIndex + 1) >= 0 ||
        markdown.indexOf(end, endIndex + 1) >= 0
      )
        throw new Error(`${filename}: missing/duplicate ${diagram.id} marker.`);
      markdown =
        markdown.slice(0, startIndex) +
        `${start}\n\n\`\`\`mermaid\n${mermaid(diagram)}\n\`\`\`\n\n` +
        markdown.slice(endIndex);
    }
    const markerIds = [
      ...markdown.matchAll(/<!-- \/?diagram:([^ ]+) -->/g),
    ].map((match) => match[1]);
    if (
      markerIds.length !== catalog.diagrams.length * 2 ||
      markerIds.some(
        (id) => !catalog.diagrams.some((diagram) => diagram.id === id),
      )
    )
      throw new Error(`${filename}: unknown diagram marker.`);
    files.set(filename, markdown);
  }
  return files;
}

export async function staleFiles(files, root = repositoryRoot) {
  const stale = [];
  for (const [filename, content] of files) {
    let existing;
    try {
      existing = await readFile(path.join(root, filename), "utf8");
    } catch (error) {
      if (error.code !== "ENOENT") throw error;
    }
    if (existing !== content) stale.push(filename);
  }
  return stale;
}

async function main() {
  const args = process.argv.slice(2);
  if (args.length > 1 || (args.length && args[0] !== "--check"))
    throw new Error("Use --check or no arguments.");
  const files = await generatedFiles();
  if (args[0] === "--check") {
    const stale = await staleFiles(files);
    for (const filename of stale)
      process.stderr.write(`Stale flow artifact: ${filename}\n`);
    if (stale.length) {
      process.exitCode = 1;
      return;
    }
  } else {
    for (const [filename, content] of files) {
      const target = path.join(repositoryRoot, filename);
      await mkdir(path.dirname(target), { recursive: true });
      await writeFile(target, content);
    }
  }
  process.stdout.write(
    "Flow diagrams and both guide sources are synchronized.\n",
  );
}

if (
  process.argv[1] &&
  path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  main().catch((error) => {
    process.stderr.write(error.message + "\n");
    process.exitCode = 1;
  });
}
