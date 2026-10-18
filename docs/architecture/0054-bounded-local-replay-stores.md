# ADR 0054 — Bounded process-local replay and permit consumption stores

- Status: accepted for the in-memory replay and permit consumption stores
- Date: 2026-10-18
- Requirements: SEC-002/003/004/006/009/014; REL-001/004/009; OPS-004 (cited,
  not fully claimed — see [Limits](#limits))

## Context

The three process-local stores behind source attestation verification,
scheduled workload verification, and execution permit consumption each used an
unbounded `ConcurrentHashMap` and swept the whole map with `removeIf` on every
`consume` call. Two defects followed from that shape:

- **Unbounded cardinality.** Entries lived until some later `consume` noticed
  their expiry. Under churn, live entries and stale-but-unreclaimed entries
  grew without limit; nothing capped how much memory a misbehaving or merely
  busy caller could pin.
- **Whole-map cleanup per call.** Every consume paid an O(n) scan over all
  entries, so the cost of one caller's cleanup backlog landed on every
  subsequent caller's critical path.

Single use itself was already atomic (`putIfAbsent`-style), and must stay so.

These stores are process-local single-instance state. They are not shared
across replicas and do not survive a restart; that boundary is unchanged here.

## Decision

Each store is bounded by two validated, positive, finite numbers: an **entry
capacity** and a **per-call cleanup budget**. Defaults are 65,536 entries and
64 reclamation removals per call; the no-arg constructors keep them, so every
existing wiring is preserved without an SPI change. Overloads accept smaller
values for deterministic tests. Construction rejects non-positive values.

A `consume` call holds one monitor across the whole decision:

1. **Argument validation first.** Null keys, expiry, or `now` are rejected
   before any state is touched; a rejected input never allocates authority
   state.
2. **Already-expired submissions are refused without consuming space.** An
   offer whose expiry is at or before `now` is counted and refused; it never
   enters the map.
3. **Bounded reclamation.** At most `cleanupBudget` entries whose recorded
   expiry is at or before `now` (equality inclusive) are reclaimed from an
   expiry-ordered min-heap. There is no full-map scan anywhere in the call.
4. **Duplicate check.** A key already present is refused. The refusal neither
   refreshes the recorded expiry nor grows any metadata.
5. **Capacity check.** When the live set is at capacity, a fresh key is
   refused with `false`. **No live entry is ever evicted to accept more work**;
   there is no allow-on-error path and no hidden replacement.
6. **Insert.** The key enters the live map and one expiry-heap entry is
   created. Duplicate, capacity, and insert are atomic under the monitor, so
   concurrent claims of one key produce exactly one winner, and distinct keys
   cannot push the live set past the cap.

Reclamation runs _before_ the duplicate check. An expired entry is eligible for
removal, but may remain behind other expired entries when the cleanup budget is
exhausted. Reuse of that key is conservatively refused until its own old record
is reclaimed; only then can a new generation enter. A genuinely new key may use
space reclaimed during the call without waiting for the whole backlog to drain.

### Heap-to-map invariant

There is exactly one expiry-heap entry per live map entry. Heap entries are
created only at insert and removed only by reclamation, so the heap size
always equals the live size, a stale expiry record can never address a newer
live generation, and every entry's primary and expiry metadata stay bounded
under arbitrary duplicate/expired churn. (The single monitor makes the
cleanup-versus-insert race impossible by construction.)

### Namespaces are preserved exactly

- Source attestation: issuer + token ID, kept as a collision-safe record key.
- Workload identity: tenant + issuer + token ID, kept as a collision-safe
  record key, so distinct tenants never share replay state and delimiter
  concatenation cannot fuse two identities into one.
- Execution permits: the legacy in-memory namespace stays **global by permit
  ID**. The tenant- and action-aware interface overloads delegate to the same
  map, so a permit consumed under one tenant is refused everywhere — the
  change does not partition legacy permit IDs and thereby open cross-tenant
  reuse. Tenant isolation for permits remains the durable store's contract.

### Saturation is a refusal, not a new taxonomy

At full capacity the refusal is `false` — the same contract as a replay — and
callers surface their existing replay refusal codes
(`SOURCE_ATTESTATION_REPLAYED`, `WORKLOAD_CREDENTIAL_REPLAYED`,
`EXECUTION_PERMIT_REPLAYED`). Saturation is deliberately not separately
classified: no new API, error code, or exception type is introduced. All three
real callers already pre-reject expired credentials before the store, so the
already-expired refusal is defense in depth rather than a caller-visible
behavior change.

### Cleanup is in-call, bounded, and honest about recovery

No scheduler, background cleaner, or request-created thread exists. Cleanup
work happens inside the calling consume, bounded by the removal budget, not a
wall-clock deadline. Real credential lifetimes (up to 60 seconds for source
attestations/permits, 300 for workload credentials) determine when entries become
eligible for reclamation; they do not schedule cleanup or bound retention time.
Expired entries can remain during idle periods. A valid, unexpired offer reclaims
up to the budget; an already-expired offer returns before reclamation. An expired
duplicate still behind the budget is refused, whereas a new key can enter once
at least one slot has been reclaimed. A full set with no reclaimable entries
continues to refuse fresh claims. None of these paths evicts a live record.
This is recovery semantics for a local bound, **not** a revocation policy:
expiry cleanup is not an independently anchored revocation decision.

### Diagnostics stay package-private

Package-private counters (live entries, expiry-index entries, reclamation
removals, saturation refusals, expired-submission refusals) exist for
same-module tests. They expose no token, signature, private claim, issuer
identifier, or permit identifier, produce no log output, and are not a public
monitoring API ([SEC-003](../specs/02-system-requirements.md)).

```mermaid
flowchart TD
  A["consume(key, expiresAt, now)"] --> V{"Arguments null?"}
  V -->|"yes"| X["Reject before any state is touched"]
  V -->|"no"| B{"expiresAt after now?"}
  B -->|"no"| R1["Refuse: expired submission; no space consumed"]
  B -->|"yes"| C["Reclaim at most cleanupBudget heap heads with expiry <= now (single monitor held across the whole decision)"]
  C --> D{"Key already live?"}
  D -->|"yes"| R2["Refuse: duplicate; no refresh, no metadata growth"]
  D -->|"no"| F{"live size at capacity?"}
  F -->|"yes"| R3["Refuse fail-closed: same contract as a replay; never evict a live entry"]
  F -->|"no"| H["Insert key and one expiry-heap entry; exactly one concurrent claim wins"]
```

## Qualification

Focused suites (all deterministic; fake time, latches, and finite future
deadlines; no sleeps):

- `InMemorySourceAttestationReplayStoreTest` (12 tests) and
  `InMemoryWorkloadIdentityReplayStoreTest` (10 tests): tiny-cap fill then
  refusal with **every still-live original entry remaining rejected afterwards**
  (an LRU implementation fails this control); already-expired offers consuming
  no capacity; duplicates with later expiry neither refreshing the claim nor
  growing metadata; the expiry-equality boundary reclaiming at or before
  `now` only; budget-exact reclamation (exactly `cleanupBudget` removals per
  call, observed through the reclamation counter); 400-iteration
  duplicate/expired churn keeping primary and expiry metadata bounded with
  heap size equal to map size at every step; 32 submitted same-key claims on
  an eight-worker pool with exactly one winner; 32 distinct keys against a cap of 4 producing exactly 4
  winners; namespace separation and delimiter-collision probes; invalid
  arguments rejected before state changes; and a positive control where a
  healthy fresh request succeeds after genuine expiry reclamation. Each suite
  also pins refusal of an expired duplicate behind the cleanup budget alongside
  acceptance of a genuinely fresh key while other expired entries remain.
- `InMemoryPermitConsumptionStoreTest` (29 tests including 20 repetitions of the
  pre-existing same-key race, with deadlines/cleanup hardened): adds the same
  tiny-cap, LRU-control, no-refresh, budget-exact, churn-bounded, and
  no-overshoot coverage, plus the **global legacy namespace** qualification —
  after a permit ID is consumed, the tenant-aware and action-aware overloads
  refuse the same ID from another tenant. Its 32 submitted claims use a
  sixteen-worker pool. All race tests release the start gate in `finally`, use
  a shared ten-second Future deadline and bounded checked shutdown; daemon
  workers cannot keep the test process alive if an uninterruptible regression
  fails the shutdown assertion. The daemon setting does not excuse a leak.
- `BoundedReplayVerifierIntegrationTest` (2 tests): integrates the tiny-cap
  stores into the real `SourceAttestationVerifier` and
  `ScheduledWorkloadVerifier` — a fresh, validly signed credential is refused
  with the replay refusal code while saturated. A test callback placed directly
  after verification is not reached on refusal, and runs in the healthy control
  after genuine expiry/reclamation. This harness does not run the application
  dispatcher, HTTP/MCP route or any target adapter; its callback count is not a
  production dispatch count or distributed abuse qualification.
- `ExecutionPermitAuthorityBoundedStoreTest` (2 tests): the real
  `ExecutionPermitAuthority` refuses a freshly issued valid permit with
  `EXECUTION_PERMIT_REPLAYED` while its bounded store is saturated, preserves
  the global permit-ID namespace across overloads, and succeeds again after
  genuine expiry. The same test-only consumption-then-callback boundary applies;
  no end-to-end dispatch claim is made.

Sensitivity probes (disposable edits, byte-for-byte restored, never
committed): bypassing the capacity guard makes the tiny-cap control tests fail
(4 failures in the source-attestation suite), and replacing the capacity
refusal with an eviction of the oldest live entry makes them fail as well
(3 failures in the permit suite). Both suites are green again after restore.

## Limits

- **Process-local.** State is per JVM instance: it does not survive a restart,
  is not shared across replicas, and confers no cross-replica durability. The
  durable PostgreSQL store remains the coordinator where durability matters.
- **Per-instance bounds.** Capacity bounds one process's live set; nothing
  here bounds a fleet, a tenant, or a deployment.
- **Trusted clock.** Reclamation and expiry decisions trust the caller's
  supplied `now`. There is no clock-rollback guarantee, no recovery fencing,
  and no durable revocation.
- **Not a revocation policy.** Expiry cleanup reclaims space for entries whose
  credential lifetime has passed; it is not an independently anchored
  revocation decision and must not be treated as one. Revocation remains the
  verifiers' credential-revocation checking.
- **Saturation is not separately classified.** A saturated store is
  indistinguishable from a replay at the store's contract; operators cannot
  tell the two apart from the refusal code alone. The package-private
  counters can distinguish them in-process for tests, but no public metric is
  added ([OPS-004](../specs/02-system-requirements.md) coverage is via this
  runbook, not a monitoring surface).
- **Conservative expired-key refusal.** A duplicate whose expired record remains
  behind the cleanup budget is refused until that record is reclaimed. New keys
  can use reclaimed space before the whole backlog drains. Idle retention and
  lock wait have no cleanup-completion or fairness deadline.
- **Cited requirements, not fully claimed.** This change strengthens
  [SEC-002](../specs/02-system-requirements.md) fail-closed behavior,
  [SEC-004](../specs/02-system-requirements.md) and
  [SEC-014](../specs/02-system-requirements.md) single-use validation,
  [SEC-006](../specs/02-system-requirements.md) atomic consumption,
  [SEC-009](../specs/02-system-requirements.md) namespace separation
  (workload), [REL-001](../specs/02-system-requirements.md),
  [REL-004](../specs/02-system-requirements.md), and
  [REL-009](../specs/02-system-requirements.md) for the in-memory stores. It
  does not claim public distributed abuse protection, tenant isolation for the
  legacy global permit namespace, or any durable-storage property. See the scoped
  [traceability entry](../specs/12-traceability-matrix.md#bounded-process-local-replay-and-consumption).

See the [operator runbook](../runbooks/bounded-local-replay-stores.md).
