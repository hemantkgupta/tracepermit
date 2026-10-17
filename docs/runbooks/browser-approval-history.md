# Browser approval history and filtering

Requirements (bounded slice): FR-007/010; SEC-001/003/005/009/012; DATA-003; OPS-002.  
Decisions: [ADR 0036](../architecture/0036-local-browser-approval-boundary.md), [ADR 0046](../architecture/0046-authenticated-approval-history-pagination.md).  
Related runbook: [Simulator approval-history API](approval-history-api.md).

This runbook documents the role-safe React controls in TracePermit's browser approval inbox for interacting with the authenticated approval-history API. It provides metadata filtering, keyset pagination, and race-safe state transitions over the same-origin session authenticated `/browser/api/v1/approvals` route.

> [!IMPORTANT]
> This is a bounded authenticated history UI slice, not retention, export, full action history or a production deployment. It operates exclusively against loopback simulator targets. No tokens are pasted into the browser or saved in browser storage.

---

## 1. Screen Behavior and Filtering Controls

The approval inbox provides accessible, server-authoritative filtering and pagination controls directly above the approval request list:

```
+-------------------------------------------------------------------------------+
| TracePermit Exact-action approvals      Simulator only · local browser profile|
+-------------------------------------------------------------------------------+
| HUMAN CONTROL, PRECISE AUTHORITY                                              |
| Approval inbox                                          [Refresh] [Sign out]  |
| Review the exact action. Approval is not execution.                           |
+-------------------------------------------------------------------------------+
| Current identity: REQUESTER · simulated-alpha · session ends 12:00:00 PM      |
+-------------------------------------------------------------------------------+
| Requests (20 loaded)                                                          |
| +---------------------------------------------------------------------------+ |
| | STATUS: [All statuses v]  TOOL: [exact tool name]  ID: [exact request ID] | |
| |                                                  [Apply filters] [Reset]  | |
| +---------------------------------------------------------------------------+ |
| | [aws.ec2.update_security_group]   Created 11:30:15 AM           [pending] | |
| | [aws.ec2.update_security_group]   Created 11:28:00 AM          [consumed] | |
| | ...                                                                       | |
| | [Load older requests] (or "End of results." / Cap notice at 250 rows)     | |
| +---------------------------------------------------------------------------+ |
+-------------------------------------------------------------------------------+
```

### Filtering Controls

- **Status Filter**: Dropdown menu offering `All statuses` (omitted from query), `Pending`, `Approved`, `Denied`, `Expired`, or `Consumed`.
- **Exact Tool Name Filter**: Text input accepting an exact tool identifier (up to 256 characters). No client-side wildcard, regex, or substring search is performed.
- **Exact Request ID Filter**: Text input accepting an exact opaque request ID (up to 256 characters).
- **Explicit Submission**: Filter inputs maintain local draft state while typing and do **not** query on each keystroke. A query is only issued when the user clicks **Apply filters** (or submits the form) or clicks **Reset**.
- **Filter Reset**: The **Reset** button clears all draft inputs and immediately loads the default initial page without filters.

### Keyset Pagination and Bounded Accumulation

- **Load Older Requests**: When the server returns an opaque `nextCursor` string, an explicit **Load older requests** button is rendered at the bottom of the list.
- **Opaque Cursor Handling**: The client treats continuation cursors as opaque tokens, passing them via `URLSearchParams` without decoding, inspecting, or fabricating cursor contents.
- **Deduplication**: Appended page items are deduplicated by `approvalRequestId` to guard against concurrent status changes across pagination boundaries.
- **Bounded In-Memory Accumulation (Cap: 250 Rows)**: The inbox accumulates at most 250 requests in browser memory. When 250 rows are reached:
  - Further pagination is disabled.
  - An explicit warning is displayed: _"Maximum 250 requests loaded. Refine your search filters or reset to view other requests."_
  - An inline **Reset search** button allows the operator to restart traversal with refined filters.
- **End-of-Results**: When `nextCursor` is `null` and items are present, the UI displays _"End of results."_

---

## 2. Consistency, Authority, and Concurrency Guards

### Request-Generation and Cancellation Guard

Asynchronous responses from the server or transport mocks can resolve out of order or after a user has cancelled an action. The inbox uses a two-tier guard:

1. **`AbortController` cancellation**: Cancels obsolete in-flight HTTP fetches when a new search, reset, or refresh is initiated.
2. **Monotonic Request Generation (`generationRef`)**: Every search, reset, refresh, or pagination action increments an internal request generation counter. Any response whose generation does not match the current active generation is immediately discarded. A superseded page cannot overwrite current filters, restore an invalidated session, append rows into a newer search, or reopen a stale confirmation dialog.

### Parallel Session Authority Re-read

Every page request (both initial search and continuation) re-reads `/browser/session` in parallel with `/browser/api/v1/approvals`. If session membership, scopes, or authentication status have changed:

- Authority is updated immediately before rendering mutation buttons.
- On HTTP 401: All items, selections, confirmations, and cursors are purged, and the sign-in screen is rendered.
- On HTTP 403: Stale authority-dependent data is purged, and a safe message is displayed.
- On HTTP 400: An explicit **Restart search** action is rendered, preventing automatic retries on malformed continuation cursors.

### Mutation and Invalidation Invariants

- **Refresh from Page 1**: Any successful approval decision (`approve` or `deny`) or execution (`execute`) refreshes the inbox from the first page using current active filters. New rows are **never** appended onto an old traversal.
- **Confirmation Invalidation**: Any active confirmation modal is immediately closed upon filter changes, search submissions, pagination, explicit refresh, error conditions, or session changes.
- **Creation vs. Expiration Labels**: The UI displays `createdAt` for creation timestamps and `expiresAt` for expiration deadlines. They are never conflated.
- **Stored Status vs. Derived Expiry**: A request that is past its lifetime retains its stored status (e.g. `pending` or `approved`) with an `expired` badge, preventing past-expiry rows from becoming executable or masquerading as active.

---

## 3. API Contract Reference

The frontend consumes `GET /browser/api/v1/approvals` with same-origin credentials, `no-store` cache, and `error` redirect policy:

| Parameter           | Type              | Behavior                                                                                       |
| :------------------ | :---------------- | :--------------------------------------------------------------------------------------------- |
| `limit`             | Decimal `1..50`   | Default 50; omitted when default.                                                              |
| `status`            | String            | Exact stored status (`pending`, `approved`, `denied`, `expired`, `consumed`). Omitted for all. |
| `toolName`          | String (max 256)  | Exact tool name; trimmed and omitted when empty.                                               |
| `approvalRequestId` | String (max 256)  | Exact request ID; trimmed and omitted when empty.                                              |
| `cursor`            | String (max 2048) | Opaque continuation; omitted on initial page.                                                  |

---

## 4. Verification and Browser Qualification

### Integrator repair and observed qualification (2026-10-17)

The initial producer head `1e6edd45dd525a6e33a358cab948f62ea905fe7e`
passed helper tests but failed mounted browser entry: state generation0 rejected
the initial generation1 response, leaving both anonymous401 and signed-in200
responses permanently loading. The repair initializes through the same transition
as refresh, synchronizes refs/state before dispatch, and invalidates both on
logout, expiry and read errors. Session and page requests remain parallel.
Changed authority context cannot append to a previous traversal. Failed reads
clear rows/selection; mutations require current, non-loading, error-free authority.
There is no automatic retry. Client reads have a15-second abort deadline.

Session/page/item shapes, mode, timestamps, scalar bounds and continuation are
checked at runtime; body JSON is rejected above1MiB after receipt (not a streaming
wire-byte limit). Unknown outcomes fail closed. Incoming rows are deduplicated
within and across pages. The250-row cap is distinct from observed exhaustion.
Stored pending/approved status remains visible alongside derived expiry.

Actual built React/Java/PostgreSQL16.4 qualification used installed Chrome through
Playwright on numeric loopback, with synthetic accounts only. Browser plugin was
unavailable; no browser/dependency download or personal account was used.
The live fixture seeded51 Alice and201 Dave pending requests through authenticated
browser APIs with session/CSRF values kept only in memory. Observed checks:

- Anonymous entry/sign-in; authenticated viewer controls; real older-page
  traversal through100/150/200/250 rows with explicit cap, not “all.”
- Alice sees only her51 rows, not Dave's201; a new request resets to page one.
- Bob's explicit decision produces approved/not-executed; Alice's subsequent
  exact action produces consumed/succeeded. Explicit replay returns409.
- Desktop1440×1000 and mobile390×844 render; mobile has no document overflow.
  No JavaScript page exceptions or framework overlay appeared in this flow.
- Controlled client transport400/403/503 and malformed200 responses clear stale
  data, disable mutations and omit raw diagnostic canaries. These are client
  fault simulations, not claims that the real backend emitted malformed data.
- A deliberately delayed fetch resolving after cancellation/logout cannot restore
  identity or rows. This exercises actual component wiring, not only pure helpers.
- Additional controlled session/page responses verify changed-principal context
  discards the old traversal, pending status remains visible with derived expiry,
  and local session-expiry timers remove cached authority and rows.

Screenshots and browser profiles/logs are private, not committed evidence or
external IdP certification. The manual checklist below is for future reruns;
starting the fixture alone does not establish that its interactions passed.

### Unit and State Test Execution

Run the fast Vitest suite for the console:

```bash
pnpm --filter @tracepermit/console test
pnpm --filter @tracepermit/console typecheck
```

The suite covers:

- Exact parameter encoding and omission of unset filters in `approval-api.test.ts`.
- Keyset append, deduplication, and 250-row capacity clamping in `approval-history-state.test.ts`.
- Monotonic generation race guards and out-of-order response rejection.
- Session logout, 401, 403, and 400 error state transitions.

### Interactive Synthetic Browser QA

Run the synthetic browser qualification launcher:

```bash
pnpm build
pnpm demo:approvals:browser
```

The qualification launcher starts disposable PostgreSQL 16 and a test-only synthetic IdP on a loopback port.

1. **Viewer History and Pagination (`carol`)**:
   - Sign in as `carol` (viewer role, read-only scope).
   - Verify filter controls are visible and enabled.
   - Filter by status `pending`; click **Apply filters**. Verify only pending requests are listed.
   - Click **Reset**; verify filters clear and all visible requests reload.
   - Click **Load older requests**; verify deduplicated accumulation and updated loaded count.
   - Verify viewer sees no approval or execution buttons.
2. **Requester Ownership (`alice`)**:
   - Sign in as `alice` (requester role).
   - Verify only alice's requests are visible.
   - Submit a new simulated change request. Verify request appears at top of list.
   - Self-review is disallowed.
3. **Reviewer Decisions (`bob`)**:
   - Sign in as `bob` (reviewer role).
   - Inspect alice's pending request. Click **Approve exact action** $\to$ **Confirm approve**.
   - Verify status transitions to `approved`.
4. **Execution and Replay Denial (`alice`)**:
   - Sign in as `alice`.
   - Select the approved request. Click **Execute approved simulation** $\to$ **Confirm execute**.
   - Verify status transitions to `consumed` and execution status shows `succeeded`.
   - Verify execution buttons are permanently removed; repeat execution is denied by the server.
5. **Cap and Error Boundaries**:
   - Test narrow viewport (mobile responsiveness of filter grid).
   - Test 400 restart action button when an invalid continuation occurs.
