# Withdraw a pending simulator approval

This is owner withdrawal of an unreviewed request, **not an emergency stop**.
Only a live `pending` request may become terminal `cancelled`. No real AWS
resource is changed. The Java API and optional local React inbox support this
flow; source-free installed withdrawal qualification remains separate work.

Requirements: FR-003/007/009/010; SEC-001/002/003/005/006/009/012;
REL-001/002/004/005; DATA-001. See [ADR 0060](../architecture/0060-authenticated-pending-request-withdrawal.md).

## Prerequisites

Use the [authenticated approval backend](authenticated-approval-api-foundation.md)
bound to numeric IPv4 loopback, with V10 state. Existing V9 installations must
follow the [explicit stopped-service upgrade](simulator-approval-distribution.md#explicit-stopped-service-upgrade-v9-to-v10);
ordinary run does not migrate, and reinitializing/deleting a populated database
is not an upgrade. Keep credentials private and out of recordings and Git.

The identity provider must explicitly grant the owner `approvals.cancel` and
the operator must enroll that issuer-qualified identity/client/tenant with
`REQUESTER` membership. A scope or token-supplied role alone grants nothing.
The same rule applies to an enrolled workload withdrawing its own proposal;
the operation does not assert human presence. Existing tokens without the new
scope are not silently upgraded. Reviewer/viewer membership alone cannot cancel.

## Exact HTTP operation

From an authorized read, retain the actual opaque request ID and exact request
hash. Send one explicit authenticated POST to:

```text
POST /api/v1/approvals/{approvalRequestId}/cancel
Authorization: Bearer <verified-owner-access-token>
Content-Type: application/json

{"requestHash":"<exact hash from the authorized read>"}
```

The placeholders above are not runnable credentials or hashes. Do not include
replacement arguments, identity, tenant, role, authentication flags, timestamps,
free-text reason or an approval decision. Extra/duplicate fields, trailing JSON,
unsupported media types and oversized bodies retain the strict API checks.

An already-authenticated browser client uses
`/browser/api/v1/approvals/{approvalRequestId}/cancel` with the existing local
session cookie, exact same-origin `Origin` and session `X-CSRF-Token`; mixing a
bearer header with the browser session is forbidden. Do not bypass those checks.
Browser login requests the new scope, but only the provider can grant it.
The inbox exposes **Withdraw request** only for an eligible owner. **Back** in
the confirmation is not durable cancellation. No new MCP tool is exposed.

## Browser walkthrough

For the built-source synthetic profile, run `pnpm demo:approvals:browser` and open
its printed `/approvals` URL. No personal login is required. Sign in as Alice,
submit a fresh fixed simulated change and select its pending row.

1. Click **Withdraw request**. Inspect the exact ID/hash and pending-only warning.
2. Click **Back** to dismiss without a mutation. The request remains pending.
3. Open it again and click **Confirm withdrawal** once. A confirmed response
   produces the withdrawal notice and a fresh server read: `cancelled`,
   `not_executed`. The row cannot be reviewed or executed.
4. Select **Cancelled (withdrawn)** in Status and apply the filter. If a pending
   filter was active, cancellation correctly removes that row from its results.
5. Bob/Carol have no withdrawal control for Alice's request. Dave's reviewer
   membership also does not permit withdrawing someone else's request.

Missing `approvals.cancel`, expiry, changed selection/authority or a new search
invalidates confirmation. A click between timer ticks rechecks expiry. Timers,
focus, refresh and reads never submit cancellation. Java remains authoritative
if a reviewer decides first or authentication expires during a database wait.
See [ADR 0061](../architecture/0061-requester-withdrawal-inbox.md).

## Interpret the result

| Result                                                  | Meaning                                                                                       |
| ------------------------------------------------------- | --------------------------------------------------------------------------------------------- |
| 200, `status=cancelled`, `executionStatus=not_executed` | Cancellation transaction committed; the original request hash is retained                     |
| 401                                                     | Missing, invalid or expired authentication, including expiry during the store's row-lock wait |
| 403                                                     | Missing requester role/scope/enrollment, or elevated visible cross-owner access               |
| 404                                                     | Absent/cross-tenant request or another owner's request hidden from requester-only access      |
| 409 `APPROVAL_BINDING_MISMATCH`                         | Supplied valid-format hash differs from the exact stored request                              |
| 409 `APPROVAL_NOT_CANCELLABLE`                          | State is already approved, denied, expired, consumed or cancelled                             |
| 409 `APPROVAL_EXPIRED`                                  | Still-pending request reached its deadline; it is not relabelled cancelled                    |
| 503                                                     | Persistence, readiness, quarantine or uncertain acknowledgement; no success is asserted       |

Do not retry automatically after a timeout/503. Read the exact request through
an authorized GET. Seeing cancelled establishes stored state, not which of two
indistinguishable concurrent calls won. An approved response is not withdrawal;
review may have won the race, and this feature does not stop admitted work.

## Evidence and remaining limits

PostgreSQL commits one pending→cancelled transition, distinct requester metadata,
version3 bounded `approval.cancelled` evidence and outbox in one transaction.
It writes no reviewer decision, permit consumption or execution attempt. History
can filter cancelled rows, and owner inspection includes their count. Ordinary
restart preserves state. An older database restore can resurrect earlier
authority; the [quarantine/recovery rules](operator-recovery-quarantine.md) remain.

Membership revocation and the authority database are not one transaction; removal
affects new ingress checks, not guaranteed instantaneous in-flight revocation.
One named same-Mac Auth0/source-free composition exercised installed withdrawal
on 2026-10-21. This local simulator API still does not establish arbitrary
external-IdP interoperability, public ingress, production target control or
safe rollback. See the
[installed qualification record](../demo-scenarios/evidence/installed-auth0-approval-2026-10-22.md).
