# Bounded simulator latency transport diagnosis

Status: diagnostic-only checkpoint; see the [evidence and repair proposal](../runbooks/latency-qualification-transport-diagnostics.md).
No runtime repair or reliability qualification is claimed.

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
complete success run. No production behavior, global canonicalization, live
service/provider, Docker configuration, other worker, dependency or paid-API
change. No new ADR: a changed production trust boundary requires a separate task.

Stop after three attempts without meaningful progress on a blocker, at most two
broad checks, or about 90 minutes of active work. Stop on unsafe scope, sensitive
input, required live authority or unconfirmed cleanup. No merge, rebase or
force-push. `agentcore-work/**` is excluded before checkout and remains unread,
unstaged and unpublished. Logs, caches and scratch probes stay outside tracked
source; a sanitized opt-in negative reproducer is retained with the runbook.
