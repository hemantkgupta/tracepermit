# Manual validation plan

The integration patch was applied locally at
`07b6cfdec578391d59f106a3381147eea2eb2e16`. At that exact commit, 23 focused
tests and the full Java check passed with one optional installed-archive skip;
the final diff check failed on whitespace in the stored patch. Formatting and
documentation gates passed. These receipts do not establish fresh results for a
later commit. Hosted CI is unrun. The retained zero-context patch requires
`git apply --unidiff-zero` if applied to a matching checkout; ADR0095 remains
subject to coordinator collision review.

The prepared Java 21 JDK and Gradle 8.14.3 distribution/cache must already be
available at the locations guarded by the runner; it calls the installed Gradle
binary directly with `--offline`, no build cache, `--rerun-tasks` and one Gradle
worker. No dependencies are downloaded. The repository stage additionally needs
prepared **real** (not symlinked) `node_modules` directories in this checkout,
Node 22+, pnpm 11.25.0, an already-running prepared Docker daemon, and the
exact locally present PostgreSQL 16.4, MariaDB 11.4.13 and Ryuk images. It does
not install or repair an environment. PR134's MariaDB 10.x retirement remains
in force. If a prerequisite is missing, stop and report it; do not download,
rebuild or substitute versions.

After the integration commit exists and its exact source/head/tree and branch
are independently reviewed, run these two stages in order. Replace the angle
bracket values with that **integration commit's** exact full SHA-1 and tree;
these are intentionally not invented before integration exists. If the
coordinator authorizes a different continuation branch, also set
`EXPECTED_BRANCH` to its exact name. Both stages refuse dirty or differently
based checkouts and private materialization. The runner creates a private
`/tmp/tracepermit-sandbox-verifier.*` directory, records commands, separate
stdout/stderr and numeric exit files, stops at the first failure and prints the
directory path. Preserve all prior evidence.

```bash
EXPECTED_HEAD=<reviewed-integrated-commit> EXPECTED_TREE=<reviewed-integrated-tree> \
  bash /workspace/tracepermit-sandbox-artifact-verifier/java/sandbox-artifact-verifier/manual-validation.sh focused
```

Expected focused selectors: `TarVerifierTest` 9, `EvidenceVerifierTest` 11,
`SandboxArtifactVerifierTest` 3: total **23**, all fresh, zero failures/errors/
skips. The runner validates exact class reports and their timestamps. It checks
archive bytes, malformed headers, traversal, links/types, duplicate paths and
resource bounds; it also checks evidence identities, strict parsing, result
states, completeness, counters and cleanup. Fixture time and disk requirements
include a 128 MiB streamed synthetic tar and a 256 MiB sparse synthetic tar.

```bash
EXPECTED_HEAD=<same-reviewed-integrated-commit> EXPECTED_TREE=<same-reviewed-integrated-tree> \
  bash /workspace/tracepermit-sandbox-artifact-verifier/java/sandbox-artifact-verifier/manual-validation.sh repository
```

This manually executes the actual `pnpm check` component sequence (console
build/typecheck/tests, Java environment check, Linux reaper controls and Gradle
`checkAll`) with one Gradle worker and fresh `--rerun-tasks` outputs, then
format, documentation links/flows and diff checks. It validates fresh XML for
all emitted Java suites, requires the 23 verifier cases, reports exact observed
counts and skips, and rejects skips other than the existing optional native
installed-archive case. It independently reads fresh aggregate XML and requires
LINE ≥94% and BRANCH ≥75%. It does not assign a guessed total count to the
repository suite. Reports of actual cache use/failures/skips must be preserved;
no test is passed by this document.

The private logs may contain test output. Do not paste them, credentials, raw
paths or synthetic tokens into public evidence. A report of `COMPLETE_AS_SUPPLIED`
from the verifier is a statement about supplied record structure, not an
independent claim that these commands ran or that deployment is approved.
