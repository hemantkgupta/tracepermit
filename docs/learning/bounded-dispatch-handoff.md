# Why an idle worker is not a reserved lane

The simulator listener keeps eight execution lanes and sixteen pending slots.
That is a task-count ceiling, not a bound on connections or buffered bytes.
Admission must happen before authentication, body parsing, SQL and effects.

A fixed `ThreadPoolExecutor` can offer new work to queue16 while eight already
created workers are not yet scheduled to dequeue. A twenty-offer burst can
therefore be refused despite idle workers. Waiting for earlier futures, or
sampling zero active threads, cannot reserve those workers for the next burst.
This is distinct from the earlier future-completion-before-worker-release gap.

[ADR0074](../architecture/0074-reserved-lane-bounded-dispatch.md) makes that
handoff explicit: under one monitor, assign a task to an unreserved worker
lane first; once all eight are assigned, use pending16. A lane remains reserved
until its task returns or throws, then takes the oldest pending task or becomes
free. A reserved task may still be waiting for its worker to wake; it is not
already executing and does not bypass later authorization. At most eight
handlers run and at most twenty-four tasks are outstanding.

The negative control holds worker startup and fails on the original factory's
seventeenth offer. The repaired factory must accept twenty-four, refuse the
twenty-fifth before any handler runs, then execute only those admitted tasks
once after release. Concurrent producers, shutdown, cancellation, failure and
interruption need their own controls; one successful burst is insufficient.

Refusal has no guaranteed JSON response and grants no authority. Shutdown is
not a drain, cancellation or target-outcome promise. A lost response must not
cause a side effect to be retried. The complete qualification retains its
original concurrency, deadlines, exact effect/permit/attempt counts and negative
controls; passing it is not production-scale or public-abuse qualification.

Requirements: FR-003/007/009; SEC-002/003/017; REL-001/004/009;
PERF-001/002/003; OPS-002/003/004/005, without whole-requirement promotion.
See the [recorded evidence and limits](../runbooks/latency-qualification-transport-diagnostics.md).
