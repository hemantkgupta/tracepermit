# Simulator Approval Admission Qualification

Requirements: FR-003; FR-007; FR-009; SEC-002; SEC-003; SEC-017 (explicitly not
satisfied here); REL-001; REL-004; REL-009; OPS-001; OPS-005.

This runbook documents the bounded admission qualification for the simulator
approval foundation (`SimulatorApprovalServer` in `java/adapters-http`).
Admission happens before any handler statement: at most `activeLanes`
exchanges are inside a handler at once (the dispatch pool's lane count), and
at most `pendingBound` exchanges wait in a bounded queue. A queued or rejected
exchange never resolves a verification key, parses a protected body, opens a
database connection, or reaches the target simulator; a queued exchange still
holds its connection while it waits. An offer beyond the pending bound is
rejected by the executor before any handler runs and surfaces as a closed
connection with no response; a closed or closing foundation answers
`503 SERVER_SHUTDOWN` during the race window or refuses connections. See
[ADR 0050](../architecture/0050-bounded-simulator-approval-admission.md).

---

## Guarantees under qualification

1. **Admission precedes every handler statement.** A lane only starts a
   handler after taking an exchange from the bounded queue, so a queued or
   rejected exchange runs no handler code at all — no host or URI validation,
   no key resolution, no body parsing, no database work, no target dispatch.
   The host/URI/closed checks themselves run inside an admitted lane: a
   malformed request consumes a lane or a queue slot until its handler returns
   the bounded 400 and releases the lane.
2. **Capacity is exact, per server instance.** Active work is bounded by the
   lane count (`activeLanes`); pending work by the queue (`pendingBound`);
   accepted work is therefore bounded at `activeLanes + pendingBound`
   executor tasks. That is a bound on executor tasks, not on TCP connections
   or buffered bytes: the listener keeps accepting connections, and a queued
   exchange keeps its connection open while it waits. An offer beyond the
   pending bound is rejected before any handler runs.
3. **Every failure path returns its lane.** Success, handler exception,
   malformed input, authentication failure, and database outage all release the
   lane back to the pool — proven by a fresh successful submission after each
   failure and by 25 repeated create/review cycles.
4. **Close is bounded.** `close()` stops the listener, gives in-flight lanes up
   to 2 seconds to finish, then interrupts them, and returns even if an
   admitted handler is blocked in a non-interruptible call. Close is bounded,
   not a drain or cancellation guarantee: queued work may never run, no target
   outcome is promised across close, and previously admitted work can remain
   uncertain. Lingering blocked lanes are daemon, bounded in count by
   `activeLanes`, and cannot hold JVM exit. OS or JDBC work is not claimed to
   be forcibly cancelled, and no resource owned by another service is closed.
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

| #   | Test                                                                    | Boundary                                                                                                                                                                                                                                                                                                                                                          | Role                                                     |
| --- | ----------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------- |
| 1   | `saturatedAdmissionQueuesBoundedAndRejectsOverflowWithoutExpensiveWork` | Capacity 2 with both lanes parked in key resolution: of 5 further exchanges exactly 2 queue and exactly 3 are transport-rejected, all with zero key resolutions, zero database attempts, zero simulator rules; opening the gate serves parked then queued                                                                                                         | Negative (saturation) + positive (queued/parked service) |
| 2   | `saturatedApprovedExecuteDoesNoWorkUntilCapacityFrees`                  | An already approved execute offered at full observed capacity (1 active lane + 1 queued exchange) is transport-rejected with zero key resolutions, zero database attempts, zero simulator rules; after release the execute succeeds with one distinct rule and replay returns 409 with unchanged simulator state                                                  | Negative (saturation) + positive (execute/replay)        |
| 3   | `dispatchPoolHoldsExactLaneAndQueueBounds`                              | Direct helper qualification: valid bounds accepted, out-of-range rejected, exactly `activeLanes` threads run, exactly `pendingBound` queued, next offer throws, lanes daemon and named; release and shutdown happen in a finally, so a failed assertion cannot strand parked tasks                                                                                | Positive + negative (bounds)                             |
| 4   | `everyFailurePathReturnsTheLaneToService`                               | Each of 401, 400, 403, 404, 405, 413, and a 503 database outage is followed by a fresh 201 — the lane returned to service each time; a malformed request consumes its lane until the bounded 400 returns it                                                                                                                                                       | Negative (per-path) + positive control after each        |
| 5   | `repeatedCyclesDoNotLeakAdmissionSlots`                                 | 25 create/review cycles with periodic denials leave history complete and no leaked lanes                                                                                                                                                                                                                                                                          | Positive (no-leak under repetition)                      |
| 6   | `closeIsBoundedEvenWithABlockedAdmittedHandler`                         | Close runs on a daemon task behind a finite deadline: it returns in under 10 seconds with a handler parked forever — an unbounded close fails that deadline promptly instead of hanging; the parked handler is always released in a finally and its lane unwinds afterwards (client sees connection teardown, not a fabricated status); a second close is a no-op | Negative (blocked handler) + idempotence                 |
| 7   | `closeReturnsWhileAdmittedResolverIgnoresInterruption`                  | Close returns while the admitted resolver genuinely ignores interruption until a test-owned release: interrupt delivery is proven (and swallowed), the lane stays parked and daemon until release, then unwinds within a bounded join                                                                                                                             | Negative (blocked, uninterruptible handler)              |
| 8   | `concurrentCloseAndSubmissionsStayBounded`                              | Eight submissions racing a close all complete within the bound; the closed foundation refuses new connections                                                                                                                                                                                                                                                     | Negative (shutdown race)                                 |
| 9   | `defaultCapacityPreservesNormalReviewExecuteAndReplayFlows`             | Default factory: create → review → execute → replay 409 `APPROVAL_NOT_EXECUTABLE` → history → one simulator rule                                                                                                                                                                                                                                                  | Positive (behavior unchanged under defaults)             |
| 10  | `invalidCapacityArgumentsAreRejectedBeforeAnyServerStarts`              | Capacities 0, 65, 0-pending, and 1025-pending throw before any server object exists                                                                                                                                                                                                                                                                               | Negative (construction fail-closed)                      |

All fixtures are the module's existing deterministic synthetic identity,
PostgreSQL (Testcontainers, PostgreSQL 16.4), and simulator fixtures. Parking
uses a latch keyed on a synthetic verification-key id — no sleeps, no
throughput assertions, and no dispatch claims based on response text: the
zero-activity checks combine a counting key resolver, a counting datasource,
and unchanged simulator state. Rule cardinality is not a dispatch counter:
the simulator deduplicates identical rules, so one stored rule cannot alone
prove exactly-once invocation. Invocation-count assertions in
`HumanApprovalServiceTest` are separate application-level evidence, not an
HTTP admission-path call count. Both blocked-close probes cover setup inside
their release `finally` and check that owned close tasks and released handlers
terminate within finite waits, including on assertion failure.

---

## How to run

```bash
source scripts/configure-java-environment.sh
java/gradlew -p java :adapters-http:test --tests io.tracepermit.adapters.http.SimulatorApprovalAdmissionTest --tests io.tracepermit.adapters.http.SimulatorApprovalServerTest
```

Docker must be available; the suite fails rather than skipping if the
container cannot start.

### Observed results

2026-10-17, local Docker (PostgreSQL 16.4 via Testcontainers), after the
review round (bounded close-test discipline, two promoted independent-review
probes, corrected ordering and bounds documentation):

- Focused run (command above): `BUILD SUCCESSFUL` —
  `SimulatorApprovalAdmissionTest` 10/10, `SimulatorApprovalServerTest` 31/31,
  0 skipped, 0 failures, 0 errors.
- Full battery (`pnpm check`, which runs `gradlew -p java checkAll` across all
  modules plus the console build, on the reconciled head): `BUILD SUCCESSFUL`
  — the `adapters-http` module totals 232 tests across 18 suites, 0 skipped,
  0 failures, 0 errors, including the pre-existing
  `ApprovalLatencyMeasurementsTest` qualification (concurrency up to 20 over
  the default capacity) and the full server/browser/MCP suites.
- `pnpm format:check`, `pnpm docs:links`, `pnpm docs:flows:check`, and
  `git diff --check` all clean.

### Sensitivity control (guard-removal demonstrations)

#### Test/documentation cleanup verification — 2026-10-18

- Forced focused run: all 10 admission and 31 existing server tests passed,
  with zero failures, errors or skips.
- Temporarily restoring the old unbounded `workers.close()` made both close
  probes fail with their own `TimeoutException` deadlines. The build exited
  in 31 seconds, without an external watchdog; the correct source was restored
  exactly before further checks. No broken control was committed.
- Post-restoration `pnpm check` passed, including 41 React tests and Java
  coverage gates. The affected HTTP module ran again (232 cases in 18 suites);
  aggregate Java result files contain 855 cases in 99 suites, zero failures,
  errors or skips. The Java gate took 2m 2s with 4 tasks executed and 47
  up-to-date; this is not a claim that every Java case was freshly rerun.
- Formatting, 511 local Markdown link targets, flow synchronization and diff
  hygiene passed. Runtime enforcement is unchanged; the helper's only source
  edit clarifies its Javadoc.

#### Current-main integration — 2026-10-19

The normal reconciliation with main
`77212678fc18ff3a67d4e17ae3356698f474e686` retains the merged JDBC upgrade,
endpoint I/O deadlines, and JWKS fixture-publication repair. The traceability
conflict was resolved by preserving both the admission/identity specification
sections and main's newer evidence sections. Executable Java is unchanged;
the admission helper differs from main only in Javadoc.

The focused command above was rerun with `--rerun-tasks --no-build-cache`
against real Docker PostgreSQL: 10 admission and 31 server cases passed,
with zero failures, errors, or skips. All 24 Gradle tasks executed; the build
took 56 seconds. This qualifies the retained HTTP admission/API tests on the
reconciled runtime, not a fresh execution of every Java test, a native-model
rehearsal, or an installed endpoint artifact. The earlier deliberate
guard-removal controls remain dated historical evidence; they were not
repeated or relabeled as new probes for this integration.

#### Earlier admission and shutdown controls — 2026-10-17

Recorded 2026-10-17 from disposable probe runs against the working tree
(each probe edits `SimulatorApprovalServer.java`, runs the focused test, and
restores the file from a byte-for-byte backup taken up front):

1. **Bounded dispatch pool replaced by `Executors.newFixedThreadPool(8)`** —
   the pool no longer bounds its queue. Probe result:
   `saturatedAdmissionQueuesBoundedAndRejectsOverflowWithoutExpensiveWork`
   FAILED (the queued/rejected offers were never transport-rejected, so the
   exact queue and rejection bounds did not hold). Expected and observed.
2. **Bounded close join replaced by `ExecutorService.close()`** — close waits
   forever for the parked lane. Probe result: both close tests
   (`closeIsBoundedEvenWithABlockedAdmittedHandler`,
   `closeReturnsWhileAdmittedResolverIgnoresInterruption`) FAILED promptly
   with a `TimeoutException` from their own finite 10-second deadline; the
   build failed in 33 seconds with no external alarm installed, and the
   finally blocks released the parked handlers so cleanup completed on its
   own. Expected and observed — no external job termination is needed to
   detect this regression.

After both probes the server source was restored (verified byte-for-byte) and
the focused run above was repeated green.

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
2. **Executor-task bounds, not connection or byte bounds.** The capacity is
   per server instance and counts executor tasks (lanes plus queue slots). The
   listener accepts TCP connections and buffers request bytes outside
   admission, and a queued exchange keeps its connection open — and its
   received bytes buffered — while it waits.
3. **No fairness or throughput claims.** Scheduling is not fair and no QPS or
   latency budget is introduced. A racing offer wins a queue slot, is served
   later, or is rejected.
4. **Close is bounded, not forceful and not a drain.** A handler blocked in a
   non-interruptible JDBC or OS call may linger as a daemon lane until the
   call returns; close does not cancel OS work, does not close the
   caller-owned datasource, does not guarantee that queued work ever runs, and
   promises no target outcome — previously admitted work can remain uncertain.
5. **No new operator configuration.** Launcher compositions use the validated
   defaults; there is no operator-facing capacity knob, metrics endpoint, or
   rate-limit header in this change.
6. **Simulator-only data.** All fixtures are synthetic (`example.invalid`
   issuers, simulated tenants, containerized PostgreSQL). Tests generate synthetic
   bearer tokens and ephemeral signing keys as private fixtures; they are not
   shared as evidence or committed. No production credentials or real enterprise
   identifiers are used.

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
| REL-009     | Every failure path returns its lane and rejections carry no authority (tests 1, 2, 4, 6, 7, 8, 10)                                             | Tested fail-safe outcomes for the new stages only                                                 |
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
