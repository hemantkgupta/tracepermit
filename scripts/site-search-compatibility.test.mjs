import assert from "node:assert/strict";
import {
  copyFileSync,
  mkdirSync,
  mkdtempSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { createServer } from "../site/node_modules/vitepress/dist/node/index.js";

const repo = dirname(dirname(fileURLToPath(import.meta.url)));
const dependencies = join(repo, "site/node_modules");

test(
  "site search compiles its canonical HMR binding and generated index",
  { timeout: 30000 },
  async () => {
    const root = mkdtempSync(join(tmpdir(), "tracepermit-site-search-"));
    let server;
    try {
      mkdirSync(join(root, ".vitepress"));
      copyFileSync(
        join(repo, "site/.vitepress/config.mts"),
        join(root, ".vitepress/config.mts"),
      );
      symlinkSync(dependencies, join(root, "node_modules"), "dir");
      writeFileSync(
        join(root, "index.md"),
        "# SearchBoundaryAlpha\n\nSynthetic search fixture.\n",
      );
      const warnings = [];
      server = await createServer(root, {
        host: "127.0.0.1",
        port: 0,
        preTransformRequests: false,
      });
      server.config.logger.warn = (message) => {
        warnings.push(message);
      };
      await server.listen();
      const component = join(
        dependencies,
        "vitepress/dist/client/theme-default/components/VPLocalSearchBox.vue",
      );
      const compiled = await server.transformRequest(`/@fs/${component}`);
      assert.ok(
        compiled?.code,
        "The actual VitePress search component must compile.",
      );
      const modules = server.moduleGraph.getModulesByFile(component);
      assert.ok(
        modules &&
          [...modules].some((module) =>
            [...module.acceptedHmrDeps].some(
              (dependency) => dependency.id === "/@localSearchIndex",
            ),
          ),
        "The component must accept updates from the canonical search-index module.",
      );
      assert.equal(
        warnings.some((message) =>
          /Failed to resolve.*localSearchIndex/.test(message),
        ),
        false,
      );

      const index = await server.transformRequest("/@localSearchIndex");
      const locale = index.code.match(
        /import\("([^"?]+localSearchIndex[^"?]*)[^"\n]*"\)/,
      )?.[1];
      assert.ok(locale, "The generated index must provide a locale import.");
      const loadIndex = async () => {
        const loaded = await server.pluginContainer.load(locale);
        return typeof loaded === "string" ? loaded : (loaded?.code ?? "");
      };
      assert.match(await loadIndex(), /SearchBoundaryAlpha/);
    } finally {
      if (server) await server.close();
      rmSync(root, { recursive: true, force: true });
    }
  },
);
