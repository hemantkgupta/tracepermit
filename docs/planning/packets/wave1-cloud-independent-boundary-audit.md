# Optional cloud lane: independent foundation boundary audit

Read and obey [the shared protocol](wave1-shared-protocol.md). This is a read-only
audit, not a fourth writer or an excuse to repeat every broad suite.
Baseline: `f23dc22f056058d7977715e0878c284728d10d17` in a fresh detached checkout.
Current unmerged dependency: PR101 at
`d35e6c285440428513c8d4864b661e71d66633cd`; later Mac repairs require a separate
exact-head follow-up, not automatic qualification by this audit.

Read ADR0066/0068/0069/0070/0071/0073/0074 and source/test boundaries. Examine the
composition gaps: trusted scoped registration versus caller JSON; immutable
registration versus current state; unknown commit versus retry permission;
process-local target ledger versus durable operation identity; mutable refs;
positive lookup versus authoritative absence; permit consumption versus a full
atomic claim/evidence transaction; database time versus external membership;
old snapshot restoration versus recovery-generation authority.

Prioritize new cross-component counterexamples, not another generic security
certification. Use bounded synthetic external probes if needed, no tracked edits,
runtime patch, branch claim, commit, push, PR creation or live action. Do not read
private planning files. Run only proportionate focused tests with exact-head
records and positive controls; no duplicated broad pnpm check without a finding.

Report substantiated defects separately from deliberately unwired future gates.
Each finding needs exact file/line, reachable boundary, minimal reproduction,
expected/actual behavior and the smallest proposed repair. If none is found,
state that narrowly. Return the shared handoff; no production-readiness claim.
Finish the baseline audit rather than polling indefinitely for future PRs.
