import { afterEach, describe, expect, it, vi } from "vitest";
import {
  ApprovalApiError,
  canExecute,
  canRequest,
  canReview,
  decideApproval,
  isExpired,
  loadApprovals,
  loadApprovalSession,
  signOut,
  submitApproval,
  type Approval,
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
      Promise.resolve(new Response(JSON.stringify({ items: [] }))),
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

  it.each([401, 403, 409, 503])(
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
});
