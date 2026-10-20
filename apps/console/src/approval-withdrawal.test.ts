import { afterEach, describe, expect, it, vi } from "vitest";
import {
  ApprovalApiError,
  cancelApproval,
  canConfirmApproval,
  canWithdraw,
  type Approval,
  type ApprovalSession,
} from "./approval-api.js";

const now = Date.parse("2026-10-20T00:00:00Z");
const session: ApprovalSession = {
  mode: "simulated",
  principalId: "synthetic-owner",
  tenantId: "synthetic-workspace",
  human: true,
  roles: ["REQUESTER"],
  scopes: ["approvals.read", "approvals.cancel"],
  csrfToken: "synthetic-csrf",
  expiresAt: new Date(now + 120_000).toISOString(),
};
const pending: Approval = {
  mode: "simulated",
  approvalRequestId: "synthetic-request",
  requestHash: "sha256:synthetic-exact",
  requesterId: session.principalId,
  status: "pending",
  executionStatus: "not_executed",
  createdAt: new Date(now).toISOString(),
  expiresAt: new Date(now + 60_000).toISOString(),
  expired: false,
  policyId: "synthetic-policy",
  policyVersion: 1,
  originalPolicyOutcome: "require_approval",
  action: {
    toolName: "aws.ec2.update_security_group",
    target: {},
    arguments: {},
  },
};

afterEach(() => vi.unstubAllGlobals());

describe("pending-only requester withdrawal guidance and client", () => {
  it("requires explicit requester membership, cancel scope and ownership, not reviewer authority", () => {
    expect(canWithdraw(session, pending, now)).toBe(true);
    expect(canWithdraw({ ...session, human: false }, pending, now)).toBe(true);
    for (const changed of [
      { ...session, scopes: ["approvals.request", "approvals.execute"] },
      { ...session, roles: ["REVIEWER"] },
      { ...session, roles: ["VIEWER"] },
      { ...session, principalId: "synthetic-other" },
      { ...session, expiresAt: new Date(now).toISOString() },
      { ...session, expiresAt: "invalid" },
    ])
      expect(canWithdraw(changed, pending, now)).toBe(false);
  });

  it.each(["approved", "denied", "expired", "consumed", "cancelled"])(
    "refuses terminal or decided %s requests",
    (status) => {
      const current = { ...pending, status };
      expect(canWithdraw(session, current, now)).toBe(false);
      expect(
        canConfirmApproval(session, current, pending, "withdraw", now),
      ).toBe(false);
    },
  );

  it("refuses expiry, execution admission and stale selection/hash at confirmation time", () => {
    expect(canConfirmApproval(session, pending, pending, "withdraw", now)).toBe(
      true,
    );
    for (const current of [
      undefined,
      { ...pending, approvalRequestId: "other-request" },
      { ...pending, requestHash: "sha256:other" },
      { ...pending, requesterId: "other-owner" },
      { ...pending, executionStatus: "claimed" },
      { ...pending, expired: true },
      { ...pending, expiresAt: "invalid" },
    ])
      expect(
        canConfirmApproval(session, current, pending, "withdraw", now),
      ).toBe(false);
    for (const time of [now + 60_000, now + 60_001, Number.NaN])
      expect(
        canConfirmApproval(session, pending, pending, "withdraw", time),
      ).toBe(false);
    expect(canConfirmApproval(null, pending, pending, "withdraw", now)).toBe(
      false,
    );
  });

  it("sends one exact-hash cancel POST with cookie/CSRF, not a reviewer decision", async () => {
    const fetcher = vi.fn(
      async () =>
        new Response(JSON.stringify({ ...pending, status: "cancelled" })),
    );
    vi.stubGlobal("fetch", fetcher);
    expect((await cancelApproval(session, pending)).status).toBe("cancelled");
    expect(fetcher).toHaveBeenCalledExactlyOnceWith(
      "/browser/api/v1/approvals/synthetic-request/cancel",
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({ requestHash: pending.requestHash }),
        credentials: "same-origin",
        cache: "no-store",
        redirect: "error",
        headers: {
          "Content-Type": "application/json",
          "X-CSRF-Token": session.csrfToken,
        },
      }),
    );
  });

  it.each([401, 403, 409, 503])(
    "does not retry cancellation on HTTP %i or expose raw diagnostics",
    async (status) => {
      const fetcher = vi.fn(
        async () => new Response("synthetic-private-canary", { status }),
      );
      vi.stubGlobal("fetch", fetcher);
      await expect(cancelApproval(session, pending)).rejects.toMatchObject({
        status,
      });
      expect(fetcher).toHaveBeenCalledTimes(1);
      expect(new ApprovalApiError(status).message).not.toContain("canary");
    },
  );

  it("leaves network completion uncertain and never automatically repeats it", async () => {
    const fetcher = vi.fn(async () => {
      throw new TypeError("synthetic transport loss");
    });
    vi.stubGlobal("fetch", fetcher);
    await expect(cancelApproval(session, pending)).rejects.toBeInstanceOf(
      TypeError,
    );
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it.each([
    { mode: "real" },
    { status: "approved" },
    { executionStatus: "succeeded" },
    { approvalRequestId: "other-request" },
    { requestHash: "sha256:other" },
    { requesterId: "other-owner" },
  ])(
    "does not claim success for a mismatched or malformed cancellation response: %j",
    async (change) => {
      const fetcher = vi.fn(
        async () =>
          new Response(
            JSON.stringify({ ...pending, status: "cancelled", ...change }),
          ),
      );
      vi.stubGlobal("fetch", fetcher);
      await expect(cancelApproval(session, pending)).rejects.toMatchObject({
        status: 502,
      });
      expect(fetcher).toHaveBeenCalledTimes(1);
    },
  );
});
