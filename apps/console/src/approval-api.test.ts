import { afterEach, describe, expect, it, vi } from "vitest";
import {
  ApprovalApiError,
  canExecute,
  canRequest,
  canReview,
  decideApproval,
  isExpired,
  loadApprovalPage,
  loadApprovals,
  loadApprovalSession,
  signOut,
  submitApproval,
  type Approval,
  type ApprovalPage,
  type ApprovalSession,
} from "./approval-api.js";

const session: ApprovalSession = {
  mode: "simulated",
  principalId: "principal:alice",
  tenantId: "synthetic-alpha",
  human: true,
  roles: ["REQUESTER"],
  scopes: ["approvals.read", "approvals.request", "approvals.execute"],
  csrfToken: "synthetic-csrf-not-a-bearer-token",
  expiresAt: "2099-01-01T00:00:00Z",
};

const approval: Approval = {
  mode: "simulated",
  approvalRequestId: "synthetic-request",
  requestHash: "sha256:exact",
  requesterId: session.principalId,
  status: "pending",
  createdAt: "2026-10-17T00:00:00Z",
  expiresAt: "2099-01-01T00:00:00Z",
  expired: false,
  policyId: "synthetic-policy",
  policyVersion: 1,
  originalPolicyOutcome: "require_approval",
  executionStatus: "not_executed",
  action: {
    toolName: "aws.ec2.update_security_group",
    target: {},
    arguments: {},
  },
};

afterEach(() => vi.unstubAllGlobals());

describe("role-scoped approval client", () => {
  it("requires role AND scope, distinct human review and owner-only execution", () => {
    const reviewer = {
      ...session,
      principalId: "principal:bob",
      roles: ["REVIEWER"],
      scopes: ["approvals.read", "approvals.decide"],
    };
    expect(canRequest(session)).toBe(true);
    expect(canRequest({ ...session, scopes: [] })).toBe(false);
    expect(canRequest(reviewer)).toBe(false);
    expect(canReview(reviewer, approval)).toBe(true);
    expect(
      canReview({ ...reviewer, principalId: session.principalId }, approval),
    ).toBe(false);
    expect(canReview({ ...reviewer, human: false }, approval)).toBe(false);
    expect(canReview({ ...reviewer, scopes: [] }, approval)).toBe(false);
    expect(canReview(reviewer, { ...approval, expired: true })).toBe(false);
    expect(canReview(reviewer, { ...approval, status: "approved" })).toBe(
      false,
    );
    const approved = { ...approval, status: "approved" };
    expect(canExecute(session, approved)).toBe(true);
    expect(canExecute(reviewer, approved)).toBe(false);
    expect(
      canExecute({ ...session, principalId: "principal:other" }, approved),
    ).toBe(false);
    expect(
      canExecute(session, { ...approved, executionStatus: "unknown" }),
    ).toBe(false);
    expect(
      canExecute(session, { ...approved, expiresAt: "2000-01-01T00:00:00Z" }),
    ).toBe(false);
    expect(isExpired(approval)).toBe(false);
  });

  it("uses same-origin cookies and CSRF without bearer headers or broad authority fields", async () => {
    const fetcher = vi.fn((_path: string, _init: RequestInit) =>
      Promise.resolve(
        new Response(
          JSON.stringify(
            _path === "/browser/session"
              ? session
              : _init.method === "POST"
                ? approval
                : {
                    mode: "simulated",
                    items: [],
                    pageSize: 50,
                    nextCursor: null,
                  },
          ),
        ),
      ),
    );
    vi.stubGlobal("fetch", fetcher);
    await Promise.all([loadApprovalSession(), loadApprovals()]);
    await submitApproval(session, {
      groupId: "sg-demo",
      port: 443,
      cidr: "192.0.2.0/24",
    });
    await decideApproval(session, approval, "approve");
    await decideApproval(session, approval, "deny");
    await decideApproval(session, approval, "execute");
    await signOut(session);
    for (const [, init] of fetcher.mock.calls) {
      expect(init.credentials).toBe("same-origin");
      expect(init.redirect).toBe("error");
      expect(init.cache).toBe("no-store");
      expect(init.headers ?? {}).not.toHaveProperty("Authorization");
    }
    expect(fetcher.mock.calls[3]?.[1].body).toBe(
      JSON.stringify({ requestHash: "sha256:exact", decision: "approve" }),
    );
    expect(fetcher.mock.calls[5]?.[1].body).toBe(
      JSON.stringify({ requestHash: "sha256:exact" }),
    );
    expect(fetcher.mock.calls[5]?.[1].headers).toHaveProperty(
      "X-CSRF-Token",
      session.csrfToken,
    );
  });

  it.each([400, 401, 403, 409, 503])(
    "does not retry or expose response secrets on HTTP %i",
    async (status) => {
      const fetcher = vi.fn(() =>
        Promise.resolve(new Response("sensitive-server-detail", { status })),
      );
      vi.stubGlobal("fetch", fetcher);
      await expect(
        decideApproval(session, approval, "execute"),
      ).rejects.toBeInstanceOf(ApprovalApiError);
      expect(fetcher).toHaveBeenCalledTimes(1);
      expect(new ApprovalApiError(status).message).not.toContain(
        "sensitive-server-detail",
      );
    },
  );

  it("encodes query parameters and omits unset filters in loadApprovalPage", async () => {
    const mockPage: ApprovalPage = {
      mode: "simulated",
      items: [approval],
      pageSize: 20,
      nextCursor: "opaque-cursor-xyz",
    };
    const fetcher = vi.fn((_path: string, _init: RequestInit) =>
      Promise.resolve(new Response(JSON.stringify(mockPage))),
    );
    vi.stubGlobal("fetch", fetcher);

    // 1. Unset query: no query string
    const res1 = await loadApprovalPage();
    expect(fetcher).toHaveBeenLastCalledWith(
      "/browser/api/v1/approvals",
      expect.objectContaining({
        credentials: "same-origin",
        cache: "no-store",
        redirect: "error",
      }),
    );
    expect(res1.items).toHaveLength(1);
    expect(res1.nextCursor).toBe("opaque-cursor-xyz");

    // 2. Empty / whitespace filters: completely omitted
    await loadApprovalPage({
      toolName: "   ",
      approvalRequestId: "",
    });
    expect(fetcher).toHaveBeenLastCalledWith(
      "/browser/api/v1/approvals",
      expect.anything(),
    );

    // 3. Single filter: status
    await loadApprovalPage({ status: "pending" });
    expect(fetcher).toHaveBeenLastCalledWith(
      "/browser/api/v1/approvals?status=pending",
      expect.anything(),
    );

    // 4. Combined filters with trimming and limit
    await loadApprovalPage({
      limit: 25,
      status: "approved",
      toolName: "  aws.ec2.update_security_group  ",
      approvalRequestId: "req-12345",
      cursor: "cursor-111",
    });
    const lastCall = fetcher.mock.calls.at(-1);
    const url = new URL(String(lastCall?.[0] ?? ""), "http://127.0.0.1");
    expect(url.pathname).toBe("/browser/api/v1/approvals");
    expect(url.searchParams.get("limit")).toBe("25");
    expect(url.searchParams.get("status")).toBe("approved");
    expect(url.searchParams.get("toolName")).toBe(
      "aws.ec2.update_security_group",
    );
    expect(url.searchParams.get("approvalRequestId")).toBe("req-12345");
    expect(url.searchParams.get("cursor")).toBe("cursor-111");

    // 5. loadApprovals compatibility calls loadApprovalPage and returns items
    const items = await loadApprovals();
    expect(items).toEqual([approval]);
  });

  it.each([
    { mode: "real" },
    { pageSize: 500 },
    { pageSize: 0 },
    { pageSize: 1.5 },
    { nextCursor: 42 },
    { nextCursor: "x".repeat(2049) },
    { items: [{ ...approval, action: null }] },
    { items: [{ ...approval, executionStatus: [] }] },
    { items: [{ ...approval, status: ["pending"] }] },
    { items: [{ ...approval, createdAt: "invalid" }] },
    { items: [{ ...approval, expiresAt: "invalid" }] },
    { items: [{ ...approval, requestHash: "" }] },
    { pageSize: 1, items: [approval, approval] },
  ])(
    "rejects malformed successful pages without raw fallback: %j",
    async (override) => {
      const fetcher = vi.fn(
        async () =>
          new Response(
            JSON.stringify({
              mode: "simulated",
              items: [approval],
              pageSize: 50,
              nextCursor: null,
              ...override,
            }),
          ),
      );
      vi.stubGlobal("fetch", fetcher);
      await expect(loadApprovalPage()).rejects.toMatchObject({ status: 502 });
      expect(fetcher).toHaveBeenCalledTimes(1);
    },
  );

  it("rejects malformed session and JSON data using safe code-only messages", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response("synthetic-sensitive-canary")),
    );
    await expect(loadApprovalPage()).rejects.toMatchObject({ status: 502 });
    vi.stubGlobal(
      "fetch",
      vi.fn(
        async () =>
          new Response(JSON.stringify({ ...session, roles: ["ADMIN"] })),
      ),
    );
    await expect(loadApprovalSession()).rejects.toMatchObject({ status: 502 });
    expect(isExpired({ ...approval, expiresAt: "invalid" })).toBe(true);
  });

  it("does not treat a malformed mutation response as success", async () => {
    const fetcher = vi.fn(
      async () => new Response(JSON.stringify({ mode: "real" })),
    );
    vi.stubGlobal("fetch", fetcher);
    await expect(
      decideApproval(session, approval, "execute"),
    ).rejects.toMatchObject({ status: 502 });
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
});
