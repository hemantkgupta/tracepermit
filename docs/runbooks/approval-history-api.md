# Simulator approval-history API

Requirements (bounded slice): FR-007/010; SEC-001/003/005/009/012;
DATA-003; OPS-002. See [ADR 0046](../architecture/0046-authenticated-approval-history-pagination.md).

This adds metadata filtering and keyset pagination to the **authenticated local
approval service**, not the unauthenticated development action feed. All targets
remain simulated. No retention, export, full-text payload search or public edge
is provided. The [React history inbox](browser-approval-history.md) consumes this
contract with explicit filters and bounded continuation; it does not infer
authority from a cursor or cached rows.

## Routes and exact contract

- Bearer: `GET /api/v1/approvals` with the existing verified access token.
- Browser: `GET /browser/api/v1/approvals` with the existing same-origin session.
  Never paste a bearer token into React or browser storage.
- Required: current operator enrollment and `approvals.read`. A requester-only
  member sees only their own rows; reviewer/viewer or combined roles retain
  workspace visibility. Filters cannot choose tenant, owner, principal or roles.

| Parameter           | Meaning / bound                                                                 |
| ------------------- | ------------------------------------------------------------------------------- |
| `limit`             | Canonical decimal 1–50; default 50. No zero, signs or leading zeroes.           |
| `status`            | Exact stored `pending`, `approved`, `denied`, `expired` or `consumed`.          |
| `toolName`          | Exact tool identifier, at most 256 characters; no substring, wildcard or regex. |
| `approvalRequestId` | Exact opaque request ID, at most 256 identifier characters.                     |
| `cursor`            | Opaque continuation returned by this collection, at most 2048 characters.       |

All parameters are optional and combined with AND. Omit an unset filter instead
of sending an empty value or `all`. Unknown/duplicate/empty fields, malformed
encoding and raw query strings over 4096 characters return HTTP 400
`INVALID_REQUEST`. SQL values are bound parameters; no query text comes from
the caller. All other service routes still reject query parameters.

The successful response remains `mode: simulated` and `items`, with additive
`pageSize` (requested limit) and `nextCursor` (string or null). Each unchanged
approval projection additionally includes UTC ISO `createdAt`:

```json
{
  "mode": "simulated",
  "items": [],
  "pageSize": 20,
  "nextCursor": null
}
```

Existing items retain exact `approvalRequestId`, `requestHash`, `requesterId`,
optional `reviewerId`, stored `status`, `createdAt`, `expiresAt`, derived `expired`,
`policyId`, `policyVersion`, `originalPolicyOutcome`, `executionStatus` and the
redacted `action`. Hashes still bind the original operational snapshot, not the
masked display. No identity/approval/permit rule changes.

Example relative browser requests (the normal console client supplies cookies):

```text
/browser/api/v1/approvals?limit=20&status=pending&toolName=aws.ec2.update_security_group
/browser/api/v1/approvals?limit=20&status=pending&toolName=aws.ec2.update_security_group&cursor=<URL-encoded-nextCursor>
```

Repeat exactly the same filters on continuation. Limit can change. Changing
identity, requester-only/workspace visibility or filters invalidates the cursor
context and returns 400; explicitly start a fresh search. It is an unsigned
position, not a secret, session, authorization capability or proof of completeness.
The server authenticates and scopes every page even if the position is edited.
Never decode/rely on it in client logic; use URLSearchParams to encode it.
Do not retry automatically after an error or silently restart a failed page.

## Display and consistency rules

1. Order is creation time descending, then ID ascending with PostgreSQL C
   collation. Timestamp ties do not skip/duplicate an unchanged dataset.
2. A null cursor ends the observed traversal; no global count is computed.
   A non-null cursor is a continuation hint, not a guarantee later rows still
   match after concurrent status changes.
3. Reads never expire pending state. Past-expiry pending/approved rows retain
   their stored status and set `expired=true`; they do not become executable.
4. Reads do not claim, consume or dispatch. Pending, approved, consumed and
   actual execution outcome remain distinct. Any mutation still verifies
   current authority, expiry, state and exact original hash independently.
5. Pages are current reads, not an immutable export snapshot. Metadata uses
   a per-page read-only transaction; later projection lookups may see newer
   attempt state. Refresh explicitly to restart from current data.
6. Collection output stays bounded and redacted; an invalid snapshot or
   projection-budget failure yields a bounded 503 without a partial/raw fallback.
   Authentication failure is 401; missing scope/enrollment is 403. Browser
   Cookie/Origin/CSRF checks remain unchanged. Responses are no-store.

## Verify

```bash
./java/gradlew -p java :execution:test --tests '*ApprovalHistoryQueryTest'
./java/gradlew -p java :persistence-postgres:test --tests '*PostgresApprovalHistoryTest'
./java/gradlew -p java :adapters-http:test \
  --tests '*ApprovalHistoryParametersTest' \
  --tests '*SimulatorApprovalHistoryTest' \
  --tests '*SimulatorApprovalBrowserTest' \
  --tests '*SimulatorApprovalRedactionBoundaryTest'
pnpm check
pnpm format:check
pnpm docs:links
pnpm docs:flows:check
```

Tests use disposable PostgreSQL, signed synthetic tokens and the existing local
browser protocol fixture. They do not constitute rendered UI or external IdP
qualification. Schema V8 and the operator installation contract are unchanged.
The query's five-second database timeout bounds one statement, not whole-request
latency. No new index, constant-cost paging or production dataset SLO is claimed.
PostgreSQL documents the need for a
[unique ordering with LIMIT](https://www.postgresql.org/docs/16/queries-limit.html);
the mixed descending/ascending comparison deliberately follows both sort keys.
