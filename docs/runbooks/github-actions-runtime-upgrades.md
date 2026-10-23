# GitHub Actions runtime upgrade qualification

This CI-only slice starts from `main` commit
`d0476dd46dd5f933aed3545647f59d066f362ea9` and combines the intended action
upgrades from PRs #4, #11 and #78. Those PRs and their branches are unchanged.
The only changed paths are this runbook and `.github/workflows/ci.yml`.
Application behavior, security policy and requirement implementation status are
unchanged. No FR/SEC/DATA requirement is newly satisfied.

## Independently verified release pins

Each official GitHub tag was resolved through `git/ref/tags/<version>` (and
peeled if annotated), then compared with the action reference. Each SHA occurs
twice, once in `verify` and once in `browser-approvals`.

| Official action      | Previous release and commit                         | Qualified target release and commit                                                                              |
| -------------------- | --------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| `actions/checkout`   | v5.1.0 — `fbc6f3992d24b796d5a048ff273f7fcc4a7b6c09` | [v7.0.1](https://github.com/actions/checkout/releases/tag/v7.0.1) — `3d3c42e5aac5ba805825da76410c181273ba90b1`   |
| `actions/setup-node` | v5.0.0 — `a0853c24544627f65ddf259abe73b1d18a591444` | [v7.1.0](https://github.com/actions/setup-node/releases/tag/v7.1.0) — `949feb2413d6458794dcd2491c4babbbce0c15c1` |
| `actions/setup-java` | v5.7.0 — `b6effb05e454b25005698d916606bdc6ffcbf961` | [v6.1.0](https://github.com/actions/setup-java/releases/tag/v6.1.0) — `b843ebf9b913146b38da2eaa7aeddf76074ae1ad` |

The supplied checkout baseline said v5.0.1. The actual base SHA resolves to
v5.1.0; official v5.0.1 instead resolves to
`93cb6efe18208431cddfb8368fd83d5badbf9bfd`. Version comments now name the exact
target releases; execution still uses immutable 40-hex commits.

PR #11's `setup-node-action-qualification.md` supplied useful investigation
topics: action/project runtime separation, explicit pnpm caching, runner
requirements and observed-versus-untested limits. Its workstation results,
historical merges and green-CI claims are not evidence for this combined head.
The release tags, metadata, sources and results below are independently checked.

## Runtime and behavior review

All six old/new `action.yml` files declare `runs.using: node24`. The actions'
execution runtime is separate from the installed **Node 22** used by the
project. Node 24 actions require runner
[v2.327.1 or later](https://github.com/actions/runner/releases/tag/v2.327.1).
Checkout's authenticated Git support inside Docker container actions requires
[v2.329.0 or later](https://github.com/actions/runner/releases/tag/v2.329.0).
Both jobs retain GitHub-hosted `ubuntu-latest`; neither minimum is a new
requirement to install Node 24 as the project runtime.

- **Checkout:** reviewed [v6 migration](https://github.com/actions/checkout/releases/tag/v6.0.0),
  [v7 changes](https://github.com/actions/checkout/releases/tag/v7.0.0) and
  [pinned metadata/source](https://github.com/actions/checkout/tree/3d3c42e5aac5ba805825da76410c181273ba90b1).
  Persisted credentials move from local Git configuration values to a separate
  `$RUNNER_TEMP` credentials file referenced by Git configuration, with post-job
  cleanup. Default persistence remains enabled; no credential contents are
  inspected or exported. ESM packaging is internal. Unsafe fork checkout
  protection for `pull_request_target`/`workflow_run` remains enabled (already
  backported to the actual v5.1.0 baseline); this workflow uses neither trigger
  and does not opt into `allow-unsafe-pr-checkout`. Default PR merge-ref checkout
  and fetch depth remain unchanged, preserving package source provenance.
- **Setup Node:** reviewed [v6 changes](https://github.com/actions/setup-node/releases/tag/v6.0.0),
  [v7 changes](https://github.com/actions/setup-node/releases/tag/v7.0.0) and
  [pinned sources](https://github.com/actions/setup-node/tree/949feb2413d6458794dcd2491c4babbbce0c15c1).
  v6 restricts automatic caching to npm; explicit `cache: pnpm` takes precedence
  in both old and new versions. v7 removes the dummy `NODE_AUTH_TOKEN` fallback
  and migrates to ESM. This workflow has no registry publishing/auth inputs or
  consumed cache outputs. v7.1 version-file support is unused. Corepack is
  enabled before setup-node queries pnpm; `packageManager: pnpm@11.25.0` and the
  frozen installation remain unchanged.
- **Setup Java:** reviewed [v6 migration](https://github.com/actions/setup-java/releases/tag/v6.0.0),
  v6.1 notes and [pinned sources](https://github.com/actions/setup-java/tree/b843ebf9b913146b38da2eaa7aeddf76074ae1ad).
  Java remains Temurin 21. ESM, new distribution support and renamed publishing
  inputs require no call-site change here. Removed Adopt distributions are
  unused. JDK download checksum/signature handling is retained without disabling
  verification. v6 adds conditional JDK/resolution caching and additional cache
  controls; those upstream defaults are documented rather than called unchanged.

## Permissions, caches and provenance

Semantic YAML comparison must show exactly six `uses` changes. Step order,
inputs, commands, timeouts, triggers, runner choice and gates are identical.
The complete job chain still covers React/Java validation, native launcher,
site install/audit/build, Gate C, PostgreSQL rerun, simulator distribution,
container profile, endpoint distribution, formatting, links/flows, approval
browsers, real downloaded export verification and responsive site browsers.

- Top-level `permissions: contents: read` is unchanged; there are no job-level
  overrides, write grants, OIDC permission, `pull_request_target`, new secrets,
  PAT inputs or publishing steps. Unspecified token scopes are disabled;
  GitHub also reports implicit `metadata: read`. Cache-service access uses its
  scoped runtime mechanism, not a new `actions: write` grant to `GITHUB_TOKEN`.
- Dependabot's read-only `pull_request` restriction remains applicable. Check
  actual Dependabot-origin job initialization receipts as well as this PR's
  effective permissions. Reading an existing bot run does not establish a new
  Dependabot execution at this combined head; unchanged policy is assessed
  separately from that observation. Do not rerun or modify the source PRs.
- pnpm caches the store (`pnpm store path --silent`), not `node_modules`.
  Source review of both versions gives
  `node-cache-${RUNNER_OS}-${arch}-pnpm-${hash(pnpm-lock.yaml)}`. Root lockfile
  selection is unchanged; the separate npm-managed site is still installed
  with `npm --prefix site ci`. No registry credentials are introduced.
- Gradle caches remain under the runner's `~/.gradle/caches`, plus a separate
  `~/.gradle/wrapper` cache. The existing exported
  `TRACEPERMIT_GRADLE_USER_HOME=$HOME/.gradle` keeps repository scripts aligned.
  Dependency keys are `setup-java-${RUNNER_OS}-${arch}-gradle-${hash(inputs)}`;
  wrapper keys use the separate `gradle-wrapper` namespace and wrapper-property
  hash. v6 adds `**/gradle.properties` to the dependency hash inputs (alongside
  Gradle scripts, wrapper properties and version catalogs/properties), so a
  previous cache miss is expected rather than a gate failure. Separate wrapper
  caching was already present in v5.7.0. JDK/resolution caching is additional;
  no custom cache paths, read-only switch or disabled verification is added.
- A restore hit, miss and successful post-save are different observations.
  Record actual outcomes; a successful cold run alone does not prove warm reuse.
  A cache is a performance hint, never authority or artifact attestation.
  Existing archive tasks still require clean source metadata and retain their
  manifest/checksum checks. Neither action pins nor archive hashes sign a release.

## Reproduce and record qualification

Use Node 22, Corepack/pnpm 11.25.0, Java 21 and working Docker for the Java
PostgreSQL suites. Keep existing proxy/CA trust and simulator-only targets.
Never print credential-bearing Git configuration, environments or cache data.

```bash
corepack enable
pnpm install --frozen-lockfile
actionlint .github/workflows/ci.yml
pnpm check
pnpm format:check
pnpm docs:links
pnpm docs:flows:check
git diff --check
```

Additionally parse YAML with duplicate-key rejection, resolve all six action
references against official tags, compare the workflow semantically with the
recorded base, and inspect effective permissions and cache-key inputs. Local
qualification uses checksum-verified Node 22 and actionlint releases; local
commands do not execute GitHub's action runtime or prove hosted behavior.

The draft PR is the qualification receipt: record its exact head, local command
outcomes and exact-head `CI/verify`, `CI/browser-approvals` and Cloudflare checks,
plus observed runner version, Node/pnpm/Java versions, effective token scopes
and cache restore/save results. GitHub normally executes `pull_request` at a
synthetic merge commit; bind its run back to the PR's exact head and record both
commits. Docs-only edits also change the head and require fresh CI. Do not mark
qualification complete until all three checks succeed on the final head.

No self-hosted-runner, live provider, real target, fresh-Mac, release-signing or
general production-readiness claim follows from this CI qualification. Stop on
a failing gate; make only a substantiated CI-only repair or report the blocker.
