# Bounded simulator latency transport diagnosis

Status: diagnostic checkpoint preserved; narrowly authorized qualification-helper
repair demonstrated but insufficient for reliability. See the [evidence and repair limits](../runbooks/latency-qualification-transport-diagnostics.md).
The historical helper-only results below do not qualify reliability.

## Subsequent bounded dispatch authorization — 2026-10-24

The user explicitly approved dispatch/handoff investigation and repair while
preserving existing limits and gates, draft PR only, no merges or live actions.
The cloud writer released the clean `0ed639a9f88c569be1c0d28d55ab1f6ebb59ae5e`
head; the Mac writer fetched, verified the handoff and took sole ownership of
this branch. Other feature branches remain untouched. The remaining queue-first
wakeup gap was reproduced against the actual factory before repair. The
candidate in [ADR0074](../architecture/0074-reserved-lane-bounded-dispatch.md)
reserves a lane before pending admission, retaining default8/16 and the original
security/permit/replay/count/deadline gates. New qualification is in progress;
no hosted or production reliability claim is made. This explicit authorization
supersedes only the earlier stop-for-runtime-direction boundary, not the bounded
work loop, no-retry or no-merge constraints.

The sections below preserve the original helper-only scope and stopping point
as history. Their former runtime-repair prohibition is superseded only by the
explicit dispatch authorization above.

The held-after-Future regression failed before the repair and passed after;
one full two-CPU qualification passed after the repair. The next independent
run failed with one concurrency20 status reset and one actual HTTP admission
rejection (sampled active2/queued16). The required three independent full passes
were **not** achieved. Stop further runtime repair and do not rerun until green;
admission behavior beyond this helper remains outside the authorization.

## Narrow integration authorization after the diagnostic checkpoint

Diagnostic sources and the unrepaired counterexample are preserved at
`4901774a5c492cd3656e6006ff5c53aa34acb2b1`. Call-graph inspection found only
`SimulatorApprovalServer.awaitDispatchQuiescence` and qualification/test
callers of the package-private `ApprovalAdmissionExecutors.awaitQuiescence`.
The coordinator explicitly authorized the smallest repair in that helper,
tests and docs. This is an exception only to the earlier main-source boundary:
no `newDispatchPool`, capacity, rejection policy, handler, endpoint, authority,
permit, deadline, workload/count or success-gate changes. Preserve the original
total barrier deadline, interrupts, timeout/shutdown cleanup and the assumption
that the qualification caller owns submissions. Do not claim global quiescence
with concurrent unowned submitters. Show the same held-after-Future regression
failing before and passing after, followed by three independent unchanged full
qualification passes and proportionate gates within the original work bounds.

- Frozen base: `2883eb6dc9c40f88c231ddc6cec7df5f016b2add`.
- Exclusive cloud writer: `codex/latency-qualification-transport-diagnostics`.
- Released feature branches/PRs #100 and #101 remain untouched.
- Requirements under investigation: PERF-001/002/003; OPS-002/003/004;
  REL-001/005/009; SEC-003/005/006/012.

## Question and evidence

The unchanged full simulator qualification sometimes loses one or more requests
at concurrency20, then observes dependent approval failures and fewer than the
required 60 effects/permits/attempts. Reported runs include 58/60 and 57/60 with
IOException categories. A separate policy lock-query timeout under heavy load
is not assumed to have the same cause. Empty asynchronously observed SQL error
categories cannot exclude a database failure. A single green rerun is not proof.

## Work sequence

1. Read the latency runner/tests, fixture server/admission and HTTP client
   ownership/retirement. Inventory safe observability and cleanup boundaries.
2. Reproduce with disposable synthetic fixtures and bounded workers, optionally
   a process-local two-CPU JVM profile. Probe pooled connections, phase/idle
   boundaries, actual admission outcomes and SQL categories. Add only fixed-code
   diagnostics where causes are currently hidden; never emit messages, payloads,
   credentials, IDs, JDBC URLs or signed source material.
3. Repair fixture/transport lifecycle only after a deterministic cause is
   demonstrated. If the cause is in production code, report exact proof and stop
   for integration direction. If only diagnostics are justified, label the
   result diagnostic-only and leave reliability unresolved.
4. For a justified patch, show a focused failure before/guard-removal control,
   restore it, then obtain at least three independent focused passes of the
   unchanged complete qualification. Run at most two broad `pnpm check` attempts,
   plus format, documentation links/flows and whitespace gates. Record every
   failure and untested boundary in a dedicated runbook/evidence matrix and
   HANDOFF.md; update affected traceability without promoting whole IDs.
5. Push coherent commits and open a draft PR against main. Leave it unmerged,
   report exact base/head/commands/results and release branch ownership.

## Invariants and stopping bounds

Keep the frozen concurrency 1/4/20, every warmup/measurement count, the default real success
gate, zero-effect controls, all 60-operation deltas, failure/timeout/unknown
accounting and permit/replay/auth/admission assertions. No automatic retry,
deadline increase, assertion weakening, silent catch or mock replacement of the
complete success run. No request/runtime enforcement behavior (only the explicit
qualification-helper allowance above), global canonicalization, live
service/provider, Docker configuration, other worker, dependency or paid-API
change. No new ADR: a changed production trust boundary requires a separate task.

Stop after three attempts without meaningful progress on a blocker, at most two
broad checks, or about 90 minutes of active work. Stop on unsafe scope, sensitive
input, required live authority or unconfirmed cleanup. No merge, rebase or
force-push. `agentcore-work/**` is excluded before checkout and remains unread,
unstaged and unpublished. Logs, caches and scratch probes stay outside tracked
source; a sanitized opt-in negative reproducer is retained with the runbook.
