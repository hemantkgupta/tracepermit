# ADR0088: selective CI with complete Java coverage

Status: implemented; exact-head CI validation belongs to the pull request.
Requirements: REL-001/009; OPS-002. No requirement status promotion.

## Decision

Select a PR's CI plan using a local, NUL-delimited, merge-base Git diff and the
policy file at GitHub's base SHA. Include deleted paths and both sides of renames.
An unavailable base policy, malformed SHA/UTF-8, failed Git diff, empty diff,
unknown path or unsupported plan never authorizes a lighter backend gate.
Push/manual runs always select the full plan. No labels, PR prose, model call,
network classifier or user-supplied skip switch can exempt a check.

Explicit root README/CONTRIBUTING/SECURITY Markdown, `docs/**/*.md` and `site/`
are the docs allowlist. Console `src/`, `public/` and `index.html` paths mixed
only with that allowlist select the
frontend plan: console checks, packaging and real approval browser tests also
run. Every other path, including scripts, CI, Java, frozen contracts, build files,
root dependencies, deployment and new modules, selects full CI. Documentation,
launcher/script invariants, site audit/build and site browser tests always run.
The existing required `verify` gate accepts a skipped dependency only when the
known plan makes it optional. Failed detection, required skips, cancellations,
missing results and unknown statuses fail the gate. Workflow file edits remain
review-sensitive; this policy is not protection against an authorized reviewer
deliberately changing the workflow or required checks themselves.

The full Java suite runs once across six lanes: core plus common operation
tests, PostgreSQL state, standalone MariaDB consumption on both supported
versions, and operation transactions on PostgreSQL / MariaDB10.6 / MariaDB11.4.
All other/new operation test classes remain in core. New Java modules join core
automatically. Database tasks disable up-to-date and build-cache success.
The former aggregate-plus-forced repetitions are replaced by mandatory fresh
execution, not removed qualification. Matrix fail-fast is disabled so one engine
cannot suppress the others' evidence. Local `pnpm check` remains complete.

Each lane publishes only JaCoCo execution files and a manifest of their hashes,
module names, lane, source SHA and workflow-run ID. The coverage job downloads
only this run's artifacts, rejects missing/extra/stale/corrupt/symlinked evidence,
compiles the complete production class inventory and enforces the unchanged
94% line / 75% branch floors. It neither reruns tests nor reduces the denominator.
An incomplete matrix or missing module cannot turn coverage into a pass.

Use SHA-pinned open-source `gradle/actions/setup-gradle` v5 for job/commit cache
snapshots; do not combine it with setup-java caching. Same-repository PR caches
remain branch-scoped; fork PRs only restore. Configuration-cache secrets are not
persisted without an encryption key. No additional repository write permission,
credential, external cache service or production resource is introduced.

## Related cancellation repair

The private pilot diagnostic checks an already-interrupted caller before
constructing its worker. A completed Future can otherwise win the race before
`get` observes interruption, producing an indeterminate result instead of
cancellation. The regression retains the exact cancellation/interrupt assertions
and additionally proves zero provider construction. This strengthens REL-001/009
without changing deadlines, retry policy, preflight reads or target authority.
Cancellation arriving after dispatch retains the existing bounded cleanup and
uncertain-outcome limits of ADR0081.

## Limits

A selective PR pass proves its selected scope, not a fresh full qualification.
Every `main` push still runs full CI. Timing depends on caches and runner capacity;
parallel startup can increase aggregate runner minutes. Shard manifests are
trusted workflow evidence, not signed provenance or production authorization.
