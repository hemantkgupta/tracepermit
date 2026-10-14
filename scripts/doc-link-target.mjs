import { existsSync } from "node:fs";
import { join, resolve, sep } from "node:path";

const repositoryPath =
  /^\/hemantkgupta\/tracepermit\/(?:blob|tree)\/main\/(.+)$/i;

export function targetExists(root, source, href) {
  const withoutAnchor = href.split("#", 1)[0].split("?", 1)[0];
  if (!withoutAnchor || /^(?:mailto:|data:|tel:)/i.test(withoutAnchor)) {
    return true;
  }

  if (/^https?:/i.test(withoutAnchor)) {
    let url;
    try {
      url = new URL(withoutAnchor);
    } catch {
      return false;
    }
    if (url.hostname.toLowerCase() !== "github.com") return true;
    const match = repositoryPath.exec(url.pathname);
    if (!match) return true;
    let path;
    try {
      path = decodeURIComponent(match[1]);
    } catch {
      return false;
    }
    const candidate = resolve(root, path);
    return candidate.startsWith(`${root}${sep}`) && existsSync(candidate);
  }

  let decoded;
  try {
    decoded = decodeURIComponent(withoutAnchor);
  } catch {
    return false;
  }
  const candidate = decoded.startsWith("/")
    ? resolve(
        root,
        "site",
        decoded.startsWith("/images/") ? "public" : ".",
        `.${decoded}`,
      )
    : resolve(join(source, ".."), decoded);
  return [candidate, `${candidate}.md`, join(candidate, "index.md")].some(
    existsSync,
  );
}
