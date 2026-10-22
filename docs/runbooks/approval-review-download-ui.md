# Browser approval review metadata download

Requirements: FR-007, FR-010, FR-012; SEC-003, SEC-005, SEC-009, SEC-012; DATA-001, DATA-003; REL-002.  
Decisions: [ADR 0036](../architecture/0036-local-browser-approval-boundary.md), ADR 0064 (Unsigned, role-scoped approval review export; see [PR #70](https://github.com/hemantkgupta/tracepermit/pull/70)).  
Related runbooks: [Simulator approval-history API](approval-history-api.md), [Browser approval history](browser-approval-history.md).

This runbook documents the React console's explicit review record download control in TracePermit's browser approval inbox. It provides safe, role-scoped, bounded downloading of unsigned simulator approval review records over the same-origin authenticated `/browser/api/v1/approvals/{id}/export` route.

> [!IMPORTANT]
> The exported JSON record is simulator-only, metadata-only, unsigned, and incomplete. It inherits read visibility from the current session, not reviewer or execution authority, and cannot authorize actions. No bearer tokens or credentials enter client JavaScript or browser storage.

---

## 1. Screen Behavior and Download Control

When an approval request is selected in the inbox, an explicit "Review record" section appears in the detail pane below the exact action metadata:

```
+-------------------------------------------------------------------------------+
| Review record                                                                 |
| Review records are simulator-only, metadata-only, unsigned, and incomplete.   |
| Export inherits current read visibility, not review or execution authority,   |
| and cannot authorize actions.                                                 |
|                                                                               |
| [Download review JSON]                                                        |
+-------------------------------------------------------------------------------+
```

### Control Characteristics

- **Explicit User Gesture**: Downloads occur strictly on user click. No fetch or download is initiated on mount, route change, search, selection, or mutation.
- **Pre-download Explanation**: The UI explicitly explains that the record is simulator-only, metadata-only, unsigned, incomplete, and not execution authority.
- **Session-bound Authority**: The button is enabled only when the user holds a valid, unexpired session with read visibility (`approvals.read` scope or `REQUESTER`/`REVIEWER`/`VIEWER` role).
- **Disabled States**: The button is disabled during loading, search transitions, invalid or expired sessions, mutation in-flight, or when another export is downloading.
- **Single Action & Duplicate Protection**: While a download is in-flight, the button indicates "Downloading review JSON…" and disables further clicks.

---

## 2. Transport and Streaming Bounds

- **Same-Origin Route**: `GET /browser/api/v1/approvals/{approvalRequestId}/export`.
- **Credentials**: Handled via browser `credentials: "same-origin"` with `tp_local_session` HttpOnly cookie. No bearer tokens, access tokens, or refresh tokens are accessed by JavaScript or stored in `localStorage`/`sessionStorage`.
- **Finite Abort Timeout**: All fetches are bounded by a 15-second abort deadline (`AbortSignal.timeout(15_000)`).
- **Streaming Byte Limit**: The response body stream is read chunk-by-chunk using a `ReadableStreamDefaultReader` and decoded with a strict UTF-8 `TextDecoder`. If cumulative bytes exceed **32,768 bytes**, the reader stream is immediately cancelled and the operation fails closed with an `ApprovalApiError(502)`.

---

## 3. Strict Closed-Schema Validation

Before creating a browser `Blob` or prompting a file save, the parsed response is strictly validated against the closed v1 schema contract:

1. **Closed Root Envelope**: Exactly `schemaVersion` (`"1.0"`), `kind` (`"tracepermit-approval-review"`), `canonicalization` (`"tracepermit-canonical-json-v1"`), `payload`, and `payloadHash` (`^sha256:[a-f0-9]{64}$`). No extra properties are permitted.
2. **Payload Envelope**: Exactly `generatedAt` (calendar-valid RFC 3339 date-time with timezone offset, ≤40 chars), `mode` (`"simulated"`), `assurance`, and `approval`.
3. **Assurance Flags Invariant**: All six assurance properties must be strictly `false`:
   - `signed: false`
   - `completeHistory: false`
   - `transactionalSnapshot: false`
   - `independentAnchor: false`
   - `authorizesExecution: false`
   - `argumentsIncluded: false`
4. **Approval Snapshot Matching**:
   - `approval.mode`: `"simulated"`
   - `approval.approvalRequestId`: Must match the selected request ID pattern (`^[a-f0-9-]{36}$`) and exact selected ID.
   - `approval.requestHash`: Must match the selected request hash pattern (`^sha256:[a-f0-9]{64}$`) and exact selected hash.
   - `approval.requesterId` & optional `approval.reviewerId`: Valid `^principal:[a-f0-9]{64}$`.
   - `approval.createdAt` & `approval.expiresAt`: Calendar-valid RFC 3339 date-times with timezone offset (≤40 chars).
   - `approval.originalPolicyOutcome`: `"require_approval"`.
   - `approval.policyVersion`: Safe integer between 1 and 2,147,483,647.
   - `approval.toolName`: Valid pattern `^[A-Za-z0-9][A-Za-z0-9._:-]{0,255}$`.

No browser-side cryptographic verification of payload hash is claimed; strict schema adherence and identity binding are enforced.

---

## 4. In-Flight Race and Invalidation Guards

When the user initiates a download, the client captures the current operational context:

- `clickGeneration`: The current search/history generation counter.
- `clickSessionAuth`: The shared session authority fingerprint derived from `authorityContext` (tenant, principal, human, sorted roles/scopes, CSRF context).
- `clickRequestId`: The selected approval request ID.
- `clickRequestHash`: The selected request binding hash.
- `clickSessionExpiresAt`: The session expiration timestamp.

If any of the following occur while the network response is in-flight, the download is immediately discarded:

- User logs out (`sessionRef.current === null`).
- Session expires (`Date.now() >= clickSessionExpiresAt`).
- Authority or roles change.
- User selects a different approval request or clears selection.
- Request hash changes.
- Generation advances due to refresh, filtering, or pagination.

---

## 5. File Download and Resource Cleanup

- **Safe Fixed Filename**: Derived strictly from the validated UUID: `tracepermit-approval-${approvalRequestId}.json`. No user input or unvalidated paths are accepted.
- **Blob Type**: `application/json;charset=utf-8`.
- **URL Revocation**: Object URLs created via `URL.createObjectURL(blob)` are revoked promptly (within 100ms) and upon any unmount or error to prevent memory leaks.

---

## 6. Error Handling

Raw server response text, stack traces, and JSON bodies are never displayed. The UI maps HTTP statuses and validation failures to fixed, safe messages:

| Condition                          | User-Facing Message                                                                                  |
| ---------------------------------- | ---------------------------------------------------------------------------------------------------- |
| HTTP 401                           | Session expired: redirects to sign-in screen.                                                        |
| HTTP 403                           | "This operation is not authorized. Refresh to check your current role."                              |
| HTTP 404                           | "The requested approval was not found or is no longer visible. Refresh to update."                   |
| HTTP 503                           | "The approval service could not complete this export. Check its state before retrying."              |
| Oversized (>32KB) / Schema Failure | "The review export record was invalid or could not be verified. Server state remains authoritative." |

---

## 7. Verification

Run the test suites locally in the isolated workspace:

```bash
# Run unit and component test suites (Vitest)
pnpm --filter @tracepermit/console test

# Verify strict TypeScript types
pnpm --filter @tracepermit/console typecheck

# Check build output
pnpm --filter @tracepermit/console build

# Run the required browser export integration test. This starts the real Java
# simulator fixture and fails if its authenticated export route is unavailable.
pnpm test:approval-export:browser

# Run full project checks
pnpm check
pnpm format:check
pnpm docs:links
```

The browser CI job runs this integration gate after installing its pinned
Playwright Chromium. A missing Java route, missing seeded approval, invalid
response, failed browser download, malformed saved JSON file, leaked listener,
offline-checker rejection, or relevant console error fails the job. The wrapper
builds the source-free checker distribution first and invokes its installed
launcher with only Java and a credential-free environment. The integrated check
is never skipped because another implementation slice is absent.
