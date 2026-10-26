# Gemini: repair the independently audited durable replay store

## Ownership and frozen evidence

The human coordinates this Gemini lane. Continue only after verifying that you
still own `codex/durable-workload-identity-replay`; do not acquire another writer's
branch or assume this Mac thread is visible. GitHub is authoritative.

- PR: [113](https://github.com/hemantkgupta/tracepermit/pull/113), draft/unmerged.
- Audited head: `72e1fca62e9d616b9da14e31bc666255cfb1105b`.
- Base: `0bc6b618aa9eac7df3bacf7a6d4bcafe2a824c4f`.
- [Four findings and exact reproduction limits](https://github.com/hemantkgupta/tracepermit/pull/113#issuecomment-6394443819).
- [Independent audit handoff](https://github.com/hemantkgupta/tracepermit/pull/113#issuecomment-6394465060).

Cloud1 acquired no writer ownership. It independently passed the existing61
cases; those passes do **not** close its four findings. Mac inspected the actual
exception/store/database source and accepts the findings for producer triage,
not as proof of an ingress or target-execution bypass. No real credentials were
exposed; failure diagnostics used synthetic canaries.

## Goal and allowed scope

Repair the four substantiated defects, add distinguishing regression/positive
controls, correct evidence claims, and leave a tested exact-head draft handoff.
Own only the existing persistence-identity implementation/tests, its explicit
schema lifecycle, ADR0080/runbook/learning/traceability and necessary additive
build wiring. Do not wire production ingress, OBO, approval/admission or targets.
Do not edit Mac108 authority/control, Cloud1 operator modules, Cloud2 HTTP work,
another producer's branch or the private `agentcore-work` subtree.

1. **Private suppressed diagnostics.** The fixed exception currently enables
   suppression/cause initialization despite its documentation. Cover the real
   DML-denial plus close-failure combination and lifecycle/read boundaries;
   prove fixed code/message, no cause, no suppressed errors and no canary in
   ordinary exception rendering. Preserve commit/cleanup uncertainty; never
   return successful consumption after failed cleanup. Do not retain arbitrary
   exception messages through the alternative constructor.
2. **Finite waits and post-wait validity.** Both claim INSERT and counter UPDATE
   have queryTimeout0. A genuine PostgreSQL counter-lock wait crossed expiry
   and still returned true. Define/enforce conservative validity using the
   caller's already-trusted `now`, elapsed monotonic time and the unchanged
   `expiresAt`; never replace trusted time with an earlier database clock.
   Recheck after every authority wait and before an acknowledged true result,
   including cleanup delays. Define safe late-commit/unknown behavior; no retry
   or authority may follow uncertainty. Apply finite DML/lock budgets and state
   explicit required connection-borrow/connect/socket/commit bounds. A generic
   arbitrary DataSource cannot magically acquire a hard end-to-end timeout;
   do not claim that it does or create leaked timeout threads. Preserve the
   public replay port and document any qualified-datasource prerequisites.
3. **Agreed capacity.** Different local capacities currently silently resize the
   same database boundary. Persist/validate one operator-owned capacity, reject
   disagreement before consumption and test reconstruction by independent
   instances. No automatic adoption, resizing, counter repair or live eviction.
   Preserve published V1 bytes: if schema changes are needed, add an explicit
   versioned empty-install/upgrade contract, validate-only startup, restricted
   runtime grants and negative partial/unknown-schema tests. No implicit upgrade.
4. **Physical-schema validation.** Intact Flyway history does not attest current
   DDL. Dropping PostgreSQL `expires_at` was accepted by both validator and store
   constructor. Validate required columns/types/nullability and actual required
   constraints/keys/collation, not just table/index names. Cover missing/renamed
   columns, incompatible shape and partial/mixed versions with real fixture DDL;
   never silently recreate, repair or adopt the schema.

Preserve the frozen valid-input hashing profile, exact UTF-8 tuple/case/whitespace
semantics, non-refreshing duplicate rejection, fingerprint-only rows and no
eviction of live claims. This store does not authenticate provider jti values,
establish OBO/current authority, or fence older backups/failover/paused senders.

## Qualification

Read AGENTS, specs and the complete independent receipts before editing. Inspect
repository path/branch/head/base/status/divergence and existing changes first.
Sparse-exclude private planning before materializing a fresh checkout. Preserve
any existing producer work; no reset/clean/rebase/force-push or merge.

Use disposable PostgreSQL16.4, MariaDB11.4.13 and MariaDB10.6.25 fixtures. Add
actual after-commit acknowledgement-loss selection; rollback after a **real**
successful INSERT followed by counter failure; independent final-slot contention
with16 stores/16 distinct tokens; conflicting-capacity rejection; real expiry
lock schedules; combined error+cleanup redaction; and physical-schema negatives.
Show the corrected checks fail against the relevant old behavior, then restore
the implementation before final runs. Preserve the existing workload, deadlines,
coverage thresholds and all61 original cases; no skips to hide unavailable Docker.

The auditor's first multi-engine probe and one diagnostic retry stopped at an
unobserved MariaDB wait-view row. That is **not** a passing wait probe and is not
proof of a MariaDB expiry failure. Design a diagnosed, genuinely distinguishing
fixture rather than retrying that identical probe a third time or weakening its
assertion. Preserve PostgreSQL versus MariaDB evidence attribution.

Run the fresh complete module cache-disabled, proportionate affected suites,
`pnpm check`, formatting, docs links/flows and diff checks. Serialize aggregate
coverage writers. At most two broad attempts without a diagnosed repair; stop
for missing trust decisions, unavailable runtime or three identical no-progress
attempts. No environment rebuild or other-worker Docker restart/prune.

Commit/push a coherent checkpoint, update the existing draft PR, review exact-head
hosted checks, and return the standard TRACEPERMIT HANDOFF with exact base/head,
all failures/skips/reuse and remaining limitations. Explicitly release only your
branch when finished. Do not claim generic production-readiness or deployed
global replay. No live provider/target/AWS/customer action, public listener/tunnel,
credentials/private values in output, paid fallback, merges or weakened gates.
