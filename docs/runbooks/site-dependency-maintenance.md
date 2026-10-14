# Static site dependency maintenance

The documentation site is built separately from the React console and Java
backend. Its reproducible dependency graph is `site/package-lock.json`.

VitePress 1.6.4 is retained as the stable documentation framework. It declares
Vite 5, which leaves Vite and esbuild development-server advisories in its
default dependency graph. A scoped npm override selects Vite 6.4.4 for
VitePress; that graph resolves esbuild 0.25.12. The Vue plugin accepts Vite 6.
This is an explicitly tested override of VitePress's declared major range,
not a claim of upstream VitePress support for every Vite 6 configuration.

Relevant upstream fixes are described in the
[Vite advisory](https://github.com/vitejs/vite/security/advisories/GHSA-fx2h-pf6j-xcff)
and [esbuild advisory](https://github.com/evanw/esbuild/security/advisories/GHSA-67mh-4wv8-2f99).
The override changes the installed graph; it does not suppress audit findings.
CI blocks reported advisories at low severity or higher before building the
site. An audit is a check against the current advisory database, not proof that
dependencies have no undiscovered vulnerabilities.

```bash
npm --prefix site ci
npm --prefix site ls vite esbuild
npm --prefix site audit --audit-level=low
pnpm site:build
pnpm docs:links
```

When changing the override, verify the development server and static preview:
home, architecture navigation, local search, diagrams, and JavaScript asset
loading must work. Check both the site build and Cloudflare preview deployment.
Keep the override until a stable VitePress release supplies a patched dependency
graph without it; then regenerate the lockfile and repeat those checks.

Cloudflare publishes static site output. The site tooling does not execute
agent actions or change the Java enforcement path.
