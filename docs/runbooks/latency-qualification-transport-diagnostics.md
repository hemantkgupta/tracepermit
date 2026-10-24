# Simulator latency transport diagnosis (diagnostic-only)

Requirements: PERF-001/002/003; OPS-002/003/004; REL-001/005/009;
SEC-003/005/006/012. This is bounded test observability and counterexample
evidence, not a runtime repair, completed requirement, production SLO or
reliability qualification. No ADR or production source changes are included.

## Frozen scope

- Repository: `hemantkgupta/tracepermit`.
- Runtime base: `2883eb6dc9c40f88c231ddc6cec7df5f016b2add`.
- Branch: `codex/latency-qualification-transport-diagnostics`.
- Initial public plan: `e8ab7a222d64aab4ee428b90e02783591e13d305`.
- Default concurrency remains **1/4/20**, warmup **5/8/20**, measured
  **20/32/40**. Every zero-effect, exact effect/permit/attempt delta (25/40/60
  per sweep), failure/timeout/unknown, replay, authentication and admission
  gate remains active. No request/action/database retry or deadline increase.

PRs #100/#101 and their checkouts are not edited by this branch. Private
`agentcore-work/**` is excluded before checkout and is not input or evidence.
Targets remain simulated. No live provider, customer or paid API is used.

## Evidence matrix and limits

| Evidence                                                          | Observed result                                                                                                                                                                   | Supported conclusion / limit                                                                                                                                                                                 |
| ----------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Reported PR #101 run `40058205938`, job `120765786252`            | Concurrency20 review: two `IOException`, then two resume denials; 58/60 deltas                                                                                                    | First transport failure must be separated from consequential failure. No admission counters were available.                                                                                                  |
| Reported PR #101 `af723ae`, run `40059822335`, job `120770700750` | Concurrency20 request warmup: one `IOException`; downstream missing-ID failures; 19/60                                                                                            | A failed warmup invalidates qualification and skips measured operations. Missing-ID failures are not independent transport failures.                                                                         |
| Reported PR #100 `c8af3f7`, run `40059677049`, job `120770247754` | Same phase: three `IOException`; downstream missing-ID failures; 17/60                                                                                                            | Same accounting distinction; shared cause remains unproved.                                                                                                                                                  |
| Separate earlier policy lock query under host load above100       | Timeout, then a passing one-worker run reported by Mac                                                                                                                            | Distinct evidence; no causal equivalence is asserted.                                                                                                                                                        |
| Local default full qualification, two-CPU test JVM                | Passed once before final diagnostic cleanup edits                                                                                                                                 | A passing sample cannot establish root cause or erase hosted failures.                                                                                                                                       |
| Controlled negative barrier/HTTP probe on frozen runtime          | `reported_quiet=true`, 20 client attempts, 16 handled successes, four failures; four exchange rejections, zero barrier rejections; sampled active8/queued16; four `HTTP_IO_RESET` | The runtime barrier can return before worker release; bounded admission refusal can cause the observed transport symptom. Artificially held worker-release window, not a reproduced complete hosted failure. |
| SQL log categories in reported failures                           | Empty asynchronous set                                                                                                                                                            | Does **not** exclude database error, missing observation, or another cause.                                                                                                                                  |

The next local unchanged default full qualification with final diagnostic
sources (two-CPU JVM) failed: concurrency20 resume warmup had one
`HTTP_IO_RESET`, actual exchange rejection1, barrier rejection0, sampled
active3/queued16 and all three deltas19/60. Nine diagnostic unit cases passed.
This is natural full-run transport/admission co-occurrence, not just the
artificial probe. Sampled active count is not a synchronized occupancy snapshot
and does not prove the barrier was its sole cause.

Final commands/results are recorded in HANDOFF and the draft PR. A successful
local run does not qualify hosted reliability. Stale pooled connection reuse
was inspected but neither demonstrated nor eliminated by these experiments.

## Counterexample: future completion versus worker release

The frozen `ApprovalAdmissionExecutors.awaitQuiescence` submits eight barrier
tasks and waits for their `Future.get()` results. `FutureTask` can publish
completion before its executor's `afterExecute` and worker release finish.
The next wave can therefore start with eight lanes still occupied. A
16-entry pending queue then refuses at least four of 20 simultaneous offers.
The JDK HTTP server closes those refused exchanges before the handler; no
authentication, SQL operation or JSON response is promised for that exchange.

The opt-in [probe source](probes/LatencyBarrierProbeTest.java) subclasses a
synthetic executor with the same eight lanes, pending16 and AbortPolicy. It
holds each completed barrier in `afterExecute`, solely to widen this ordering
window. It calls the **unchanged** runtime helper with a one-second budget,
waits for all eight held workers, then uses real numeric-loopback JDK HTTP
and the existing qualification client/concurrent runner to send 20 POSTs.
It releases the hold after four observed rejections or a two-second bound.
Each POST has a three-second budget, the synthetic batch four seconds, and
the hold five seconds. No credential, database, provider or target is involved.

The original observed counts were exactly 20/16/4 and four `HTTP_IO_RESET`.
The probe asserts at least four rejections/failures and complete terminal
accounting; a slower machine may fail the probe's finite bounds or reject
more. It never retries until a favorable result. This is a scheduling
counterexample, not a surrogate success path or a latency measurement.

### Run the opt-in negative probe

Prerequisites: trusted checkout of this diagnostic branch, Java21 and the
normal Gradle dependencies. Docker is needed for the separate complete
qualification, not this negative probe. From the repository root:

```bash
(
  set -euo pipefail
  source scripts/configure-java-environment.sh
  qualification_probe_root="$(mktemp -d)"
  trap 'rm -rf "$qualification_probe_root"' EXIT
  mkdir -p "$qualification_probe_root/io/tracepermit/adapters/http"
  cp docs/runbooks/probes/LatencyBarrierProbeTest.java \
    "$qualification_probe_root/io/tracepermit/adapters/http/"
  TRACEPERMIT_LATENCY_PROBE_ROOT="$qualification_probe_root" \
    ./java/gradlew -p java --no-configuration-cache --max-workers=1 \
    -I docs/runbooks/probes/latency-barrier.gradle :adapters-http:test \
    --tests '*LatencyBarrierProbeTest' --console=plain
)
```

The [init script](probes/latency-barrier.gradle) adds only that temporary test
source root and a process-local `-XX:ActiveProcessorCount=2` test JVM profile.
It changes neither the runtime nor the real qualification's internal client
concurrency. Gradle XML contains only the fixed `CONTROL` facts above. The
probe releases its latch, stops its HTTP server, restores the exact original
rejection handler, joins both owned executors within two seconds each and
uses the client's existing bounded retirement helper. It verifies the numeric
loopback port can be rebound and prints a fixed cleanup confirmation. The
shell removes the dedicated temporary source directory. A failed bound or
cleanup assertion is a failure, not a success or an instruction to retry.

Do not run this in place of the complete simulator success gate. Do not
archive raw test streams, environment dumps or provider material as evidence.

## Diagnostic implementation

All Java edits are on the test classpath:

- `LatencyFailureDiagnostics`: inspect at most eight distinct cause links,
  with cycle detection. Only fixed cause types / exact constant markers can
  produce `HTTP_IO_RESET`, `HTTP_IO_EOF`, `HTTP_IO_CLOSED`, `HTTP_IO_CONNECT`,
  `HTTP_IO_HEADER_EOF` or `HTTP_IO_OTHER`. Messages and unknown class names
  are never copied into the result. Locale/JDK variants fall back to OTHER.
- Missing-ID status/review/resume categories remain separate from transport
  failure. Top-level timeouts retain TIMEOUT and terminal counts are unchanged.
  Unknown status lookalikes and tool-response strings become bounded generic
  categories, without printing their contents.
- `LatencyAdmissionObservation`: reflectively obtains the fixture's actual
  executor and wraps its existing rejection handler, recording then delegating
  the **same** rejection. No queue/worker/admission setting changes. Restore
  the exact handler in cleanup. Reflection/ownership mismatch fails closed.
- Admission output has four fixed keys: `HTTP_EXCHANGE_REJECTED`,
  `QUIESCENCE_TASK_REJECTED`, `REJECT_MAX_ACTIVE`, `REJECT_MAX_QUEUED`.
  Counts saturate at65535; sampled maxima are capped at64/1024 and are not
  atomic snapshots. The future-task distinction is specific to this fixture.
- Final client retirement uses the existing five-second graceful plus
  five-second forced bounds; cleanup errors now invalidate the report with
  fixed codes instead of disappearing. No HTTP/phase budget was raised.

Admission diagnostics appear on standalone qualification failure alongside
the existing asynchronous fixed PostgreSQL categories. Neither empty map
excludes failure outside that observation boundary. Aggregate counters do
not correlate a particular refusal with a particular approval or SQL action.
The observer changes scheduling slightly and cannot certify that the same
interleaving occurred in earlier hosted runs.

## Smallest proposed repair for separate authorization

Review the quiescence contract in
`java/adapters-http/src/main/java/io/tracepermit/adapters/http/ApprovalAdmissionExecutors.java`:
future completion is insufficient proof of worker release. A repair candidate
needs bounded worker-release/idle acknowledgement under the **existing**
deadline, including the interval after the barrier's future completes. Review
the bridge in `SimulatorApprovalServer.java` and test caller
`ApprovalLatencyMeasurements.java`; add a deterministic lifecycle regression
in `ApprovalAdmissionExecutorsTest.java` and repeat the unchanged complete
success gate under a two-CPU profile. That implementation is **not** authorized
or included here.

Do not assume that waiting for idle alone guarantees an arbitrary 20-offer
burst: ThreadPoolExecutor queueing/scheduling still needs independent proof.
Eight active plus16 pending is an upper bound, not a minimum guaranteed burst
acceptance. Do not fix this by enlarging admission, retrying an effect,
lowering concurrency, raising deadlines or weakening success/count assertions.
The exact smallest correct implementation remains an integration decision.
