# Bounded Local Replay and Permit Consumption Stores

Requirements: SEC-002; SEC-003; SEC-004; SEC-006; SEC-009; SEC-014; REL-001;
REL-004; REL-009; OPS-004.

This runbook documents the bounded process-local store qualification for the
three in-memory stores behind enforcement:
`InMemorySourceAttestationReplayStore` and
`InMemoryWorkloadIdentityReplayStore` (java/identity) and
`InMemoryPermitConsumptionStore` (java/security). Each store now has an
explicit finite entry capacity and a bounded per-call cleanup budget; at full
live capacity a fresh consume fails closed (`false`) and no live entry is ever
evicted to accept more work; reclamation removes at most `cleanupBudget`
expired entries per call instead of sweeping the whole map. Single use stays
atomic: concurrent claims of one key produce exactly one winner. See
[ADR 0054](../architecture/0054-bounded-local-replay-stores.md).

---

## Guarantees under qualification

1. **Positive finite bounds, validated at construction.** Defaults are
   65,536 entries and 64 reclamation removals per call via the no-arg
   constructors (all production wirings unchanged); non-positive capacities
   or budgets are rejected before any state exists.
2. **Saturation fails closed, never evicts.** When the live set is at
   capacity, a fresh key is refused with `false` — the same refusal contract
   as a replay. Every still-live original entry stays rejected afterwards
   (an LRU implementation fails this control). Saturation is not separately
   classified: callers surface their existing replay refusal codes.
3. **Atomic single use under contention.** Duplicate check, capacity check,
   and insert happen under one monitor: 32 submitted same-key claims produce
   exactly one winner, and 32 distinct keys against a cap of 4 produce
   exactly 4 winners. Identity suites use eight workers; permit suites use
   sixteen. These are task counts, not 32 simultaneously executing threads.
4. **Duplicates do not refresh.** A duplicate with a later requested expiry
   neither extends the recorded expiry nor grows any metadata.
5. **Bounded, budget-exact cleanup.** Each consume reclaims at most
   `cleanupBudget` entries with expiry at or before `now` (equality
   inclusive) from an expiry-ordered heap — never a full-map scan. The heap
   size always equals the live size (one heap entry per live entry), so
   repeated duplicate/expired churn keeps both primary and expiry metadata
   bounded. Reclamation runs before the duplicate check. An expired duplicate
   still behind the removal budget is refused until its old record is actually
   reclaimed. New keys can use reclaimed space before the entire backlog drains.
6. **Already-expired input consumes no capacity.** Offers with expiry at or
   before `now` are counted and refused without changing authority entries. (Real callers
   already pre-reject expired credentials before the store.)
7. **Namespaces preserved.** Source replay keys are issuer + token ID;
   workload replay keys are tenant + issuer + token ID (collision-safe
   record keys, not delimiter concatenation); the legacy permit namespace
   stays global by permit ID — the tenant- and action-aware overloads refuse
   a permit ID consumed under any tenant, so cross-tenant reuse stays closed.
8. **In-call cleanup only.** No scheduler, background cleaner, or
   request-created thread exists. Recovery from a cleanup backlog is spread
   across valid unexpired offers (at most `cleanupBudget` removals each).
   Credential lifetimes determine cleanup eligibility, not retention time:
   expired entries can remain during idle periods, and expired offers return
   before reclamation. No cleanup-completion deadline or live eviction is added.

Defaults are conservative for a single-process simulator; the qualification
suites exercise tiny capacities (1–8) and budgets (1–4) for determinism.

---

## Positive / negative matrix

| #   | Test                                                                                                                                                      | Boundary                                                                                                                                                                | Role                                                        |
| --- | --------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------- |
| 1   | `tinyCapFillsThenRefusesAndEveryLiveOriginalStaysRejected` (all three store suites)                                                                       | Tiny cap fills, next fresh consume refused with `saturatedRefusals` counted, and every still-live original entry remains rejected — no LRU eviction                     | Negative (saturation) + positive (no-eviction control)      |
| 2   | `alreadyExpiredSubmissionIsRefusedWithoutConsumingCapacity`                                                                                               | Expired offers counted and refused; live and expiry index stay empty; a live insert still succeeds                                                                      | Negative (expired) + positive control                       |
| 3   | `duplicateWithLaterExpiryDoesNotRefreshOriginalClaim` / `duplicateWithLaterExpiryAlreadyExpiredOfferAndEqualityBoundary`                                  | Duplicate refusal never refreshes the recorded expiry or grows metadata; original expiry stays authoritative; T+29 live, T+30 (equality) reclaimable and re-claimable   | Negative (refresh) + boundary                               |
| 4   | `boundedReclamationRecoversWithBoundedPerCallWork` (all three suites)                                                                                     | After a full store expires, each consume reclaims exactly the budget (reclamation counter deltas of exactly 2), capacity returns over successive calls, heap equals map | Positive (budget-exact recovery)                            |
| 5   | `repeatedChurnKeepsPrimaryAndExpiryMetadataBounded`                                                                                                       | 400 duplicate/expired churn iterations: live entries within cap and heap size equal to live size at every step; expired-submission counter exact                        | Positive (bounded under churn)                              |
| 6   | `sameKeyConcurrencyProducesExactlyOneWinner`                                                                                                              | 32 submitted claims on eight identity workers (sixteen permit workers), exactly one winner                                                                              | Negative (double-consume)                                   |
| 7   | `distinctKeyConcurrencyNeverOvershootsTheCap`                                                                                                             | 32 distinct keys against cap 4: exactly 4 winners, no overshoot                                                                                                         | Negative (overshoot)                                        |
| 8   | `namespaceIsCollisionSafeIssuerPlusToken` / `tenantNamespaceSeparatesIdenticalIssuerAndToken` / `compoundKeyCannotCollideThroughDelimiterConcatenation`   | Issuer/tenant/issuer+token separation; NUL-concatenation probes cannot fuse distinct identities                                                                         | Negative (collision)                                        |
| 9   | `tenantOverloadsKeepOneGlobalPermitIdNamespace` (permit suite)                                                                                            | After a global consume of a permit ID, the tenant-aware 4-arg and 5-arg overloads (another tenant, with/without action hash) all refuse the same ID                     | Negative (cross-tenant reuse)                               |
| 10  | `invalidArgumentsAreRejectedBeforeAnyStateChanges` / `invalidArgumentsAndCapacitiesAreRejectedBeforeAnyStateChanges`                                      | Null keys/expiry/now and non-positive capacities/budgets rejected; no state allocated                                                                                   | Negative (construction/validation)                          |
| 11  | `expiryEqualityBoundaryReclaimsAtOrBeforeNowOnly` / `healthyFreshRequestSucceedsAfterGenuineExpiryReclamation`                                            | Equality boundary semantics; positive control after genuine expiry                                                                                                      | Positive controls                                           |
| 12  | `saturatedSourceAttestationStoreRefusesFreshValidAttestationBeforeTestCallback` and `saturatedWorkloadStoreRefusesFreshValidCredentialBeforeTestCallback` | Real verifiers refuse valid signed inputs under saturation; the immediately following test callback is not reached; healthy post-expiry control reaches it              | Negative (verification/callback harness) + positive control |
| 13  | `saturatedStoreRefusesFreshValidPermitBeforeTestCallback` and `globalPermitNamespaceClosesCrossTenantOverloadReuse`                                       | Real permit authority refuses saturated/consumed claims; the test-only callback follows successful consumption in the healthy control                                   | Negative (consumption/callback harness) + positive control  |
| 14  | `expiredDuplicateBehindCleanupBudgetWaitsButFreshKeyCanEnter` (all three stores)                                                                          | Expired duplicate is refused while its old record is behind the removal budget; fresh key can use reclaimed space; renewed generation remains single-use                | Conservative refusal + positive fresh control               |

The integration harness invokes the real verifier/authority followed by a
test-only callback. The counter is attached to that path, but the harness does
not execute the application dispatcher, HTTP/MCP route or any target adapter.
Neither this callback count nor store cardinality is a production dispatch
measurement, and no distributed abuse protection is qualified here.

---

## How to run

```bash
java/gradlew -p java :identity:test --tests 'io.tracepermit.identity.InMemorySourceAttestationReplayStoreTest' --tests 'io.tracepermit.identity.InMemoryWorkloadIdentityReplayStoreTest' --tests 'io.tracepermit.identity.BoundedReplayVerifierIntegrationTest'
java/gradlew -p java :security:test --tests 'io.tracepermit.security.InMemoryPermitConsumptionStoreTest' --tests 'io.tracepermit.security.ExecutionPermitAuthorityBoundedStoreTest'
```

The pre-existing verifier suites (`SourceAttestationVerifierTest`,
`ScheduledWorkloadVerifierTest`, `ExecutionPermitAuthorityTest`) are the
regression gate that the defaults preserve all existing behavior; they run in
the modules' full test batteries (Docker required for those batteries; the
store suites themselves need no Docker).

### Observed results

2026-10-18, integrator-focused runs after the tests/docs follow-up:

- `InMemorySourceAttestationReplayStoreTest` 12/12, 0 skipped, 0 failures.
- `InMemoryWorkloadIdentityReplayStoreTest` 10/10, 0 skipped, 0 failures.
- `InMemoryPermitConsumptionStoreTest` 29/29 (including the 20 pre-existing
  repeated same-key concurrency instances), 0 skipped, 0 failures.
- `BoundedReplayVerifierIntegrationTest` 2/2;
  `ExecutionPermitAuthorityBoundedStoreTest` 2/2, 0 skipped, 0 failures.
- Direct cap/cleanup measurements asserted inside the suites: exact
  `saturatedRefusals` counts at tiny caps; reclamation counter deltas of
  exactly the budget per call; live-entry and expiry-index bounds held at
  every one of 400 churn iterations; exact winner counts (1 of 32 same-key,
  4 of 32 distinct-key at cap 4).
- Producer full battery at `51a97eb` (`pnpm check`): BUILD SUCCESSFUL in 2m 35s,
  51 tasks (45 executed) — the Docker-dependent persistence and HTTP adapter
  suites ran rather than silently skipping — followed by
  `pnpm format:check` clean.

Each store has a permanent control where an expired duplicate remains behind
the one-removal budget and is refused, while a genuinely new key enters using
reclaimed space. The old key can enter only after its own record is removed.
Race tests now bound start-gate waits, share a ten-second Future deadline,
release the gate in `finally`, interrupt owned workers and assert termination
within three seconds. Workers are daemon threads so an uninterruptible regression
cannot keep the process alive after the failed cleanup assertion; a leak still
fails the test. There is no unbounded `ExecutorService.close()` or `Future.get()`.

The integrator separately ran a deterministic reference-model oracle (120,000
decisions across the three stores and budgets 1/3), 30 expiry/reinsert races
with 64 released virtual-thread contenders, and each no-arg default at 65,536
entries with exactly 64 reclamation removals. These were temporary independent
probes, not added permanent suites or production target dispatch measurements.

### Sensitivity control (guard-removal demonstrations)

Recorded 2026-10-18 from disposable probe runs against the working tree (each
probe edits one store source, runs the focused suite, and restores the file
from a byte-for-byte backup taken up front; broken variants are never
committed):

1. **Capacity guard bypassed** (`InMemorySourceAttestationReplayStore`:
   `if (live.size() >= capacity)` → `if (false)`): the suite fails —
   `tinyCapFillsThenRefusesAndEveryLiveOriginalStaysRejected` FAILED, 4 tests
   failed in total (the store accepted work past its cap, so the refusal and
   no-eviction controls did not hold). Expected and observed.
2. **Live entry evicted** (`InMemoryPermitConsumptionStore`: the capacity
   refusal replaced by removing the oldest heap entry to make room): the
   suite fails — `tinyCapFillsThenRefusesAndEveryLiveOriginalStaysRejected`
   FAILED, 3 tests failed in total (an evicted original was accepted again,
   so the no-eviction control caught it). Expected and observed.

After both probes the sources were restored (verified byte-for-byte) and both
focused suites were repeated green.

---

## Capability limits and boundary guarantees

> [!NOTE]
> These stores are process-local, single-instance control state. They are not
> shared across replicas, do not survive a restart, and do not provide
> distributed abuse protection. Expiry cleanup is **not** an independently
> anchored revocation policy: revocation remains the verifiers'
> credential-revocation checking.

1. **Restart loss and replica independence.** All replay and consumption
   state is lost on restart; two replicas keep independent state. Where
   durability or fleet-wide single use matters, the durable PostgreSQL store
   remains the coordinator.
2. **Trusted clock, no rollback guarantee.** Expiry and reclamation trust the
   supplied `now`; there is no clock-rollback guarantee, recovery fencing, or
   durable revocation.
3. **Saturation is not separately observable from outside.** A saturated
   store answers exactly as a replay does; no new error taxonomy, API, or
   public metric is introduced. The package-private counters exist for
   same-module tests only and expose no identifiers.
4. **No fairness or latency guarantees.** Recovery from a cleanup backlog
   takes successive valid unexpired offers (at most the budget per call).
   Reused expired keys can remain conservatively refused behind that budget;
   fresh keys may use already reclaimed space. No retention deadline, wait-time,
   QPS, or fairness claim is made.
5. **No transport or logging surface.** The stores add no HTTP behavior, no
   metrics, and no log output of tokens, signatures, private claims, or
   identifiers.
6. **Simulator-only data.** All fixtures are synthetic issuers, tenants, and
   simulated targets. No bearer tokens, private keys, or real enterprise
   identifiers are involved.

---

## Requirement coverage and limits

The [scoped traceability entry](../specs/12-traceability-matrix.md#bounded-process-local-replay-and-consumption)
integrates this evidence without promoting broad requirement statuses.

| Requirement | Added evidence                                                                                                                                                    | Qualification boundary                                                  |
| ----------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------- |
| SEC-002     | Saturation and expired input fail closed; refusals never become allows; invalid arguments never allocate state                                                    | In-memory stores only; no new input-validation surface                  |
| SEC-003     | Diagnostics are package-private counters with no identifiers; no token/signature/private-claim logging and no HTTP metrics added                                  | No new observability surface                                            |
| SEC-004     | Token and permit single-use stores keep atomic consumption under their new bounded maintenance                                                                    | Process-local stores; durable coordinator unchanged                     |
| SEC-006     | Single-use consumption stays atomic (one winner under 32-way races) while capacity and cleanup are bounded                                                        | Effect dispatch itself unchanged                                        |
| SEC-009     | Workload replay keys carry tenant explicitly (collision-safe record keys); legacy permit namespace stays global by contract — cross-tenant overload reuse refused | Tenant isolation for permits remains the durable store's contract       |
| SEC-014     | Source attestation single-use replay state bounded and preserved; every live original stays rejected at saturation                                                | Attestation verification logic unchanged                                |
| REL-001     | Saturation, expiry, and duplicate paths each have explicit fail-safe outcomes with negative tests and positive controls                                           | New bounds only; no recovery semantics beyond bounded reclamation       |
| REL-004     | Concurrency correctness re-qualified (exactly-one-winner, no overshoot) under the new synchronized shape                                                          | Same-key and distinct-key races only; no distributed contention claimed |
| REL-009     | Every new refusal path is tested (saturation, expired submission, duplicate refresh, overload reuse, invalid construction)                                        | Tested fail-safe outcomes for the new behaviors only                    |
| OPS-004     | This runbook plus ADR 0054 document behavior, recovery semantics, and limits for the bounded local stores                                                         | No monitoring endpoint or alert surface is added                        |

---

## Unimplemented target scope

1. **Distributed single use and cross-replica durability:** not provided by
   these stores; the PostgreSQL-backed store remains that contract.
2. **Public monitoring of saturation or backlog:** no metrics surface; the
   package-private counters are test diagnostics only.
3. **Clock-rollback tolerance, recovery fencing, and durable revocation:**
   explicitly out of scope; expiry cleanup must not be operated as a
   revocation control.
4. **Separate saturation error taxonomy:** deliberately not introduced;
   saturation shares the replay refusal contract.
