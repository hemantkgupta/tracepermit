# ADR 0046 — Authenticated, bounded approval-history queries

- Status: implemented Java simulator API slice; React filtering controls pending
- Requirements: FR-007/010; SEC-001/003/005/009/012; DATA-003; OPS-002

## Decision

Extend only the approval collection's GET route, over existing bearer and optional
same-origin browser authentication. Read authority remains operator enrollment,
`approvals.read`, tenant partition and requester-only ownership. Neither metadata
filters nor cursor contents grant authority. Mutations and MCP envelopes retain
their current query rejection, strict arguments and exact-action checks.

Support exact stored status, tool name and approval ID filters; a page limit of
1–50 (default 50); and an unsigned, versioned, bounded continuation position.
Reject unknown/duplicate/empty parameters, malformed cursors and context changes.
Do not search raw arguments, prompts, identity labels or arbitrary SQL/regex.

Order by immutable `created_at DESC, approval_request_id COLLATE "C" ASC`.
The continuation uses both values, with the mixed-direction comparison expressed
explicitly. Fetch at most limit+1 IDs, hydrate at most limit aggregates in a
read-only repeatable-read transaction, then return a cursor only when another
matching row was observed. Tenant/owner predicates apply before limit/lookahead.
Snapshot-less legacy rows remain absent, matching the existing collection.

The cursor is URL-safe base64 JSON containing version, timestamp, ID and a
canonical context hash of server-derived tenant/principal/visibility plus exact
filters. The hash detects mismatched context; **it is not a MAC, signature,
confidentiality mechanism or capability**. Clients can edit/reconstruct positions.
Every page authenticates and re-derives authority, and every database query
enforces its tenant/owner predicates independently. Limit may change between
pages, but filters and identity/visibility must match. No secret or signing key
is introduced, and restart does not invalidate otherwise usable positions.

After the metadata transaction, each returned item goes through the existing
exact-snapshot/hash verification and bounded default redaction projection.
Projection failure fails the entire response; there is no raw or partial fallback.
No total count, foreign owner search or cross-tenant selector is exposed. Additive
`createdAt`, `pageSize` and nullable `nextCursor` preserve existing item consumers.

## Consistency and limits

- A page is a current read, not a database snapshot held across requests. Status
  changes/backdated or timestamp-tied inserts may alter later membership. Explicit
  refresh restarts from current history. No snapshot/export completeness claim.
- Projection/action/attempt lookups follow the metadata transaction and can see
  newer execution information. Display is not execution authority; mutations
  still reload and verify current state and the original request hash.
- Expiry is a derived display flag, not an automatic read-side transition. A
  `status=pending` result can be past its expiry; `status=expired` matches only
  persisted expired rows. No read consumes, expires, approves or dispatches.
- The list statement has a five-second query timeout and bounded result size;
  this is not an end-to-end request deadline or abuse-control layer. Existing
  schema V8 is unchanged; no new history index, constant-time pagination,
  workload capacity or latency SLO is claimed. Larger datasets need measured
  indexing/upgrade work, not an edit to an installed migration.
- This is approval history only, not full allow/block action search, retention,
  export, external IdP qualification, public hosting or production readiness.
  The React inbox still shows the existing first page until its separate change.

## Verification

Query/value/cursor boundary tests, actual signed HTTP/browser/PostgreSQL tests,
timestamp ties and pages beyond 50, new-insert/restart continuation, role/owner
checks, cross-tenant colliding IDs, snapshot-less rows, exact filters,
authentication/scope/membership changes and unchanged read-side state.
The existing response-redaction tests now exercise the paged collection path.
See the [API contract and runbook](../runbooks/approval-history-api.md).
