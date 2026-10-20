# Node Setup Action Upgrade Qualification (actions/setup-node v5.0.0 to v7.1.0)

Context: Dependabot PR #11 (`dependabot/github_actions/actions/setup-node-7.1.0`), which bumps the pinned `actions/setup-node` ref in `.github/workflows/ci.yml` from `a0853c24544627f65ddf259abe73b1d18a591444` (tag `v5.0.0`) to `949feb2413d6458794dcd2491c4babbbce0c15c1` (tag `v7.1.0`).  
Scope: CI workflow infrastructure only. No product, enforcement, contract, simulator, or application dependency changes are included, so no FR/SEC/DATA requirement IDs are affected.  
Related Runbooks: [PostgreSQL JDBC driver upgrade qualification](postgresql-jdbc-upgrade-qualification.md), [Site dependency maintenance](site-dependency-maintenance.md).

This runbook records the primary-source provenance, runtime and runner compatibility assessment, behavior-change review, and local qualification results for that bump. It was produced by independent verification against the official `actions/setup-node` repository (release code, tags, and release notes), not from the PR title or the Dependabot summary alone.

---

## 1. Change Inventory and Pin Provenance

The Dependabot commit changes exactly two lines in `.github/workflows/ci.yml`, one in each job (`verify` and `browser-approvals`):

```diff
- uses: actions/setup-node@a0853c24544627f65ddf259abe73b1d18a591444 # v5
+ uses: actions/setup-node@949feb2413d6458794dcd2491c4babbbce0c15c1 # v7.1.0
```

Both sides are full 40-hex immutable commits; no floating major tag is introduced. The adjacent `with:` blocks (`node-version: 22`, `cache: pnpm`) are unchanged, as are all other action pins (`actions/checkout` and `actions/setup-java`, both still at their existing pinned SHAs) and the single top-level `permissions: contents: read` block.

Provenance was verified against the official repository with the GitHub API:

| Claim                                    | Official evidence                                                                                                                                                                                         |
| ---------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| New SHA resolves in `actions/setup-node` | Commit `949feb2413d6458794dcd2491c4babbbce0c15c1` exists; authored 2026-10-08; message "Bump eslint from 10.5.0 to 10.11.0 and package.json from 7.0.0 to 7.1.0 (#1635)" — the v7.1.0 release commit      |
| New SHA is v7.1.0                        | Official tag [`v7.1.0`](https://github.com/actions/setup-node/releases/tag/v7.1.0) points at that commit                                                                                                  |
| Old SHA is v5.0.0                        | Official tag [`v5.0.0`](https://github.com/actions/setup-node/releases/tag/v5.0.0) points at `a0853c24544627f65ddf259abe73b1d18a591444`                                                                   |
| Interval                                 | The bump spans two majors: `v6.0.0` ([notes](https://github.com/actions/setup-node/releases/tag/v6.0.0)) and `v7.0.0` ([notes](https://github.com/actions/setup-node/releases/tag/v7.0.0)), plus `v7.1.0` |

`ci.yml` is the only workflow file in the repository, so both `setup-node` uses are covered by this assessment.

## 2. Action Runtime and Runner Compatibility

The Node.js runtime that executes the action and the project's Node.js runtime are distinct:

- **Action runtime (release code):** `action.yml` at both pinned SHAs declares `runs.using: node24`, which tells the runner to execute the action's JavaScript (`dist/setup/index.js`, plus the `dist/cache-save/index.js` post step) with the Node 24 runtime the runner supplies; the action does not bundle a runtime. This execution runtime is independent of what the workflow installs for builds.
- **Project runtime (workflow input):** the `node-version: 22` input installs Node.js 22 for the console/Java/tooling steps. The project's pnpm 11.25.0 comes from the `packageManager` field via `corepack enable`, followed by `pnpm install --frozen-lockfile`. Neither is touched by this PR.
- **Runner minimum:** the node24 runtime was introduced in v5.0.0, whose release notes require [actions/runner v2.327.1 or later](https://github.com/actions/runner/releases/tag/v2.327.1). v7.1.0 keeps `node24`, so this bump does **not** raise the runner minimum. Both jobs run on GitHub-hosted `ubuntu-latest` images, which receive current runner versions, and CI already ran the v5.0.0 (node24) action on these runners.

## 3. Behavior Changes Across the v6 and v7 Majors

Assessed from the official v6.0.0/v7.0.0/v7.1.0 release notes and `action.yml` at the pinned SHA, against the two call sites in `ci.yml`:

| Upstream change                                                                                                | Effect on TracePermit CI                                                                                                                                                                                                   |
| -------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| v6.0.0 (breaking): automatic caching limited to npm projects (detected via `packageManager`)                   | None. This repo's `packageManager` is `pnpm@11.25.0`, so automatic npm caching never engaged. Both jobs set `cache: pnpm` explicitly, which the README documents as the supported manual path for pnpm (pnpm v6.10+).      |
| v7.0.0: dummy `NODE_AUTH_TOKEN` fallback removed                                                               | None. The workflow sets no `registry-url` or `scope`, and the repository defines no `NODE_AUTH_TOKEN`; installs resolve from the public registry, so no `.npmrc` auth is generated. No credentials are introduced.         |
| v7.0.0: new `cache-primary-key` / `cache-matched-key` outputs; ESM migration of the action's dist              | No call-site impact; the outputs are not consumed and the dist migration is internal to the action.                                                                                                                        |
| v7.1.0: `mise.toml` and absolute `node-version-file` support; Node version validation and manifest fetch retry | No call-site impact (neither input is used); validation/retry is a robustness improvement.                                                                                                                                 |
| Cache key behavior with `cache: pnpm` and no `cache-dependency-path`                                           | Unchanged by the bump: the dependency file is resolved at the repository root, where `pnpm-lock.yaml` lives, and its hash forms part of the cache key. The input set and lockfile location are identical before and after. |

The default action `token` remains the ephemeral `github.token`, used only to fetch Node.js version manifests, under the unchanged `contents: read` permission scope.

## 4. Security and Permission Boundaries

Verified unchanged by reading the full workflow at the reconciled head:

- Trigger is `pull_request` plus `push` to `main`; there is no `pull_request_target`.
- A single top-level `permissions: contents: read`; neither job escalates it.
- No new secrets, environment variables, registry credentials, or third-party actions; no self-hosted runners (`runs-on: ubuntu-latest` in both jobs).
- The diff introduces no `with:` input changes.

## 5. Local Qualification Results

Main was reconciled into the branch after an overlap review: `main` advanced from the PR base by one merge (PR #60, approval-expiry UX) with no changes to `.github/workflows/ci.yml`, so the merge was conflict-free and the Dependabot commit required no modification. All checks ran at the reconciled head with Node.js >= 22, pnpm 11.25.0 (corepack), Java 21, and a `--frozen-lockfile` install.

| Gate                                                       | Result                                         |
| ---------------------------------------------------------- | ---------------------------------------------- |
| `pnpm check` (console build, typecheck, tests, Java suite) | PASS                                           |
| `pnpm format:check`                                        | PASS                                           |
| `pnpm docs:links`                                          | PASS (all local documentation targets resolve) |
| `pnpm docs:flows:check`                                    | PASS (diagrams and guide sources synchronized) |

No repair beyond the reconciliation was needed; the Dependabot commit's pins matched the official tags exactly and were adopted as-is.

## 6. CI Verification and Observed-Versus-Untested Boundaries

Local runs cannot prove GitHub-hosted behavior. The following are observable only in the Actions runs at this exact head and are classified there (see the PR description for the exact-head run URLs): the installed Node.js 22 and pnpm 11.25.0 versions as printed on the runner, the frozen-install step, the pnpm cache restore key and save outcome (hit, miss, or post-run save), the full `verify` gate chain including both PostgreSQL passes and the source-free distribution build, `browser-approvals`, and the Cloudflare Pages deployment. A green `browser-approvals` job alone does not qualify the change; the `verify` job exercises the `setup-node` step under the same inputs and is the load-bearing signal. Cache reuse (warm hit) versus a first-population miss is reported separately; a single miss-only run does not demonstrate warm-cache behavior.

## 7. Out of Scope

Other action upgrades (`actions/checkout`, `actions/setup-java`), connector or product repairs, lockfile or application dependency changes, artifact release automation, self-hosted runners, and any registry or publishing configuration. No workflow steps, permission boundaries, or retry/threshold settings were altered.
