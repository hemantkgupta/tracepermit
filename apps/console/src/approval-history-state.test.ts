import { describe, expect, it } from "vitest";
import type { Approval, ApprovalPage } from "./approval-api.js";
import {
  MAX_ACCUMULATED_ROWS,
  appendPageItems,
  deduplicateApprovals,
  initialHistoryState,
  pageFailure,
  pageSuccess,
  resetHistoryFilters,
  sessionLoggedOut,
  startLoadOlder,
  startRefresh,
  startSearch,
  toApprovalQuery,
  type HistoryFilters,
  authorityContext,
  historyAuthorityReady,
  sanitizeFilterString,
} from "./approval-history-state.js";

function makeMockApproval(
  id: string,
  toolName = "aws.ec2.update_security_group",
): Approval {
  return {
    mode: "simulated",
    approvalRequestId: id,
    requestHash: `hash-${id}`,
    requesterId: "principal:alice",
    status: "pending",
    createdAt: "2026-10-17T00:00:00Z",
    expiresAt: "2099-01-01T00:00:00Z",
    expired: false,
    policyId: "policy-1",
    policyVersion: 1,
    originalPolicyOutcome: "require_approval",
    executionStatus: "not_executed",
    action: { toolName, target: {}, arguments: {} },
  };
}

describe("approval history state helper", () => {
  it("converts filters to query omitting unset and trimming whitespace", () => {
    const emptyFilters: HistoryFilters = {
      status: "",
      toolName: "   ",
      approvalRequestId: "",
    };
    expect(toApprovalQuery(emptyFilters)).toEqual({});

    const activeFilters: HistoryFilters = {
      status: "pending",
      toolName: "  aws.ec2.update_security_group  ",
      approvalRequestId: "req-123",
    };
    expect(toApprovalQuery(activeFilters, "cursor-xyz", 10)).toEqual({
      status: "pending",
      toolName: "aws.ec2.update_security_group",
      approvalRequestId: "req-123",
      cursor: "cursor-xyz",
      limit: 10,
    });
  });

  it("deduplicates approvals on append and caps accumulation at 250 rows", () => {
    const item1 = makeMockApproval("item-1");
    const item2 = makeMockApproval("item-2");
    const item3 = makeMockApproval("item-3");

    // Deduplication check
    const deduped = deduplicateApprovals([item1, item2], [item2, item3]);
    expect(deduped.map((i) => i.approvalRequestId)).toEqual([
      "item-1",
      "item-2",
      "item-3",
    ]);

    // Under 250 items: atCapacity is false
    const appendUnder = appendPageItems([item1], [item2]);
    expect(appendUnder.items).toHaveLength(2);
    expect(appendUnder.atCapacity).toBe(false);

    // Create 240 items and append 20 items -> 260 total -> clamped to 250
    const bulk240: Approval[] = [];
    for (let i = 0; i < 240; i++) {
      bulk240.push(makeMockApproval(`id-${i}`));
    }
    const incoming20: Approval[] = [];
    for (let i = 240; i < 260; i++) {
      incoming20.push(makeMockApproval(`id-${i}`));
    }

    const appendCapped = appendPageItems(bulk240, incoming20);
    expect(appendCapped.items).toHaveLength(MAX_ACCUMULATED_ROWS);
    expect(appendCapped.atCapacity).toBe(true);
    expect(
      appendCapped.items[MAX_ACCUMULATED_ROWS - 1]?.approvalRequestId,
    ).toBe("id-249");
  });

  it("starts a fresh search, increments generation and clears traversal state", () => {
    let state = initialHistoryState();
    state = {
      ...state,
      items: [makeMockApproval("old-1")],
      nextCursor: "old-cursor",
      selectedId: "old-1",
      loading: false,
      generation: 1,
    };

    const newDraft: HistoryFilters = {
      status: "approved",
      toolName: "aws.s3.delete_bucket",
      approvalRequestId: "",
    };

    const { nextState, query, generation } = startSearch(state, newDraft);
    expect(generation).toBe(2);
    expect(nextState.generation).toBe(2);
    expect(nextState.loading).toBe(true);
    expect(nextState.items).toHaveLength(0);
    expect(nextState.nextCursor).toBeNull();
    expect(nextState.selectedId).toBeNull();
    expect(nextState.activeFilters.status).toBe("approved");
    expect(nextState.activeFilters.toolName).toBe("aws.s3.delete_bucket");
    expect(query).toEqual({
      status: "approved",
      toolName: "aws.s3.delete_bucket",
    });
  });

  it("resets filters, increments generation and resets active and draft filters", () => {
    let state = initialHistoryState();
    state = {
      ...state,
      activeFilters: {
        status: "pending",
        toolName: "custom.tool",
        approvalRequestId: "req-1",
      },
      draftFilters: {
        status: "pending",
        toolName: "custom.tool",
        approvalRequestId: "req-1",
      },
      items: [makeMockApproval("item-1")],
      nextCursor: "cursor-1",
      selectedId: "item-1",
      generation: 5,
    };

    const { nextState, query, generation } = resetHistoryFilters(state);
    expect(generation).toBe(6);
    expect(nextState.activeFilters.status).toBe("");
    expect(nextState.activeFilters.toolName).toBe("");
    expect(nextState.activeFilters.approvalRequestId).toBe("");
    expect(nextState.draftFilters.status).toBe("");
    expect(nextState.items).toHaveLength(0);
    expect(nextState.nextCursor).toBeNull();
    expect(nextState.selectedId).toBeNull();
    expect(query).toEqual({});
  });

  it("prevents duplicate or out-of-bounds loadOlder requests", () => {
    let state = initialHistoryState();
    state = {
      ...state,
      loading: false,
      loadingOlder: false,
      nextCursor: null, // No cursor!
    };

    // Disallowed because nextCursor is null
    const noCursor = startLoadOlder(state);
    expect(noCursor.query).toBeNull();
    expect(noCursor.nextState).toBe(state);

    // Disallowed because already loadingOlder
    state = { ...state, nextCursor: "cursor-abc", loadingOlder: true };
    const alreadyLoading = startLoadOlder(state);
    expect(alreadyLoading.query).toBeNull();

    // Disallowed when at capacity (>= 250)
    const bulk250: Approval[] = [];
    for (let i = 0; i < 250; i++) bulk250.push(makeMockApproval(`id-${i}`));
    state = {
      ...state,
      items: bulk250,
      nextCursor: "cursor-abc",
      loadingOlder: false,
    };
    const atCapacity = startLoadOlder(state);
    expect(atCapacity.query).toBeNull();

    // Allowed when valid
    state = {
      ...state,
      items: [makeMockApproval("id-0")],
      nextCursor: "valid-cursor",
      generation: 10,
    };
    const allowed = startLoadOlder(state);
    expect(allowed.query).toEqual({ cursor: "valid-cursor" });
    expect(allowed.generation).toBe(11);
    expect(allowed.nextState.loadingOlder).toBe(true);
  });

  it("guards against stale asynchronous responses via generation check", () => {
    const state = {
      ...initialHistoryState(),
      generation: 5, // Currently at generation 5
      items: [makeMockApproval("item-current")],
    };

    const stalePage: ApprovalPage = {
      mode: "simulated",
      items: [makeMockApproval("stale-item")],
      pageSize: 50,
      nextCursor: "stale-cursor",
    };

    // Stale page response with generation 4 must be ignored
    const afterStaleSuccess = pageSuccess(state, 4, stalePage, false);
    expect(afterStaleSuccess).toBe(state);
    expect(afterStaleSuccess.items[0]?.approvalRequestId).toBe("item-current");

    // Stale error response with generation 4 must be ignored
    const afterStaleFailure = pageFailure(state, 4, 401, "Stale session error");
    expect(afterStaleFailure).toBe(state);

    // Matching generation 5 updates state
    const afterValidSuccess = pageSuccess(state, 5, stalePage, false);
    expect(afterValidSuccess.items[0]?.approvalRequestId).toBe("stale-item");
    expect(afterValidSuccess.loading).toBe(false);
  });

  it("handles 401, 403, and 400 failure statuses with appropriate state clears", () => {
    const baseState = {
      ...initialHistoryState(),
      generation: 2,
      items: [makeMockApproval("item-1")],
      nextCursor: "cursor-1",
      selectedId: "item-1",
    };

    // 401: Clears items, cursor, selection
    const state401 = pageFailure(baseState, 2, 401, "Your session has ended.");
    expect(state401.items).toHaveLength(0);
    expect(state401.nextCursor).toBeNull();
    expect(state401.selectedId).toBeNull();
    expect(state401.errorStatus).toBe(401);

    // 403: Clears items, cursor, selection
    const state403 = pageFailure(
      baseState,
      2,
      403,
      "This operation is not authorized.",
    );
    expect(state403.items).toHaveLength(0);
    expect(state403.nextCursor).toBeNull();
    expect(state403.selectedId).toBeNull();
    expect(state403.errorStatus).toBe(403);

    // 400: Clears stale rows and authority-dependent selection, offers restart
    const state400 = pageFailure(
      baseState,
      2,
      400,
      "Invalid query or continuation.",
    );
    expect(state400.items).toHaveLength(0);
    expect(state400.errorStatus).toBe(400);
  });

  it("clears all state and increments generation on sessionLoggedOut", () => {
    const state = {
      ...initialHistoryState(),
      items: [makeMockApproval("item-1")],
      nextCursor: "cursor-1",
      selectedId: "item-1",
      generation: 3,
    };
    const loggedOut = sessionLoggedOut(state);
    expect(loggedOut.items).toHaveLength(0);
    expect(loggedOut.nextCursor).toBeNull();
    expect(loggedOut.selectedId).toBeNull();
    expect(loggedOut.generation).toBe(4);
  });

  it("refreshes from page 1 on startRefresh with active filters preserved", () => {
    const state = {
      ...initialHistoryState(),
      activeFilters: {
        status: "consumed" as const,
        toolName: "aws.ec2.update_security_group",
        approvalRequestId: "",
      },
      items: [makeMockApproval("item-1")],
      generation: 8,
    };
    const { nextState, query, generation } = startRefresh(state);
    expect(generation).toBe(9);
    expect(nextState.loading).toBe(true);
    expect(nextState.items).toHaveLength(0);
    expect(nextState.activeFilters.status).toBe("consumed");
    expect(query).toEqual({
      status: "consumed",
      toolName: "aws.ec2.update_security_group",
    });
  });

  it("aligns the initial request with success and error generations", () => {
    const first = startRefresh(initialHistoryState());
    expect(first.nextState.generation).toBe(first.generation);
    const success = pageSuccess(
      first.nextState,
      first.generation,
      { mode: "simulated", items: [], pageSize: 50, nextCursor: null },
      false,
    );
    expect(success.loading).toBe(false);
    expect(
      pageFailure(first.nextState, first.generation, 401, "Ended").loading,
    ).toBe(false);
    const loggedOut = sessionLoggedOut(first.nextState);
    expect(
      pageSuccess(
        loggedOut,
        first.generation,
        { mode: "simulated", items: [], pageSize: 50, nextCursor: null },
        false,
      ),
    ).toBe(loggedOut);
  });

  it("deduplicates within a page and distinguishes cap from exhaustion", () => {
    const row = makeMockApproval("duplicate");
    expect(deduplicateApprovals([], [row, row])).toEqual([row]);
    const rows = Array.from({ length: 250 }, (_, i) =>
      makeMockApproval(String(i)),
    );
    const capped = pageSuccess(
      initialHistoryState(),
      0,
      { mode: "simulated", items: rows, pageSize: 50, nextCursor: "more" },
      false,
    );
    expect(capped.atCapacity).toBe(true);
    expect(capped.endOfResults).toBe(false);
  });

  it("disables every mutation during errors, reads and expired sessions", () => {
    const identity = {
      mode: "simulated" as const,
      principalId: "alice",
      tenantId: "alpha",
      human: true,
      roles: ["REQUESTER"],
      scopes: ["approvals.request"],
      csrfToken: "synthetic",
      expiresAt: "2099-01-01T00:00:00Z",
    };
    const ready = { ...initialHistoryState(), loading: false };
    expect(historyAuthorityReady(ready, identity)).toBe(true);
    for (const status of [400, 401, 403, 503]) {
      expect(
        historyAuthorityReady(
          pageFailure(ready, 0, status, "safe error"),
          identity,
        ),
      ).toBe(false);
    }
    expect(
      historyAuthorityReady({ ...ready, loadingOlder: true }, identity),
    ).toBe(false);
    expect(
      historyAuthorityReady(ready, { ...identity, expiresAt: "invalid" }),
    ).toBe(false);
    expect(authorityContext(identity)).not.toBe(
      authorityContext({ ...identity, principalId: "bob" }),
    );
    expect(authorityContext(identity)).not.toBe(
      authorityContext({ ...identity, roles: ["VIEWER"] }),
    );
    expect(() => sanitizeFilterString("x".repeat(257))).toThrow();
  });
});
