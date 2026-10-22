# Approval review metadata export

This backend adds an unsigned, simulator-only JSON review record for one request.
It is useful for a controlled demo, support review or design-partner evaluation,
not a compliance certificate or independently verified audit trail.

Requirements: FR-007/010/012; SEC-003/005/009/012; DATA-001/003; REL-002.
See [ADR0064](../architecture/0064-unsigned-approval-review-export.md) and the
[closed v1 schema](../../contracts/json-schema/approval-review-export-v1.schema.json).

## Download contract

- Bearer: `GET /api/v1/approvals/{approvalRequestId}/export`.
- Browser: `GET /browser/api/v1/approvals/{approvalRequestId}/export`.
- No query, request body, filters, tenant/owner overrides or POST mutation.
- Current verified identity, enrollment and `approvals.read` on every call.
  Requester-only members export only their own requests. Workspace reviewers,
  viewers and combined roles see the same requests as the existing detail API.
- Browser credentials stay in HttpOnly cookies, never pasted into JavaScript.
  Existing same-origin, mixed-auth rejection and session expiry rules apply.
- Success: JSON, `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`, and
  attachment name `tracepermit-approval-{id}.json` derived only from validated ID.
  Maximum 32,768 serialized UTF-8 bytes. No redirect, remote sink or email send.
- Missing/invalid/expired authentication: 401; absent scope/enrollment: 403;
  absent or invisible record: 404. Malformed queries: 400; unsupported method: 405. Missing/inconsistent snapshot or unavailable storage: bounded 503. There
  is no partial file or raw fallback. Preserve the real error, do not auto-retry.

The envelope has exactly `schemaVersion`, `kind`, `canonicalization`, `payload`,
`payloadHash`. Fixed values are `1.0`, `tracepermit-approval-review`, and
`tracepermit-canonical-json-v1`. `payload` contains `generatedAt`, `mode`,
`approval`, `assurance`. The approval fields and optional `reviewerId` are listed
in the schema. Never decode a hash into authority or substitute it for login.

The payload hash is `CanonicalJson.sha256(payload)` (UTF-8 canonical JSON), not
the file-byte hash. JSON key ordering and whitespace may change without changing
it. This export contains only positive 32-bit policy integers, strings and
booleans; do not alter the existing v1 numeric/canonicalization profile.

## What the record means

It preserves the original request binding, pseudonymous participants, policy
ID/version, stored approval status, derived expiry and observed execution status.
Pending/approved is not execution; consumed is not necessarily succeeded.
Cancelled/denied remains not-executed. `unknown` is not failed or safe to retry.

All assurance flags are false. No argument, target response, token, source proof,
permit or raw evidence is included. The request hash binds original operational
data that is intentionally absent. The export checksum cannot verify that original
binding, completeness, authorship, real-world outcome or resistance to a database
administrator. Concurrent reads are not a transactional snapshot. Downloaded
metadata can still identify or correlate people/work; handle it privately.

## Verification and delivery status

```bash
pnpm install --frozen-lockfile
pnpm build
source scripts/configure-java-environment.sh
java/gradlew -p java :adapters-http:test --tests '*ApprovalReviewExportTest' --tests '*SimulatorApprovalExportTest' --tests '*SimulatorApprovalRedactionBoundaryTest'
java/gradlew -p java :review-verifier:test
pnpm test:approval-export:browser
pnpm check
pnpm format:check
pnpm docs:links
```

These tests use signed synthetic tokens, disposable PostgreSQL and the synthetic
browser protocol. They cover visibility/scope/enrollment/expiry, checksum
sensitivity, terminal states, restart, original binding, no read-side authority
or simulator changes, attachment/cache headers and canary exclusion. The required
browser gate exercises the rendered download against the real Java route and
passes the saved file to the source-free installed checker. These tests do not
claim live Auth0, fresh-laptop installation, retention/deletion or full MVP
completion. The milestone boundaries are in the
[delivery plan](../planning/audit-ready-approval-workspace.md).
