# ADR 0074 — Reserve dispatch lanes before pending queue admission

- Status: implemented candidate; focused and three independent full passes recorded
- Date: 2026-10-24
- Requirements: FR-003/007/009; SEC-002/003/017; REL-001/004/009;
  PERF-001/002/003; OPS-002/003/004/005 (bounded evidence only)
- Supersedes: ADR 0050's queue-first executor mechanism, not its capacity,
  security, transport-overflow or bounded-close limits

## Evidence and decision

The original fixed `ThreadPoolExecutor(8, 8, queue16, AbortPolicy)` offers to
the queue once its core workers exist. An empty-worker observation therefore
does not reserve capacity for the next wave: twenty offers can fill queue16
before idle workers dequeue. Waiting for earlier futures **and** worker release
repairs a separate barrier gap, but cannot remove this queue-first condition.
Historic failures remain recorded in the
[diagnostic runbook](../runbooks/latency-qualification-transport-diagnostics.md).

`ApprovalAdmissionExecutorsTest.idleLaneReservationsMustSurviveWorkerWakeupDelay`
uses the actual factory with a package-private thread-factory seam. For the
original executor it prestarts the eight core workers, holds them before their
worker loop, and offers a fixed batch. It failed at the seventeenth offer with
queue16. This deliberately widens a worker-wakeup scheduling interval; it is
not a reconstruction of every prior hosted failure or a throughput benchmark.

`BoundedDispatchExecutor` now owns exactly eight daemon worker lanes by default.
Under one monitor, an offer reserves an unassigned lane first, including its
single handoff slot before the worker runs. Only when every lane is reserved
does it use one of the sixteen pending FIFO slots. A task returns its lane only
after `Runnable.run()` returns or throws; the oldest pending task is promoted
directly into that same lane before new submissions can take it. No task or
handler executes inside the admission monitor, on a producer, or on an extra
thread. Worker startup occurs once at construction, never through an unbounded
replacement executor.

The invariant is `0 <= reserved <= activeLanes`,
`0 <= pending <= pendingBound`, total outstanding at most their sum. A reserved
lane is **not** a claim that its thread is already inside a handler. A delayed
worker may hold one assigned task; this is not a second pending queue or a
larger execution capacity. Previously a newly created core worker could already
hold such a first task. The new implementation makes that same reservation
available after the worker becomes idle as well. Bounds remain 8/16 by default,
validated at 1..64/1..1024; there is no new operator setting.

## Fail-closed behavior and lifecycle

- No lane and no pending slot, or any offer after shutdown: throw a fixed
  `RejectedExecutionException` without running the task. The JDK HTTP server
  closes that connection; there is still no guaranteed JSON 503 or retry.
- Authentication, request-body parsing, SQL, policy, approval, signing, permit
  consumption and effects remain inside admitted handlers, unchanged. Transport
  admission grants none of those authorities.
- Task exceptions are not retried or serialized. A failed raw task aborts that
  invocation and releases the lane; a submitted future retains its own failure.
  A task's interrupt is cleared before serving unrelated work.
- Graceful executor shutdown refuses offers and drains already accepted work.
  The existing server still stops its listener, waits only two seconds, then
  calls `shutdownNow`; server close is **not** a drain or target-outcome promise.
  Forced shutdown returns pending and not-yet-started handoffs, interrupts
  workers and does not claim that interruption-ignoring handlers terminated.
- Failed worker startup closes already-started owned workers and refuses
  construction before binding a listener; it does not open a partially
  functioning server. Failed listener binding stops its already-created workers.
- The owned qualification barrier retains the original total deadline, latch
  release, cancellation, interrupt and shutdown failure rules. It now observes
  lane reservations and pending size together under the admission monitor,
  after all barrier futures complete. It still requires exclusive submission
  ownership and is not a public readiness endpoint.
- The package-private rejection observer receives only a future-task flag and
  bounded counts, not the task or request. It cannot suppress refusal; a throwing
  observer still yields only the fixed rejection. There is no public metrics or
  diagnostic surface. Test attachment/restoration verifies exact ownership.

## Qualification and limits

The worker-wakeup regression now requires all 24 reserved/pending tasks accepted
without starting any, the 25th refused, then exactly 24 invocations. Independent
concurrent producers must produce the same ceiling. Further tests cover FIFO
promotion, futures completing before lane release, cancellation, task failure,
interrupt isolation, graceful/forced shutdown, retained interruption, worker
startup/bind failure and observer failure. All owned workers and observers have
finite cleanup waits. The original real-HTTP saturation tests retain zero
verification-key resolutions, zero database calls and zero simulator rules for
overflow; approval execution and replay assertions remain unchanged.

The default complete latency gate remains concurrency 1/4/20, warmup 5/8/20 and
measured 20/32/40, with exact 25/40/60 effect/permit/attempt deltas and every
failure/timeout/unknown/negative/replay assertion. Three independent complete
passes and the broad repository gates are required; results and any failure
belong in the diagnostic runbook, not a fabricated reliability claim here.

This remains per-process, per-listener simulator task admission, not a TCP,
byte, distributed, fair-scheduling or public-abuse bound. No live target, real
provider, production scale, latency SLO, exactly-once target effect or completed
PERF-003/SEC-017 requirement is established.
