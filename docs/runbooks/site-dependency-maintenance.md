# Static site dependency maintenance

The documentation site is built separately from the React console and Java
backend. Its reproducible dependency graph is `site/package-lock.json`.

## Package-manager boundary

The root console/backend workspace uses **pnpm 11.25.0**. The separate static
site uses **npm 11.9.0**, pinned with its SHA-512 archive integrity in
`site/package.json`; its build host needs Node **22.9.0 or newer**. Keep both
lockfiles. Do not install the root workspace with npm or regenerate the site's
graph with pnpm.

From the repository root, use the `pnpm site:*` entry points below. They change
into `site/` before invoking npm, so an npm Corepack shim sees the site's npm
declaration rather than the root's pnpm declaration. `npm --prefix site ...`
from the root does not change Corepack's initial project lookup and can fail
with “This project is configured to use pnpm.” Do not disable Corepack's
project/strict checks or remove the root `packageManager` field to bypass it.

```bash
pnpm site:install
pnpm site:dev
# Or build and serve the generated static output:
pnpm site:build
pnpm site:preview
```

Both commands default to numeric loopback (`127.0.0.1`); Ctrl+C stops the server.
Preview uses the already-patched Vite 6.4.4 CLI explicitly, not VitePress 1.x's
preview server, whose implementation does not bind the supplied host. Vite is
also declared directly so its CLI is an intentional tool dependency. Preview
refuses an occupied port instead of silently selecting another; choose an
available port with `pnpm site:preview -- --port 4174`. It is a local static
preview, not a Cloudflare/CDN cache or redirect-parity test.
Reading Markdown under `site/guide/` needs no local server or Java backend.
Preview needs a successful build. Missing Corepack or dependencies is a setup
failure, not a reason to bypass package-manager checks. npm shims are optional
locally: ordinary installed npm works too, but does not enforce Corepack's
version/integrity selection; check `(cd site && npm --version)` when comparing
builds. These root commands do not install global shims. CI explicitly enables
the npm dispatcher in its disposable runner before using the same commands.

## Dependency security checks

VitePress 1.6.4 is retained as the stable documentation framework. It declares
Vite 5, which leaves Vite and esbuild development-server advisories in its
default dependency graph. A scoped npm override selects Vite 6.4.4 for
VitePress; that graph resolves esbuild 0.25.12. The Vue plugin accepts Vite 6.
This is an explicitly tested override of VitePress's declared major range,
not a claim of upstream VitePress support for every Vite 6 configuration.

The site config also maps the search-index HMR URL to VitePress's virtual module
ID. VitePress 1.x uses a URL in its hot-accept hook; Vite 6 resolves an ID there.
The narrow alias binds that hook to the canonical search-index module and
avoids the corresponding import-analysis warning.

Relevant upstream fixes are described in the
[Vite advisory](https://github.com/vitejs/vite/security/advisories/GHSA-fx2h-pf6j-xcff)
and [esbuild advisory](https://github.com/evanw/esbuild/security/advisories/GHSA-67mh-4wv8-2f99).
The override changes the installed graph; it does not suppress audit findings.
CI blocks reported advisories at low severity or higher before building the
site. An audit is a check against the current advisory database, not proof that
dependencies have no undiscovered vulnerabilities.

```bash
pnpm site:install
(cd site && npm ls vite esbuild)
pnpm site:audit
pnpm site:build
pnpm test:site-tooling
node --test scripts/site-search-compatibility.test.mjs
pnpm docs:links
```

When changing the override, verify the development server and static preview:
home, architecture navigation, local search, diagrams, and JavaScript asset
loading must work. Check both the site build and Cloudflare preview deployment.
Keep the override until a stable VitePress release supplies a patched dependency
graph without it; then regenerate the lockfile and repeat those checks.

The command-boundary regressions use a labelled no-network manager-check stub,
including former root-CWD and missing-site-declaration negative controls. They
do not themselves prove real Corepack downloads, advisory status or UI rendering;
those need separate installed-tool/build/browser checks.

Cloudflare publishes static site output. The site tooling does not execute
agent actions or change the Java enforcement path.
