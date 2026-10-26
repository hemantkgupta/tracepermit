# Wave 2: durable authority and customer-sandbox composition

Planning baseline: `0bc6b618aa9eac7df3bacf7a6d4bcafe2a824c4f`.
Repository: <https://github.com/hemantkgupta/tracepermit>.
This is an ownership/checkpoint record, not passing implementation evidence.
Every PR remains unmerged until the operator explicitly decides otherwise.

## Exclusive work lanes

| Lane            | Owned work                                                                                                                                                                         | Excluded work                                                                                           |
| --------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------- |
| Mac coordinator | `codex/durable-operation-authority`: closed business-operation authority/admission commands, application service and multi-engine operation storage; ADR0077                       | Live target/provider calls, ingress, credential-provider branch, existing simulator runtime wiring      |
| Cloud 1         | Existing [PR105](https://github.com/hemantkgupta/tracepermit/pull/105): current-main reconciliation and bounded GitHub credential/adapter composition; ADR0076, optionally ADR0079 | Business-operation authority, storage, identity/ingress, CI                                             |
| Cloud 2         | `codex/agentcore-trusted-ingress`: bounded execution-disabled registration adapter and verified identity relationship contracts; ADR0078                                           | Authority/claim implementation, storage, existing identity behavior, real providers or public listeners |
| Gemini          | Independent adversarial probes, tests/docs-only qualification, exact-head reviews                                                                                                  | Runtime fixes or writes to another lane's branch                                                        |

The previous [wave-one protocol](packets/wave1-shared-protocol.md) continues to
govern security, exclusive branch ownership, bounded retries and the exact
handoff fields. Its lane assignments are historical; this board defines the
new lane allocation. Shared documentation receives scoped evidence, not another
lane's status rewrite. No session can see a different machine's local files.

The approved cloud environment is `tracepermit-java21-docker`. The generic Work
task creation helper did not select that environment: both attempted task
launches stopped before editing when `/workspace/tracepermit` was absent. This
is not cloud qualification or writer ownership. Start/restart those packets in
the selected published environment; do not silently rebuild a generic runtime
or modify environment configuration as an application task.

## Mac first checkpoint and bounded implementation order

Mac takes sole writer ownership of `codex/durable-operation-authority`, in the
isolated checkout `/Users/hemantkgupta/code-all/tracepermit-durable-authority`.
The initial HEAD/main is the frozen baseline above; the tree is clean and the
branch initially tracks main until its first push. The historical checkout,
shared producer checkouts and other PR branches are untouched. Private input
directories are excluded before checkout and are never inspected or committed.

Review the proposed transaction contract in
[PR107](https://github.com/hemantkgupta/tracepermit/pull/107) at
`ddaefdbe573d9083d3d16a80a31aca9e7e96a584` before writing runtime code.
That proposal is not an implemented schema, approved API or parity claim.
Preserve ADR0070 initial-return registration, ADR0072 pending-only withdrawal
and ADR0075's data-only target binding. Choose one transaction owner rather
than composing independently committing stores and calling the result atomic.

Implement and test closed coherent slices in this order:

1. Freeze typed commands, trusted-context provenance, exact binding, state/lock
   order, time and failure semantics; keep execution disabled until authority
   and admission are fully integrated and independently reviewed.
2. Durable exact review/authorization transitions with the existing scoped
   lifecycle head and append-only journal; no arbitrary proposed-state writer.
3. Single-winner admission bound to approval, current policy/recovery generation,
   single-use authority and attempt/evidence identity, if the preceding contract
   supports a complete closed transaction within the batch.
4. Qualify rollback, genuine lock-wait expiry, concurrency, commit/cleanup
   uncertainty and restart on PostgreSQL16.4 and MariaDB11.4.13/10.6.25.
5. Compose only a synthetic target fixture after acknowledged admission, with no
   external I/O under database locks and no dispatch after uncertain commit.

An eight-hour allocation is a productive-work ceiling, not permission to pad a
completed task or ship half-wired authority. If a larger composition is not safe
within the window, publish the coherent tested slice and name the exact remaining
gate. A snapshot, valid proposal, successful lookup or constructor flag is not
authentication, current approval, a permit or dispatch authority. Recovery checks
must not be described as instantaneous paused-worker fencing or protection from
an older database backup. Unknown target outcomes never authorize another POST.

## Verification and publication

No runtime tests have run at this initial planning checkpoint. Focused and full
gates, exact-head hosted checks and independent review remain required before a
completed implementation handoff. Preserve positive/negative controls, coverage,
all workload/concurrency limits, failure and skip facts. Use the managed/local
Docker endpoint without restarting other workers' services. No paid API fallback,
real provider/target calls, public listeners, force pushes or PR merges.
