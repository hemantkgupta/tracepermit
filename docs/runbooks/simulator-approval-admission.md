# Simulator Approval Admission Qualification

Requirements: FR-003; FR-007; FR-009; SEC-002; SEC-003; SEC-017 (explicitly not
satisfied here); REL-001; REL-004; REL-009; OPS-001; OPS-005.

This runbook documents the bounded admission qualification for the simulator
approval foundation (`SimulatorApprovalServer` in `java/adapters-http`). The
foundation enforces two small capacity bounds **before** authentication, body
parsing, database work, and dispatch: at most `activeLanes` exchanges are
inside a handler at once (the dispatch pool's lane count), and at most
`pendingBound` exchanges wait in a bounded queue. An offer beyond the pending
bound is rejected by the executor before any handler runs and surfaces as a
closed connection with no response; a closed or closing foundation answers
`503 SERVER_SHUTDOWN` during the race window or refuses connections. See
[ADR 0050](../architecture/0050-bounded-simulator-approval-admission.md).

---

## Guarantees under qualification

1. **Gating precedes expensive work.** Host, URI, and closed checks are the
   only work performed before a lane takes an exchange from the bounded queue.
   A queued or rejected exchange never resolves a verification key, parses a
   protected body, opens a database connection, or reaches the target
   simulator.
2. **Capacity is exact.** Active work is bounded by the lane count
   (`activeLanes`); pending work by the queue (`pendingBound`); accepted work
   is therefore bounded at `activeLanes + pendingBound`. An offer beyond that
   is rejected before any handler runs.
3. **Every failure path returns its lane.** Success, handler exception,
   malformed input, authentication failure, and database outage all release the
   lane back to the pool — proven by a fresh successful submission after each
   failure and by 25 repeated create/review cycles.
4. **Close is bounded.** `close()` stops the listener, gives in-flight lanes up
   to 2 seconds to finish, then interrupts them, and returns even if an
   admitted handler is blocked in a non-interruptible call. Lingering blocked
   lanes are daemon, bounded in count by `activeLanes`, and cannot hold JVM
   exit. OS or JDBC work is not claimed to be forcibly cancelled, and no
   resource owned by another service is closed.
5. **Rejections carry nothing.** The transport overflow close carries no body
   at all, and `SERVER_SHUTDOWN` uses the existing bounded error shape — no
   tokens, fingerprints, or admission state; rejections authorize nothing and
   are never recorded as evidence.

Defaults are `activeLanes = 8` and `pendingBound = 16`, validated to stay
within `1..64` lanes and `1..1024` pending exchanges. The launcher
compositions use these defaults; the qualification suite starts smaller
foundations (capacity 1 and 2) through a package-private factory so saturation
is deterministic.

---

## Positive / negative matrix

| #   | Test                                                                    | Boundary                                                                                                                                                                                                                                                  | Role                                                     |
| --- | ----------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------- |
| 1   | `saturatedAdmissionQueuesBoundedAndRejectsOverflowWithoutExpensiveWork` | Capacity 2 with both lanes parked in key resolution: of 5 further exchanges exactly 2 queue and exactly 3 are transport-rejected, all with zero key resolutions, zero database attempts, zero simulator rules; opening the gate serves parked then queued | Negative (saturation) + positive (queued/parked service) |
| 2   | `dispatchPoolHoldsExactLaneAndQueueBounds`                              | Direct helper qualification: valid bounds accepted, out-of-range rejected, exactly `activeLanes` threads run, exactly `pendingBound` queued, next offer throws, lanes daemon and named                                                                    | Positive + negative (bounds)                             |
| 3   | `everyFailurePathReturnsTheLaneToService`                               | Each of 401, 400, 403, 404, 405, 413, and a 503 database outage is followed by a fresh 201 — the lane returned to service each time                                                                                                                       | Negative (per-path) + positive control after each        |
| 4   | `repeatedCyclesDoNotLeakAdmissionSlots`                                 | 25 create/review cycles with periodic denials leave history complete and no leaked lanes                                                                                                                                                                  | Positive (no-leak under repetition)                      |
| 5   | `closeIsBoundedEvenWithABlockedAdmittedHandler`                         | `close()` returns in under 10 seconds with a handler parked forever; the parked lane unwinds afterwards (client sees connection teardown, not a fabricated status); a second close is a no-op                                                             | Negative (blocked handler) + idempotence                 |
| 6   | `concurrentCloseAndSubmissionsStayBounded`                              | Eight submissions racing a close all complete within the bound; the closed foundation refuses new connections                                                                                                                                             | Negative (shutdown race)                                 |
| 7   | `defaultCapacityPreservesNormalReviewExecuteAndReplayFlows`             | Default factory: create → review → execute → replay 409 `APPROVAL_NOT_EXECUTABLE` → history → one simulator rule                                                                                                                                          | Positive (behavior unchanged under defaults)             |
| 8   | `invalidCapacityArgumentsAreRejectedBeforeAnyServerStarts`              | Capacities 0, 65, 0-pending, and 1025-pending throw before any server object exists                                                                                                                                                                       | Negative (construction fail-closed)                      |

All fixtures are the module's existing deterministic synthetic identity,
PostgreSQL (Testcontainers, PostgreSQL 16.4), and simulator fixtures. Parking
uses a latch keyed on a synthetic verification-key id — no sleeps, no
throughput assertions, and no dispatch claims based on response text: the
zero-activity proofs come from a counting key resolver, a counting datasource,
and the simulator rule counter.

---

## How to run

```bash
source scripts/configure-java-environment.sh
java/gradlew -p java :adapters-http:test --tests io.tracepermit.adapters.http.SimulatorApprovalAdmissionTest --tests io.tracepermit.adapters.http.SimulatorApprovalServerTest
```

Docker must be available; the suite fails rather than skipping if the
container cannot start.

### Observed results

2026-10-17, local Docker (PostgreSQL 16.4 via Testcontainers):

- Focused run (command above): `BUILD SUCCESSFUL` —
  `SimulatorApprovalAdmissionTest` 8/8, `SimulatorApprovalServerTest` 31/31,
  0 skipped, 0 failures, 0 errors.
- Full battery (`pnpm check`, which runs `gradlew -p java checkAll` across all
  modules plus the console build): `BUILD SUCCESSFUL` — the `adapters-http`
  module totals 227 tests, 0 skipped, 0 failures, 0 errors, including the
  pre-existing `ApprovalLatencyMeasurementsTest` qualification (concurrency up
  to 20 over the default capacity) and the full server/browser/MCP suites.
- `pnpm format:check`, `pnpm docs:links`, `pnpm docs:flows:check`, and
  `git diff --check` all clean.

### Sensitivity control (guard-removal demonstrations)

Recorded 2026-10-17 from disposable probe runs against the working tree
(each probe edits `SimulatorApprovalServer.java`, runs the focused test, and
restores the file from a byte-for-byte backup taken up front):

1. **Bounded dispatch pool replaced by `Executors.newFixedThreadPool(8)`** —
   the pool no longer bounds its queue. Probe result:
   `saturatedAdmissionQueuesBoundedAndRejectsOverflowWithoutExpensiveWork`
   FAILED (the queued/rejected offers were never transport-rejected, so the
   exact queue and rejection bounds did not hold). Expected and observed.
2. **Bounded close join replaced by `ExecutorService.close()`** — close waits
   forever for the parked lane. Probe result: the
   `closeIsBoundedEvenWithABlockedAdmittedHandler` run did not complete and
   was killed by a 150-second alarm; the Gradle daemon was stopped
   afterwards. Expected and observed.

After both probes the server source was restored and the focused run above
was repeated green.

---

## Capability limits and boundary guarantees

> [!NOTE]
> Bounded admission is local simulator resource control on a loopback
> foundation. It is not public abuse prevention and does not satisfy SEC-017:
> public remote adapters must enforce fail-closed abuse controls before
> expensive authentication and again after authenticated tenant/principal
> attribution. That second gate, shared abuse state, rate limiting, and TLS
> identity remain public-adapter scope.

1. **Transport overflow has no response contract.** An offer beyond the
   pending bound is rejected by the executor before any handler runs; the JDK
   HTTP server closes that connection without a response. Clients must not
   expect a JSON error there, and none is promised.
2. **No fairness or throughput claims.** Scheduling is not fair and no QPS or
   latency budget is introduced. A racing offer wins a queue slot, is served
   later, or is rejected.
3. **Close is bounded, not forceful.** A handler blocked in a
   non-interruptible JDBC or OS call may linger as a daemon lane until the
   call returns; close does not cancel OS work and does not close the
   caller-owned datasource.
4. **No new operator configuration.** Launcher compositions use the validated
   defaults; there is no operator-facing capacity knob, metrics endpoint, or
   rate-limit header in this change.
5. **Simulator-only data.** All fixtures are synthetic (`example.invalid`
   issuers, simulated tenants, containerized PostgreSQL). No bearer tokens,
   private keys, or real enterprise identifiers are involved.

---

## Requirement coverage and limits

The [shared traceability matrix](../specs/12-traceability-matrix.md) records
the bounded evidence; this runbook cites the affected IDs without broadening
their wording.

| Requirement | Added evidence                                                                                                                                 | Qualification boundary                                                                            |
| ----------- | ---------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------- |
| FR-003      | Authentication still precedes all protected body parsing and dispatch; admission only adds a cheaper gate in front of it                       | Simulator approval API only; public metadata/dispatch adapters unchanged                          |
| FR-007      | Approval binding, decision, execution, and replay behavior re-qualified unchanged under default capacity                                       | Behavior preservation; admission does not participate in binding                                  |
| FR-009      | Saturated or rejected exchanges cannot reach the executor's handler path; execute/replay re-qualified unchanged                                | Simulator target only; no new authority check added                                               |
| SEC-002     | Capacity exhaustion and shutdown fail closed — transport rejection or bounded 503 — and never convert a denial into an allow                   | New failure families (saturation/shutdown); existing unknown-input fail-closed coverage unchanged |
| SEC-003     | The transport close carries no body and `SERVER_SHUTDOWN` carries no tokens, fingerprints, or admission state                                  | Bounded error shape only; no new evidence or logging surface                                      |
| SEC-017     | **Not satisfied by this change** — see the note above; the pre-authentication gate here is local resource control, not public abuse prevention | Public remote adapters remain out of scope                                                        |
| REL-001     | Saturation, shutdown, and overflow each have an explicit fail-safe outcome with a tested negative and positive control                         | Loopback foundation; no public adapter semantics claimed                                          |
| REL-004     | Permit consumption and execution transitions re-qualified unchanged under concurrent admission (existing concurrency suites remain the gate)   | Admission is orthogonal; no consumption logic changed                                             |
| REL-009     | Every failure path returns its lane and rejections carry no authority (tests 1, 3, 5, 6, 8)                                                    | Tested fail-safe outcomes for the new stages only                                                 |
| OPS-001     | No new metrics, logs, or health surface; existing secret-safe behavior preserved                                                               | Observability expansion is out of scope for this change                                           |
| OPS-005     | **Not claimed** — no TLS identity, OAuth trust, sender constraint, or shared abuse state is added; production remote readiness is unaffected   | Public production readiness remains a separate qualification                                      |

---

## Unimplemented target scope

1. **Public-adapter abuse controls:** post-attribution second gating, shared
   abuse state, rate limiting, and sender constraints for public remote
   adapters are not implemented here.
2. **Operator capacity configuration and metrics:** no knob, health probe, or
   admission metrics surface is added; defaults are fixed at construction.
3. **Response contract for transport overflow:** the executor-rejection path
   deliberately keeps no JSON contract; a guaranteed overflow response would
   require transport support and is not provided.
4. **Forcible cancellation:** close does not cancel OS or JDBC work and does
   not evict blocked daemon lanes beyond interruption.
