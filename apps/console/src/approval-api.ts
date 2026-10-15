export interface ApprovalSession {
  mode: "simulated";
  principalId: string;
  tenantId: string;
  human: boolean;
  roles: string[];
  scopes: string[];
  csrfToken: string;
  expiresAt: string;
}

export interface Approval {
  mode: "simulated";
  approvalRequestId: string;
  requestHash: string;
  requesterId: string;
  reviewerId?: string;
  status: string;
  expiresAt: string;
  expired: boolean;
  policyId: string;
  policyVersion: number;
  originalPolicyOutcome: string;
  executionStatus: string;
  action: { toolName: string; target: unknown; arguments: unknown };
}

export class ApprovalApiError extends Error {
  constructor(public readonly status: number) {
    super(
      status === 401
        ? "Your session has ended. Sign in again."
        : status === 403
          ? "This operation is not authorized. Refresh to check your current role."
          : status === 409
            ? "The request changed, expired or was already handled. Refresh before continuing; nothing will be retried automatically."
            : "The approval service could not complete this request. Check its state before retrying.",
    );
  }
}

const root = "/browser/api/v1/approvals";

async function request<T>(
  path: string,
  init?: RequestInit,
  signal?: AbortSignal,
): Promise<T> {
  const response = await fetch(path, {
    ...init,
    credentials: "same-origin",
    cache: "no-store",
    redirect: "error",
    signal: signal ?? null,
  });
  if (!response.ok) throw new ApprovalApiError(response.status);
  return response.json() as Promise<T>;
}

export function loadApprovalSession(signal?: AbortSignal) {
  return request<ApprovalSession>("/browser/session", undefined, signal);
}

export async function loadApprovals(signal?: AbortSignal) {
  const page = await request<{ mode: "simulated"; items: Approval[] }>(
    root,
    undefined,
    signal,
  );
  return page.items;
}

export function canRequest(session: ApprovalSession) {
  return (
    session.roles.includes("REQUESTER") &&
    session.scopes.includes("approvals.request")
  );
}

export function canReview(session: ApprovalSession, approval: Approval) {
  return (
    session.human &&
    session.roles.includes("REVIEWER") &&
    session.scopes.includes("approvals.decide") &&
    session.principalId !== approval.requesterId &&
    approval.status === "pending" &&
    !isExpired(approval)
  );
}

export function canExecute(session: ApprovalSession, approval: Approval) {
  return (
    session.roles.includes("REQUESTER") &&
    session.scopes.includes("approvals.execute") &&
    session.principalId === approval.requesterId &&
    approval.status === "approved" &&
    approval.executionStatus === "not_executed" &&
    !isExpired(approval)
  );
}

export function isExpired(approval: Approval) {
  return approval.expired || Date.parse(approval.expiresAt) <= Date.now();
}

export function submitApproval(
  session: ApprovalSession,
  value: { groupId: string; port: number; cidr: string },
) {
  return mutate<Approval>(root, session, value);
}

export function decideApproval(
  session: ApprovalSession,
  approval: Approval,
  decision: "approve" | "deny" | "execute",
) {
  return mutate<Approval>(
    `${root}/${encodeURIComponent(approval.approvalRequestId)}/${decision === "execute" ? "execute" : "decision"}`,
    session,
    decision === "execute"
      ? { requestHash: approval.requestHash }
      : { requestHash: approval.requestHash, decision },
  );
}

export function signOut(session: ApprovalSession) {
  return mutate<{ signedOut: boolean }>("/browser/logout", session, {});
}

function mutate<T>(path: string, session: ApprovalSession, body: object) {
  return request<T>(path, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-CSRF-Token": session.csrfToken,
    },
    body: JSON.stringify(body),
  });
}
