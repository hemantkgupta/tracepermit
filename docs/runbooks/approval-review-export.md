# Approval review metadata export

This backend adds an unsigned, simulator-only JSON review record for one request.
It is useful for a controlled demo, support review or design-partner evaluation,
not a compliance certificate or independently verified audit trail.

Requirements: FR-007/010/012; SEC-003/005/009/012; DATA-001/003;
REL-001/002/009; OPS-002/004.
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

The browser qualification separately records a SHA-256 digest over the exact
downloaded file bytes. That qualification digest is an ephemeral test
observation, not a field added to the export and not an integrity signature.

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
browser gate first runs the focused Java authorization/export tests and installed
verifier tests. It then fixes the synthetic clock and action, creates a real
PostgreSQL-backed approval, clicks the rendered React control, captures exactly
one Playwright download and saves those browser-provided bytes in an owned private
temporary directory. The gate hashes that file, passes that exact saved path to
the installed Java 21 launcher with a credential-free environment, checks the
fixed success result, hashes the file again and requires the digests to match. It
never recreates or substitutes a positive fixture. Opaque identifiers and runtime
test signing keys remain freshly generated, so the per-run file digest is recorded
rather than hard-coded.

The same permanent gate checks malformed/oversized documents, unsupported
versions, extra fields, mode/assurance changes, missing evidence, checksum and
payload mutation, stale refresh/filter/logout state, stale sessions, cross-owner
visibility, controlled API/download/verifier failures, one-request/one-download
bounds, fixed diagnostics and deterministic cleanup. It fails if its listener,
process, browser context, container or temporary qualification directory remains.

Successful verification still does not prove current authorization. The export
is unsigned and is not a signed attestation, and all targets remain simulated.
These tests do not claim live Auth0, fresh-laptop installation,
retention/deletion or full MVP completion. The milestone boundaries are in the
[delivery plan](../planning/audit-ready-approval-workspace.md).
