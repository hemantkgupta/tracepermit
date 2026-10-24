# Simulator latency transport diagnosis and qualification barrier

Requirements: PERF-001/002/003; OPS-002/003/004; REL-001/005/009;
SEC-003/005/006/012. This is bounded test observability, counterexample evidence
and the historical qualification-helper repair, followed by explicitly
authorized bounded dispatch repair. It does not complete a requirement or
establish a production SLO. The current mechanism is in
[ADR0074](../architecture/0074-reserved-lane-bounded-dispatch.md); it preserves
admission ceilings and security gates. Earlier sections below retain the frozen
helper-only scope and its failed results as historical evidence.

## Subsequent dispatch repair — 2026-10-24

After cloud writer release at `0ed639a`, the user explicitly approved bounded
dispatch/handoff investigation and repair on the Mac, draft PR only, no merges
or live actions. The actual factory was exercised with a package-private
thread-factory seam, eight prestarted workers held before their worker loop,
and 24 offers. The old queue-first executor refused the seventeenth offer:
queue16 fills before any worker can dequeue. Cleanup released the workers and
joined the owned pool within two seconds. This deterministic worker-wakeup
counterexample failed before repair; it does not reconstruct every earlier
hosted failure or assert an atomic interpretation of historic sampled counters.

The candidate `BoundedDispatchExecutor` explicitly assigns one task per free
lane before using pending16. Reservation includes handoff before a worker wakes.
The same probe now admits exactly24, refuses the25th, runs nothing before worker
release and then runs exactly24 tasks once. It retains eight workers, at most16
additional pending tasks and at most24 outstanding tasks, not a larger bound.
Handler/auth/body/SQL/policy/permit/target code is unchanged. Refusal precedes
handler execution and has no guaranteed response body. See ADR0074 for shutdown,
failure, cancellation and submission-ownership semantics.

The existing test-only observer now attaches to the bounded executor's fixed
rejection observer, not a `ThreadPoolExecutor` rejection policy. It receives
only a future-task flag and one atomic reserved/pending count pair. Overflow
always throws the fixed rejection even if observation fails. Exact observer
restoration is checked. Historical maxima were non-atomic samples; they are
not retroactively upgraded. The unchanged output key `REJECT_MAX_ACTIVE` now
counts reserved lanes (which may be waiting for worker wakeup), not a claim
that all those threads are already running a handler.

Focused two-CPU validation passed 31 cases: six helper, ten executor, five
diagnostic-observer and ten real HTTP admission cases, zero failures/errors/skips.
These retain the zero-key-resolution/zero-SQL/zero-rule overflow controls and
approved execution/replay assertions. No failed run is relabeled, automatically
retried or used as a successful latency sample.

A new startup-cleanup control initially failed: on the tested JDK, stopping an
unstarted bound HTTP server did not release its socket. Startup now creates
workers before binding; failed worker creation cannot allocate that socket,
and a failed bind stops the created workers. The corrected control verifies
both ordering and finite worker joins. One compilation warning about a
non-completing `finally` and one CPU-profile launch error were corrected before
these passes; no compiler/environment check or qualification gate was relaxed.
The CPU option is applied only after sourcing Java configuration, because
`JAVA_TOOL_OPTIONS` output otherwise contaminates its `javac -version` probe.

After the final startup change, three independent forced complete runs passed
all sixteen `ApprovalLatencyMeasurementsTest` cases each, with zero failures,
errors or skips. No runtime source changed between these three runs. Earlier
exploratory passes before startup cleanup are not substituted for this sequence.

| Final-runtime run | JUnit suite start (UTC, 2026-10-24) | Result                                   |
| ----------------- | ----------------------------------- | ---------------------------------------- |
| 1                 | 05:14:12.496                        | 16 passed; complete default success gate |
| 2                 | 05:14:58.402                        | 16 passed; complete default success gate |
| 3                 | 05:16:07.673                        | 16 passed; complete default success gate |

Each used Java21, one Gradle worker, Docker PostgreSQL16.4-alpine and a
process-local two-CPU JVM profile. From the repository root:

```bash
(
  set -euo pipefail
  source scripts/configure-java-environment.sh
  export JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=2'
  java/gradlew -p java :adapters-http:test \
    --tests '*ApprovalLatencyMeasurementsTest' --max-workers=1 --rerun-tasks
)
```

This command was invoked independently three times, not as a loop that ignores
failure. The sequence would stop on a failure. No client/action/SQL retry,
capacity/deadline increase or changed success/count/negative control was used.
The broad repository gate and exact-head hosted checks remain separate evidence.

The first broad `pnpm check` after dispatch repair passed: React build/typecheck
and124 tests; Java recorded1378 cases across138 suites, zero failures/errors,
one existing installed-archive/native-PTY test skipped because its archive was
absent. The changed HTTP suite freshly ran425 cases (424 passed, that one skip).
Other unchanged suites reused valid Gradle outputs:61 actionable tasks,
5 executed,16 from cache,40 up-to-date; this is not a fresh run of every Java
test. Aggregate coverage passed unchanged gates:94.34% line/77.75% branch.
The Mac used Node25.6.1, pnpm11.25.0 and Java21; the earlier Node22.23.4
receipt below belongs to the cloud helper checkpoint, not this Mac run.
Frozen install, format,1035 documentation links,13 synchronized flows and
`git diff --check` passed. No second broad attempt was needed.

Hosted exact-head CI and independent integration review are still required;
the local results do not erase prior hosted failures or qualify production.

### Independent review findings and bounded repair

The independent read-only cloud review of `33cb1ac` found two invocation-boundary
defects. A legal generic rethrow of a checked exception escaped a catch limited
to `RuntimeException | Error`: a raw task could kill its worker, strand accepted
successors and leave misleading termination accounting. The same mechanism in
the package-private observer could escape instead of the fixed refusal. This
is an internal robustness defect; no remote exploit or authority bypass was
claimed.

The Mac independently reproduced both in permanent tests **before** repair:
the task case timed out waiting for accepted successors, and the observer case
received the fixture `IOException` instead of `RejectedExecutionException`.
Owned cleanup still completed within finite waits. The smallest repair changes
only those two catches to contain `Throwable`; no logging, retry, capacity,
deadline, workload, handler, authority or outcome change is introduced. The
regressions require both successors to run once, zero remaining reservations,
truthful termination and a fixed refusal with no cause or suppressed throwable.

The Mac also applied the same fault to the worker-start callback. Its permanent
control failed before repair with an escaping checked startup exception; the
test's independent release/join still prevented a leaked test thread. The
startup containment now covers `Throwable` too, so already-started owned workers
are interrupted before the fixed startup failure is returned. This is a third
one-line containment repair, not an extra capacity or lifecycle mechanism.

The earlier31-case, three-pass and broad receipt above belongs to the pre-review
candidate and does not close these findings. Post-repair requalification is a
separate receipt; no successful old run is substituted for it.

Post-review focused qualification passed34 cases (6 helper,13 executor,
5 diagnostics,10 real HTTP admission), zero failures/errors/skips. The final
runtime then passed three new independent forced complete runs,16 cases each:

| Post-review run | JUnit suite start (UTC, 2026-10-24) | Result                                   |
| --------------- | ----------------------------------- | ---------------------------------------- |
| 1               | 05:39:01.135                        | 16 passed; complete default success gate |
| 2               | 05:39:25.190                        | 16 passed; complete default success gate |
| 3               | 05:39:48.895                        | 16 passed; complete default success gate |

The serial script used `set -e` and checked each XML receipt, stopping on any
failed command or nonzero failure/error/skip count. Each invocation forced a
fresh Gradle test JVM with the same two-CPU profile and unchanged workload.
No source changed between these runs; final executor source SHA-256 is
`dee0858f4d43764489b5a4525dc0abf4438f9044e31ec2b3135bc27967c3c494`.
The old candidate's four hosted checks also passed at `33cb1ac`, run40066110415;
that does not close the later findings or replace exact-head CI for this repair.

The second and final allowed local broad `pnpm check` passed on this repaired
runtime:124 React tests; Java1381 recorded cases across138 suites, zero failures
or errors, one unchanged archive-absent installed/native-PTY skip. The HTTP
suite freshly ran428 cases (427 passed, one skip); unchanged modules reused
prior valid outputs. Gradle61 tasks:4 executed,57 up-to-date. Coverage gates
were unchanged and passed. Formatting,1035 links,13 flows and whitespace
checks passed again. Further hosted exact-head CI and a bounded read-only
closure check of the independent findings remain separate gates.

## Frozen scope

- Repository: `hemantkgupta/tracepermit`.
- Runtime base: `2883eb6dc9c40f88c231ddc6cec7df5f016b2add`.
- Branch: `codex/latency-qualification-transport-diagnostics`.
- Initial public plan: `e8ab7a222d64aab4ee428b90e02783591e13d305`.
- Diagnostic-only checkpoint, before the helper repair:
  `4901774a5c492cd3656e6006ff5c53aa34acb2b1`.
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
The repeated frozen-helper control also confirmed executor/client retirement
and explicit numeric-loopback port release with the same counts.
The probe asserts at least four rejections/failures and complete terminal
accounting; a slower machine may fail the probe's finite bounds or reject
more. It never retries until a favorable result. This is a scheduling
counterexample, not a surrogate success path or a latency measurement.

### Run the frozen pre-repair negative probe

Prerequisites: a separate trusted detached checkout at the **diagnostic-only
checkpoint** `4901774a5c492cd3656e6006ff5c53aa34acb2b1`, Java21 and the normal
Gradle dependencies. Never reset another writer's checkout. Docker is needed
for the separate complete qualification, not this negative probe. The old
counterexample intentionally expects the old defect; it must not be run as a
success test of the repaired helper. From that repository root:

```bash
(
  set -euo pipefail
  test "$(git rev-parse HEAD)" = 4901774a5c492cd3656e6006ff5c53aa34acb2b1
  source scripts/configure-java-environment.sh
  qualification_probe_root="$(mktemp -d)"
  trap 'rm -rf "$qualification_probe_root"' EXIT
  mkdir -p "$qualification_probe_root/io/tracepermit/adapters/http"
  cp docs/runbooks/probes/LatencyBarrierProbeTest.java \
    "$qualification_probe_root/io/tracepermit/adapters/http/"
  TRACEPERMIT_LATENCY_PROBE_ROOT="$qualification_probe_root" \
    ./java/gradlew -p java --no-configuration-cache --max-workers=1 \
    -I "$PWD/docs/runbooks/probes/latency-barrier.gradle" :adapters-http:test \
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

All diagnostic Java edits are on the test classpath. The only main-source
exception is the explicitly authorized qualification helper described below:

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

## Narrowly authorized helper repair

After preserving the diagnostic-only checkpoint, explicit integration
authorization permitted the smallest repair to the quiescence contract in
`java/adapters-http/src/main/java/io/tracepermit/adapters/http/ApprovalAdmissionExecutors.java`:
future completion is insufficient proof of worker release. After all futures
complete, the helper now observes zero active workers and an empty queue,
with bounded one-millisecond sleeps under the **original total deadline**.
Shutdown, unsupported executor observation, timeout and interruption return
false; interruption remains set. Existing barrier cancellation/release cleanup
is retained. Neither `newDispatchPool` nor admission capacity/policy changes.

Call-graph inspection found only the package-private bridge
`SimulatorApprovalServer.awaitDispatchQuiescence` and qualification/test
callers. No request route invokes this helper. The qualification caller must
own submissions throughout this boundary; these sampled observations are not
a global quiescence protocol with concurrent unowned submitters.

`ApprovalAdmissionExecutorsTest.completedFuturesMustNotReportQuiescenceBeforeTheirWorkersLeave`
uses the same eight held `afterExecute` lanes. It failed before the repair
because the future had already returned, then passed with the repair: the
observer remains blocked until the held workers are released. Additional
tests cover the original total budget, retained interrupt and shutdown refusal.
All waits and owned executor joins are finite. The historical HTTP probe is
kept opt-in outside the default test source root; the new regression belongs
to the real default suite.

### Repair qualification outcome: incomplete

The first post-repair focused run passed 30 cases, including the held-lane,
budget/interrupt/shutdown cases, diagnostic tests and all 16 latency tests
(with the unchanged full real default success run). The next independent
two-CPU full run failed in measured concurrency20 `mcp_status`: one
`HTTP_IO_RESET`, one exchange rejection, zero barrier rejections, sampled
active2/queued16, empty asynchronous SQL set. Fourteen other focused cases
passed. The three-pass sequence stopped on that first failure; later passes
were not launched. No outcome was retried or relabeled successful.

Thus the helper repair is correct for the demonstrated held-worker interval
but **insufficient** to qualify reliability. The queue's 16 pending slots do
not guarantee acceptance of a 20-offer burst, even after observing idle workers:
at least four workers must dequeue promptly. Sampled active maxima at rejection
are not synchronized occupancy proofs. No queue, dispatch policy or admission
repair is included or authorized by the helper-only decision. Further runtime
work needs separate integration direction, not another green rerun.

The repeated pre-repair HTTP control used the exact frozen helper temporarily,
with trap-based restoration and byte comparison to the repaired helper. It
reported 20 attempts, 16 handled, four reset failures/rejections and explicit
client/executor/loopback-port cleanup. One initial probe launch failed before
execution because Gradle resolves a relative `-I` beneath `-p java`; the
documented command now uses an absolute init-script path. No gate was changed.

Do not assume that waiting for idle alone guarantees an arbitrary 20-offer
burst: ThreadPoolExecutor queueing/scheduling still needs independent proof.
Eight active plus16 pending is an upper bound, not a minimum guaranteed burst
acceptance. Do not fix this by enlarging admission, retrying an effect,
lowering concurrency, raising deadlines or weakening success/count assertions.
The patch addresses the demonstrated worker-release gap only. The natural
pre-repair failure proves admission/reset co-occurrence, not that this gap
explains all hosted failures. Independent full qualification passes and the
unchanged broad gate are required; neither a locally green run nor this repair
certifies production readiness.

## Recorded validation commands and outcomes

Commands ran with Java21, Node22.23.4, pnpm11.25.0 and one Gradle worker
(`-Dorg.gradle.workers.max=1`). The focused profile additionally applied this
external temporary init script; it did not change default client concurrency:

```groovy
allprojects {
  tasks.withType(Test).configureEach { jvmArgs '-XX:ActiveProcessorCount=2' }
}
```

`$qualification_cpu_init` below denotes the absolute path to that script.
The public frozen-probe recipe above separately records its exact command.

| Command                                                                                                                                                                                                                                                                              | Outcome                                                                                                                                                                                                                                                                                                                                 |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `corepack enable && pnpm install --frozen-lockfile`                                                                                                                                                                                                                                  | PASS, frozen installation.                                                                                                                                                                                                                                                                                                              |
| `./java/gradlew -p java :adapters-http:test --tests '*ApprovalAdmissionExecutorsTest.completedFuturesMustNotReportQuiescenceBeforeTheirWorkersLeave' --console=plain` before repair                                                                                                  | Expected FAIL: future returned while all eight lanes were still held.                                                                                                                                                                                                                                                                   |
| `./java/gradlew -p java -I "$qualification_cpu_init" :adapters-http:test --tests '*ApprovalAdmissionExecutorsTest' --tests '*LatencyTransportDiagnosticsTest' --tests '*LatencyFailureDiagnosticsTest' --tests '*ApprovalLatencyMeasurementsTest' --console=plain` after repair      | PASS, 30 cases including unchanged complete default success.                                                                                                                                                                                                                                                                            |
| Same profile, `:adapters-http:test --rerun-tasks --tests '*ApprovalAdmissionExecutorsTest' --tests '*LatencyTransportDiagnosticsTest' --tests '*LatencyFailureDiagnosticsTest' --tests '*ApprovalLatencyMeasurementsTest.mainRunnerReportsFullQualificationSuccess' --console=plain` | FAIL, 14/15 passed; status reset/rejection described above. Planned second/third repeats were not run.                                                                                                                                                                                                                                  |
| `pnpm check`                                                                                                                                                                                                                                                                         | FAIL, one broad attempt only. Build/typecheck and 124 React cases passed; HTTP414 =412 passed, one latency failure, one existing absent-archive skip. Resume warmup had two `HTTP_IO_RESET`, two exchange rejections, sampled active2/queued16, deltas18/60. Later Java module/aggregate coverage gates did not run after HTTP failure. |
| `pnpm docs:links`                                                                                                                                                                                                                                                                    | PASS, 1023 local Markdown links and link-target tests.                                                                                                                                                                                                                                                                                  |
| `pnpm docs:flows:check`                                                                                                                                                                                                                                                              | PASS, 13 tests and synchronized generated guides.                                                                                                                                                                                                                                                                                       |

After the broad run, the local Docker listing reported zero running
Testcontainers-labelled containers. The controlled probe independently
asserted joined executors, retired client and released loopback port. These
are observed local cleanup facts, not a universal host/process guarantee.
Temporary probe source/init/backup artifacts are removed after use; bounded
local logs/results are not published. Formatting and whitespace outcomes are
recorded in HANDOFF and the draft PR. No production reliability claim follows.
