# Withdraw a pending simulator approval

This is owner withdrawal of an unreviewed request, **not an emergency stop**.
Only a live `pending` request may become terminal `cancelled`. No real AWS
resource is changed. The Java API exists; the requester React button and
source-free installed withdrawal qualification are separate work.

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
There is no Withdraw button yet, and the existing dialog Cancel/Back is not
durable cancellation. No new MCP tool is exposed.

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
This local simulator API does not establish external-IdP interoperability,
installed withdrawal qualification, production target control or safe rollback.
