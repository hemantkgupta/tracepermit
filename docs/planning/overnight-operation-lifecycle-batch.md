# Bounded overnight operation-lifecycle batch

Frozen starting main: `2883eb6dc9c40f88c231ddc6cec7df5f016b2add` (merged PR #99).
This is an implementation plan, not a completed pilot or production claim.

## Ownership and objective

| Lane            | Exclusive writer branch                              | Outcome                                                                                                     |
| --------------- | ---------------------------------------------------- | ----------------------------------------------------------------------------------------------------------- |
| Mac coordinator | `codex/durable-operation-lifecycle`                  | Durable lifecycle journal/current projection, atomic state comparison and pending-only requester withdrawal |
| Cloud           | `codex/github-pilot-target-preflight`                | GitHub destination/resource binding and conservative read-only outcome qualification                        |
| Gemini          | no implementation ownership; isolated detached audit | Independent baseline adversarial audit, then exact-head Mac/cloud review                                    |

Each writer starts from the frozen main in a separate checkout. No lane changes
another lane's branch, merges, rebases, force-pushes or changes live resources.
The primary shared checkout and `agentcore-work/**` are out of scope. The four
private customer files must remain unread, unstaged and unpublished.

The batch is intended for the user's next 8–10-hour unattended window, with an
earlier stop when the defined outcome is achieved. It is not permission to spend
quota to fill a time window. Local execution needs the Mac awake and connected.
No model/API billing route, overage or fallback is enabled by this plan.

## Mac acceptance boundary

1. Preserve the immutable V1 registration snapshot, scoped-key uniqueness and
   `createOrGet` original-return semantics. Never edit the frozen V1 migrations.
2. Introduce an explicit V1-to-V2 operator migration and V2-only runtime guard.
   Keep runtime validate-only; do not silently upgrade/adopt an existing store.
3. Keep current lifecycle state and appended facts separate from original
   registration. Validate immutable fields, history prefix, whole-state token,
   scope, generation, contiguous sequence and projection/fact consistency.
4. Atomic expected-state checks under a bounded database row lock choose at most
   one winner. Projection plus appended fact commit together. Reads use a
   coherent snapshot, not a mixture of two concurrent states.
5. Only a closed pending-only requester withdrawal command is exposed as a new
   mutation port in this slice. Trusted actor, tenant/workspace/owner, required
   role/scope and authentication deadline must be checked under the lock using
   store time. Constructor flags or body-supplied metadata are not authentication.
   No generic proposed `approved`, `claimed`, `succeeded` or `published` JSON
   becomes a durable command or target capability.
6. No policy/permit issuance, target dispatch, evidence publication or public
   HTTP route is wired. Cancellation is not rollback or stopping admitted work.
   Current-policy/approval/recovery composition remains an explicit later gate.
7. Distinguish stale/not-found/authority/state errors from storage errors.
   Commit/cleanup acknowledgement loss is unknown, never false success or an
   automatic retry. Do not expose SQL, payloads, identifiers or credentials.
8. Qualify PostgreSQL 16.4, MariaDB 11.4.13 and MariaDB 10.6.25 with the same
   bounded contention, restart, partial-write rollback, corruption, exact-state,
   expiry-during-lock-wait, migration and runtime-grant matrix. Tests must prove
   unchanged immutable registrations and absence of target/permit authority.

## Execution loop

- Inspect ownership/status and define a testable checkpoint before each edit.
- Implement one coherent checkpoint, run the narrow relevant tests, review the
  diff, then run the complete required gates before the final checkpoint.
- Add positive and negative controls; at least one deliberate guard-removal
  control must make selected tests fail, then restore the guard and rerun.
- At most three repair attempts without measurable progress per failure.
  Preserve the failure and stop for a decision instead of weakening a gate.
- Stop on unexpected edits/ownership, sensitive input, quota/authentication,
  unconfirmed cleanup, required live authority, migration ambiguity or a security
  contract that cannot be satisfied within the frozen scope.
- Push coherent checkpoints; open a draft PR; leave it unmerged. Record exact
  head, tests actually run, limitations and the standard TRACEPERMIT HANDOFF.
- A passing model response is advice only. It never substitutes for tests,
  code review, final-head CI or independently verified target observations.

## Parallel packets

- [Cloud target-preflight goal](packets/github-pilot-target-preflight-cloud.md)
- [Gemini independent audit goal](packets/operation-lifecycle-independent-gemini.md)

Shared specification/traceability edits may require later reconciliation after
review. They do not authorize simultaneous writes to a branch. ADR 0072 is
reserved for the Mac lifecycle slice; ADR 0073 for cloud target preflight.

## Observed checkpoints

- Mac [PR #101](https://github.com/hemantkgupta/tracepermit/pull/101): runtime
  `fc5a7e0a444b269bc97f303b1dd3d1c7c0954c33`, then fixed-diagnostic repair
  `d17fc90ef478d57a3cac8015b25375c25a0a4b4e`. The shared database suite and
  full local check passed. Fresh exact-head CI/review is a separate gate.
- Cloud [PR #100](https://github.com/hemantkgupta/tracepermit/pull/100): original
  green head `119df5cc4039a993008885232e09806f96c9d034`; review found a large-ID
  cache-binding alias, independently reproduced by cloud. Cloud owns its repair.
- Independent Mac review found suppressed JDBC-close diagnostics, reproduced
  before repair. Two other removed-deadline controls failed as expected. These
  are observed review/repair outcomes, not production certification.
- First local broad check had an unchanged contention timeout under severe
  load; unchanged single-worker rerun passed. Original-head hosted verify
  separately failed the unchanged latency harness. Neither failure was hidden
  by removing tests, raising deadlines or reducing internal concurrency.
- A later Mac reader review reproduced permissive duplicate-key/trailing-data
  acceptance in stored originals, heads, facts, actors and populated upgrade
  inputs. Runtime repair `5ec343be33903884029a2a24a297ce54e0864539` adds a strict
  package-local decoder without changing global JSON/canonical semantics or
  migrations. Ten pre-repair PostgreSQL cases failed; the final full local check
  passed (1,498 recorded Java cases, 124 React tests; one existing archive skip).
  The shared database suite now has 234 cases, 78 per engine; 11 isolated reader
  cases also pass. Hosted latency diagnosis stays separate.
- Gemini's earlier-head reports were consumed and checked read-only: 271 total
  remote cases, of which 189 are GitHub-related, and 195 database cases. They do
  not qualify the subsequent exception, numeric-binding or strict-reader repairs.
