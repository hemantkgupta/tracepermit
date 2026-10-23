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
