# ADR 0061 — Explicit requester withdrawal in the local inbox

**Status: Implemented local simulator React client; installed withdrawal qualification remains separate.**

Requirements: FR-003/007/009/010; SEC-004/005/006/009/012;
REL-002/004/005.

## Context and decision

[ADR 0060](0060-authenticated-pending-request-withdrawal.md) provides the Java
pending-only cancellation API. The optional same-origin browser inbox now exposes
that existing boundary with a separate **Withdraw request** action. It is not
reviewer denial, approved-authority revocation, emergency stop or rollback.
Java, PostgreSQL, operator enrollment, cookies/CSRF and the exact-hash API remain
authoritative; this PR changes no backend behavior, migration or MCP catalog.

The button requires a current requester session with explicit `approvals.cancel`,
ownership, pending/not-executed state and unexpired request. Reviewer/viewer
membership alone, including a combined-role non-owner, does not qualify. Login
requesting the scope does not grant it; old tokens do not silently gain it.

The confirmation shows the exact request ID/hash and the pending-only limit.
**Back** only dismisses the confirmation. **Confirm withdrawal** alone sends
one same-origin, CSRF-protected POST to the existing `/cancel` route with exactly
`requestHash`. The action is an event handler, never an effect, timer, read,
filter, refresh, selection change or mount side effect. A synchronous submission
ref rejects repeated clicks while the operation is in flight.

## Stale state and acknowledgement

Confirmation is bound to the captured authority context (tenant, principal,
human flag, roles, scopes and session CSRF binder). Immediately before POST the
handler rechecks current authority, selected ID/hash, state, role/scope and
request/session expiry. Refresh, filtering, paging, selection and logout discard
confirmation. The existing shared local clock/focus/visibility expiry guidance
also covers withdrawal. These browser checks are advisory, not trusted time or
instantaneous membership-revocation guarantees; Java rechecks independently.

The client validates a successful projection as the same ID/hash/owner with
`cancelled` and `not_executed` before showing success. It then starts an explicit
post-mutation read from page one. A pending-only filter may remove the cancelled
row; the success notice remains and the stored cancelled filter can retrieve it.
The neutral cancelled badge is distinct from approved or denied.

401/403, conflict, network failure, malformed/mismatched success and unavailable
responses clear stale local authority and confirmation. No success or optimistic
cancelled row is fabricated, and no mutation is automatically retried. A reviewer
may have won first, or cancellation may have committed despite a lost response.
An authorized read establishes stored state, not which indistinguishable caller
committed. Refresh/search cannot resume, execute or reverse anything.

## Verification and limits

`approval-withdrawal.test.ts` covers eligibility, exact-hash transport, strict
success projection and no retry. The mounted browser suite uses actual Java,
disposable PostgreSQL and the synthetic IdP for owner withdrawal, non-owner role
visibility, Back, duplicate clicks, cancelled history and review-first conflict.
A transport fault forwards a real cancellation to Java, then replaces only its
successful acknowledgement with503; the UI remains uncertain while an authorized
read finds cancelled/not-executed. Controlled client projections/clocks qualify
legacy-scope, session and request-expiry UX, not real provider grants or advanced
database time. Existing Java race/rollback tests remain independent controls.

This is source-tree, built-React qualification, not the source-free installed
withdrawal workflow or a fresh-host test. No external IdP, production cookie/TLS
profile, real target, emergency stop or background notification is claimed.
