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
  createdAt: string;
  expiresAt: string;
  expired: boolean;
  policyId: string;
  policyVersion: number;
  originalPolicyOutcome: string;
  executionStatus: string;
  action: { toolName: string; target: unknown; arguments: unknown };
}

export interface ApprovalPage {
  mode: "simulated";
  items: Approval[];
  pageSize: number;
  nextCursor: string | null;
}

export type ApprovalStatusFilter =
  "pending" | "approved" | "denied" | "expired" | "consumed";

export interface ApprovalQuery {
  limit?: number;
  status?: ApprovalStatusFilter;
  toolName?: string;
  approvalRequestId?: string;
  cursor?: string;
}

export type ApprovalDecision = "approve" | "deny" | "execute";

export class ApprovalApiError extends Error {
  constructor(public readonly status: number) {
    super(
      status === 401
        ? "Your session has ended. Sign in again."
        : status === 403
          ? "This operation is not authorized. Refresh to check your current role."
          : status === 400
            ? "Invalid query or continuation. Reset filters to start a fresh search."
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
    signal: signal
      ? AbortSignal.any([signal, AbortSignal.timeout(15_000)])
      : AbortSignal.timeout(15_000),
  });
  if (!response.ok) throw new ApprovalApiError(response.status);
  try {
    const text = await response.text();
    if (text.length > 1_048_576) throw new ApprovalApiError(502);
    return JSON.parse(text) as T;
  } catch {
    throw new ApprovalApiError(502);
  }
}

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function text(value: unknown, max = 256): value is string {
  return typeof value === "string" && value.length > 0 && value.length <= max;
}

function timestamp(value: unknown): value is string {
  return text(value, 64) && Number.isFinite(Date.parse(value));
}

function strings(value: unknown): value is string[] {
  return (
    Array.isArray(value) && value.length <= 32 && value.every((v) => text(v))
  );
}

export function validateApproval(value: unknown): Approval {
  if (
    !record(value) ||
    value.mode !== "simulated" ||
    !["approvalRequestId", "requestHash", "requesterId", "policyId"].every(
      (k) => text(value[k]),
    ) ||
    (value.reviewerId !== undefined && !text(value.reviewerId)) ||
    !text(value.status) ||
    !["pending", "approved", "denied", "expired", "consumed"].includes(
      value.status,
    ) ||
    !timestamp(value.createdAt) ||
    !timestamp(value.expiresAt) ||
    typeof value.expired !== "boolean" ||
    !Number.isSafeInteger(value.policyVersion) ||
    Number(value.policyVersion) < 1 ||
    value.originalPolicyOutcome !== "require_approval" ||
    !text(value.executionStatus) ||
    !["not_executed", "claimed", "succeeded", "failed", "unknown"].includes(
      value.executionStatus,
    ) ||
    !record(value.action) ||
    !text(value.action.toolName) ||
    !record(value.action.target) ||
    !record(value.action.arguments)
  ) {
    throw new ApprovalApiError(502);
  }
  return value as unknown as Approval;
}

export async function loadApprovalSession(signal?: AbortSignal) {
  const value = await request<unknown>("/browser/session", undefined, signal);
  if (
    !record(value) ||
    value.mode !== "simulated" ||
    !text(value.principalId) ||
    !text(value.tenantId) ||
    typeof value.human !== "boolean" ||
    !strings(value.roles) ||
    !value.roles.every((r) =>
      ["REQUESTER", "REVIEWER", "VIEWER"].includes(r),
    ) ||
    !strings(value.scopes) ||
    !text(value.csrfToken, 512) ||
    !timestamp(value.expiresAt)
  ) {
    throw new ApprovalApiError(502);
  }
  return value as unknown as ApprovalSession;
}

export async function loadApprovalPage(
  query?: ApprovalQuery,
  signal?: AbortSignal,
): Promise<ApprovalPage> {
  const params = new URLSearchParams();
  if (query?.limit !== undefined && query.limit !== null) {
    params.set("limit", String(query.limit));
  }
  if (query?.status) {
    params.set("status", query.status);
  }
  if (query?.toolName && query.toolName.trim().length > 0) {
    params.set("toolName", query.toolName.trim());
  }
  if (query?.approvalRequestId && query.approvalRequestId.trim().length > 0) {
    params.set("approvalRequestId", query.approvalRequestId.trim());
  }
  if (query?.cursor && query.cursor.length > 0) {
    params.set("cursor", query.cursor);
  }
  const queryString = params.toString();
  const path = queryString.length > 0 ? `${root}?${queryString}` : root;
  const value = await request<unknown>(path, undefined, signal);
  if (
    !record(value) ||
    value.mode !== "simulated" ||
    !Number.isInteger(value.pageSize) ||
    Number(value.pageSize) < 1 ||
    Number(value.pageSize) > 50 ||
    !Array.isArray(value.items) ||
    value.items.length > Number(value.pageSize) ||
    !(value.nextCursor === null || text(value.nextCursor, 2048))
  ) {
    throw new ApprovalApiError(502);
  }
  value.items.forEach(validateApproval);
  return value as unknown as ApprovalPage;
}

export async function loadApprovals(signal?: AbortSignal) {
  const page = await loadApprovalPage(undefined, signal);
  return page.items;
}

export function canRequest(session: ApprovalSession) {
  return (
    session.roles.includes("REQUESTER") &&
    session.scopes.includes("approvals.request")
  );
}

export function canReview(
  session: ApprovalSession,
  approval: Approval,
  now = Date.now(),
) {
  return (
    session.human &&
    session.roles.includes("REVIEWER") &&
    session.scopes.includes("approvals.decide") &&
    session.principalId !== approval.requesterId &&
    approval.status === "pending" &&
    !isExpired(approval, now)
  );
}

export function canExecute(
  session: ApprovalSession,
  approval: Approval,
  now = Date.now(),
) {
  return (
    session.roles.includes("REQUESTER") &&
    session.scopes.includes("approvals.execute") &&
    session.principalId === approval.requesterId &&
    approval.status === "approved" &&
    approval.executionStatus === "not_executed" &&
    !isExpired(approval, now)
  );
}

export function isExpired(approval: Approval, now = Date.now()) {
  const expiry = Date.parse(approval.expiresAt);
  return (
    approval.expired ||
    !Number.isFinite(expiry) ||
    !Number.isFinite(now) ||
    expiry <= now
  );
}

// A client-side usability guard, never a substitute for Java authorization.
export function canConfirmApproval(
  session: ApprovalSession | null,
  current: Approval | undefined,
  snapshot: Approval,
  decision: ApprovalDecision,
  now = Date.now(),
) {
  return Boolean(
    session &&
    current &&
    ["approve", "deny", "execute"].includes(decision) &&
    Date.parse(session.expiresAt) > now &&
    current.approvalRequestId === snapshot.approvalRequestId &&
    current.requestHash === snapshot.requestHash &&
    (decision === "execute"
      ? canExecute(session, current, now)
      : canReview(session, current, now)),
  );
}

export function submitApproval(
  session: ApprovalSession,
  value: { groupId: string; port: number; cidr: string },
) {
  return mutate<unknown>(root, session, value).then(validateApproval);
}

export function decideApproval(
  session: ApprovalSession,
  approval: Approval,
  decision: ApprovalDecision,
) {
  return mutate<unknown>(
    `${root}/${encodeURIComponent(approval.approvalRequestId)}/${decision === "execute" ? "execute" : "decision"}`,
    session,
    decision === "execute"
      ? { requestHash: approval.requestHash }
      : { requestHash: approval.requestHash, decision },
  ).then(validateApproval);
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
