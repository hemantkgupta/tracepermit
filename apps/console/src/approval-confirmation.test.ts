import { describe, expect, it } from "vitest";
import {
  canConfirmApproval,
  type Approval,
  type ApprovalDecision,
  type ApprovalSession,
} from "./approval-api.js";

const now = Date.parse("2026-10-19T00:00:00Z");
const requester: ApprovalSession = {
  mode: "simulated",
  principalId: "synthetic-alice",
  tenantId: "synthetic-workspace",
  human: true,
  roles: ["REQUESTER"],
  scopes: ["approvals.execute"],
  csrfToken: "synthetic-csrf",
  expiresAt: new Date(now + 600_000).toISOString(),
};
const reviewer: ApprovalSession = {
  ...requester,
  principalId: "synthetic-bob",
  roles: ["REVIEWER"],
  scopes: ["approvals.decide"],
};
const pending: Approval = {
  mode: "simulated",
  approvalRequestId: "synthetic-request",
  requestHash: "sha256:synthetic-exact",
  requesterId: requester.principalId,
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

describe("last-moment approval confirmation guidance", () => {
  it("retains distinct review and explicit owner execution for a current exact snapshot", () => {
    expect(canConfirmApproval(reviewer, pending, pending, "approve", now)).toBe(
      true,
    );
    expect(canConfirmApproval(reviewer, pending, pending, "deny", now)).toBe(
      true,
    );
    const approved = { ...pending, status: "approved" };
    expect(
      canConfirmApproval(requester, approved, approved, "execute", now),
    ).toBe(true);
    expect(
      canConfirmApproval(requester, pending, pending, "execute", now),
    ).toBe(false);
  });

  it("refuses at the exact expiry boundary, not only after it", () => {
    for (const decision of ["approve", "deny", "execute"] as const) {
      const item =
        decision === "execute" ? { ...pending, status: "approved" } : pending;
      const session = decision === "execute" ? requester : reviewer;
      expect(
        canConfirmApproval(session, item, item, decision, now + 59_999),
      ).toBe(true);
      expect(
        canConfirmApproval(session, item, item, decision, now + 60_000),
      ).toBe(false);
      expect(
        canConfirmApproval(session, item, item, decision, now + 60_001),
      ).toBe(false);
    }
  });

  it("rejects server-observed expiry even if the local clock is behind", () => {
    expect(
      canConfirmApproval(
        reviewer,
        { ...pending, expired: true },
        pending,
        "approve",
        now,
      ),
    ).toBe(false);
  });

  it("requires the same still-selected request and exact request hash", () => {
    for (const item of [
      undefined,
      { ...pending, approvalRequestId: "other-request" },
      { ...pending, requestHash: "sha256:changed" },
    ]) {
      expect(canConfirmApproval(reviewer, item, pending, "approve", now)).toBe(
        false,
      );
    }
  });

  it("rechecks current status rather than relying on the confirmation snapshot", () => {
    for (const status of ["approved", "denied", "consumed", "expired"]) {
      expect(
        canConfirmApproval(
          reviewer,
          { ...pending, status },
          pending,
          "approve",
          now,
        ),
      ).toBe(false);
    }
    const approved = { ...pending, status: "approved" };
    for (const executionStatus of [
      "claimed",
      "succeeded",
      "failed",
      "unknown",
    ]) {
      expect(
        canConfirmApproval(
          requester,
          { ...approved, executionStatus },
          approved,
          "execute",
          now,
        ),
      ).toBe(false);
    }
  });

  it("requires current session lifetime, role, scope and separation of duties", () => {
    for (const session of [
      null,
      { ...reviewer, expiresAt: new Date(now).toISOString() },
      { ...reviewer, scopes: [] },
      { ...reviewer, roles: ["VIEWER"] },
      { ...reviewer, human: false },
      { ...reviewer, principalId: requester.principalId },
    ]) {
      expect(
        canConfirmApproval(session, pending, pending, "approve", now),
      ).toBe(false);
    }
    const approved = { ...pending, status: "approved" };
    expect(
      canConfirmApproval(
        { ...requester, principalId: "other-requester" },
        approved,
        approved,
        "execute",
        now,
      ),
    ).toBe(false);
  });

  it("rejects malformed time and unknown decision values conservatively", () => {
    expect(
      canConfirmApproval(reviewer, pending, pending, "approve", Number.NaN),
    ).toBe(false);
    expect(
      canConfirmApproval(
        reviewer,
        { ...pending, expiresAt: "invalid" },
        pending,
        "approve",
        now,
      ),
    ).toBe(false);
    expect(
      canConfirmApproval(
        { ...reviewer, expiresAt: "invalid" },
        pending,
        pending,
        "approve",
        now,
      ),
    ).toBe(false);
    expect(
      canConfirmApproval(
        reviewer,
        pending,
        pending,
        "unknown" as ApprovalDecision,
        now,
      ),
    ).toBe(false);
  });
});
