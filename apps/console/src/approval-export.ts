import {
  ApprovalApiError,
  type Approval,
  type ApprovalSession,
} from "./approval-api.js";

export const EXPORT_MAX_BYTES = 32_768;
export const EXPORT_TIMEOUT_MS = 15_000;

export interface ApprovalReviewExportAssurance {
  signed: false;
  completeHistory: false;
  transactionalSnapshot: false;
  independentAnchor: false;
  authorizesExecution: false;
  argumentsIncluded: false;
}

export interface ApprovalReviewExportApproval {
  mode: "simulated";
  approvalRequestId: string;
  requestHash: string;
  requesterId: string;
  reviewerId?: string;
  status:
    "pending" | "approved" | "denied" | "expired" | "consumed" | "cancelled";
  createdAt: string;
  expiresAt: string;
  expired: boolean;
  policyId: string;
  policyVersion: number;
  originalPolicyOutcome: "require_approval";
  executionStatus:
    "not_executed" | "executing" | "succeeded" | "failed" | "unknown";
  toolName: string;
}

export interface ApprovalReviewExportPayload {
  generatedAt: string;
  mode: "simulated";
  assurance: ApprovalReviewExportAssurance;
  approval: ApprovalReviewExportApproval;
}

export interface ApprovalReviewExport {
  schemaVersion: "1.0";
  kind: "tracepermit-approval-review";
  canonicalization: "tracepermit-canonical-json-v1";
  payload: ApprovalReviewExportPayload;
  payloadHash: string;
}

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function hasExactKeys(
  obj: Record<string, unknown>,
  required: string[],
  optional: string[] = [],
): boolean {
  const keys = Object.keys(obj);
  for (const req of required) {
    if (!Object.prototype.hasOwnProperty.call(obj, req)) return false;
  }
  for (const k of keys) {
    if (!required.includes(k) && !optional.includes(k)) return false;
  }
  return true;
}

const HASH_REGEX = /^sha256:[a-f0-9]{64}$/;
const PRINCIPAL_REGEX = /^principal:[a-f0-9]{64}$/;
const UUID_REGEX = /^[a-f0-9-]{36}$/;
const TOOL_NAME_REGEX = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,255}$/;

const ROOT_REQUIRED_KEYS = [
  "canonicalization",
  "kind",
  "payload",
  "payloadHash",
  "schemaVersion",
];
const PAYLOAD_REQUIRED_KEYS = ["approval", "assurance", "generatedAt", "mode"];
const ASSURANCE_REQUIRED_KEYS = [
  "argumentsIncluded",
  "authorizesExecution",
  "completeHistory",
  "independentAnchor",
  "signed",
  "transactionalSnapshot",
];
const APPROVAL_REQUIRED_KEYS = [
  "approvalRequestId",
  "createdAt",
  "executionStatus",
  "expired",
  "expiresAt",
  "mode",
  "originalPolicyOutcome",
  "policyId",
  "policyVersion",
  "requestHash",
  "requesterId",
  "status",
  "toolName",
];

export function validateApprovalReviewExport(
  value: unknown,
  expected?: {
    approvalRequestId?: string | undefined;
    requestHash?: string | undefined;
  },
): ApprovalReviewExport {
  if (!record(value)) throw new ApprovalApiError(502);

  // Root closed envelope check
  if (!hasExactKeys(value, ROOT_REQUIRED_KEYS)) throw new ApprovalApiError(502);
  if (value.schemaVersion !== "1.0") throw new ApprovalApiError(502);
  if (value.kind !== "tracepermit-approval-review")
    throw new ApprovalApiError(502);
  if (value.canonicalization !== "tracepermit-canonical-json-v1")
    throw new ApprovalApiError(502);
  if (
    typeof value.payloadHash !== "string" ||
    !HASH_REGEX.test(value.payloadHash)
  ) {
    throw new ApprovalApiError(502);
  }

  // Payload closed shape check
  if (
    !record(value.payload) ||
    !hasExactKeys(value.payload, PAYLOAD_REQUIRED_KEYS)
  ) {
    throw new ApprovalApiError(502);
  }
  const payload = value.payload;
  if (payload.mode !== "simulated") throw new ApprovalApiError(502);
  if (
    typeof payload.generatedAt !== "string" ||
    payload.generatedAt.length > 40 ||
    !Number.isFinite(Date.parse(payload.generatedAt))
  ) {
    throw new ApprovalApiError(502);
  }

  // Assurance closed shape and false invariant check
  if (
    !record(payload.assurance) ||
    !hasExactKeys(payload.assurance, ASSURANCE_REQUIRED_KEYS)
  ) {
    throw new ApprovalApiError(502);
  }
  const assurance = payload.assurance;
  if (
    assurance.signed !== false ||
    assurance.completeHistory !== false ||
    assurance.transactionalSnapshot !== false ||
    assurance.independentAnchor !== false ||
    assurance.authorizesExecution !== false ||
    assurance.argumentsIncluded !== false
  ) {
    throw new ApprovalApiError(502);
  }

  // Approval closed shape and field checks
  if (
    !record(payload.approval) ||
    !hasExactKeys(payload.approval, APPROVAL_REQUIRED_KEYS, ["reviewerId"])
  ) {
    throw new ApprovalApiError(502);
  }
  const app = payload.approval;
  if (app.mode !== "simulated") throw new ApprovalApiError(502);

  if (
    typeof app.approvalRequestId !== "string" ||
    !UUID_REGEX.test(app.approvalRequestId)
  ) {
    throw new ApprovalApiError(502);
  }
  if (
    expected?.approvalRequestId &&
    app.approvalRequestId !== expected.approvalRequestId
  ) {
    throw new ApprovalApiError(502);
  }

  if (
    typeof app.requestHash !== "string" ||
    !HASH_REGEX.test(app.requestHash)
  ) {
    throw new ApprovalApiError(502);
  }
  if (expected?.requestHash && app.requestHash !== expected.requestHash) {
    throw new ApprovalApiError(502);
  }

  if (
    typeof app.requesterId !== "string" ||
    !PRINCIPAL_REGEX.test(app.requesterId)
  ) {
    throw new ApprovalApiError(502);
  }

  if (
    app.reviewerId !== undefined &&
    (typeof app.reviewerId !== "string" ||
      !PRINCIPAL_REGEX.test(app.reviewerId))
  ) {
    throw new ApprovalApiError(502);
  }

  if (
    typeof app.status !== "string" ||
    ![
      "pending",
      "approved",
      "denied",
      "expired",
      "consumed",
      "cancelled",
    ].includes(app.status)
  ) {
    throw new ApprovalApiError(502);
  }

  if (
    typeof app.createdAt !== "string" ||
    app.createdAt.length > 40 ||
    !Number.isFinite(Date.parse(app.createdAt))
  ) {
    throw new ApprovalApiError(502);
  }

  if (
    typeof app.expiresAt !== "string" ||
    app.expiresAt.length > 40 ||
    !Number.isFinite(Date.parse(app.expiresAt))
  ) {
    throw new ApprovalApiError(502);
  }

  if (typeof app.expired !== "boolean") throw new ApprovalApiError(502);

  if (
    typeof app.policyId !== "string" ||
    app.policyId.length < 1 ||
    app.policyId.length > 256
  ) {
    throw new ApprovalApiError(502);
  }

  if (
    !Number.isSafeInteger(app.policyVersion) ||
    Number(app.policyVersion) < 1 ||
    Number(app.policyVersion) > 2147483647
  ) {
    throw new ApprovalApiError(502);
  }

  if (app.originalPolicyOutcome !== "require_approval")
    throw new ApprovalApiError(502);

  if (
    typeof app.executionStatus !== "string" ||
    !["not_executed", "executing", "succeeded", "failed", "unknown"].includes(
      app.executionStatus,
    )
  ) {
    throw new ApprovalApiError(502);
  }

  if (typeof app.toolName !== "string" || !TOOL_NAME_REGEX.test(app.toolName)) {
    throw new ApprovalApiError(502);
  }

  return value as unknown as ApprovalReviewExport;
}

export function canDownloadReviewExport(
  session: ApprovalSession | null,
  approval: Approval | undefined,
  now = Date.now(),
): boolean {
  if (!session || !approval) return false;
  const sessionExpiry = Date.parse(session.expiresAt);
  if (!Number.isFinite(sessionExpiry) || sessionExpiry <= now) return false;
  if (
    !session.scopes.includes("approvals.read") &&
    !session.roles.some((r) => ["REQUESTER", "REVIEWER", "VIEWER"].includes(r))
  ) {
    return false;
  }
  return true;
}

export interface InFlightExportContext {
  generation: number;
  authority: string;
  approvalRequestId: string;
  requestHash: string;
  sessionExpiresAt: number;
}

export interface CurrentExportState {
  generation: number;
  session: ApprovalSession | null;
  selectedId: string | null;
  currentHash: string | undefined;
  now?: number;
}

export function isExportResponseValid(
  inFlight: InFlightExportContext,
  current: CurrentExportState,
): boolean {
  if (inFlight.generation !== current.generation) return false;
  if (!current.session) return false;
  const now = current.now ?? Date.now();
  if (now >= inFlight.sessionExpiresAt) return false;
  if (current.selectedId !== inFlight.approvalRequestId) return false;
  if (current.currentHash !== inFlight.requestHash) return false;
  const sessionAuth = `${current.session.principalId}:${current.session.tenantId}:${current.session.roles
    .slice()
    .sort()
    .join(",")}:${current.session.scopes.slice().sort().join(",")}`;
  if (sessionAuth !== inFlight.authority) return false;
  return true;
}

export function exportFilename(approvalRequestId: string): string {
  if (!UUID_REGEX.test(approvalRequestId)) throw new ApprovalApiError(502);
  return `tracepermit-approval-${approvalRequestId}.json`;
}

export async function fetchApprovalReviewExport(
  approvalRequestId: string,
  expectedHash?: string,
  signal?: AbortSignal,
): Promise<ApprovalReviewExport> {
  if (!UUID_REGEX.test(approvalRequestId)) throw new ApprovalApiError(400);

  const path = `/browser/api/v1/approvals/${encodeURIComponent(approvalRequestId)}/export`;
  const timeoutSignal = AbortSignal.timeout(EXPORT_TIMEOUT_MS);
  const combinedSignal = signal
    ? AbortSignal.any([signal, timeoutSignal])
    : timeoutSignal;

  const response = await fetch(path, {
    method: "GET",
    credentials: "same-origin",
    cache: "no-store",
    redirect: "error",
    signal: combinedSignal,
  });

  if (!response.ok) {
    throw new ApprovalApiError(response.status);
  }

  if (!response.body) {
    throw new ApprovalApiError(502);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder("utf-8", { fatal: true });
  let totalBytes = 0;
  let text = "";

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      totalBytes += value.byteLength;
      if (totalBytes > EXPORT_MAX_BYTES) {
        try {
          await reader.cancel("EXPORT_TOO_LARGE");
        } catch {
          // Ignore stream cancel error
        }
        throw new ApprovalApiError(502);
      }
      text += decoder.decode(value, { stream: true });
    }
    text += decoder.decode();
  } catch (failure) {
    if (failure instanceof ApprovalApiError) throw failure;
    throw new ApprovalApiError(502);
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    throw new ApprovalApiError(502);
  }

  return validateApprovalReviewExport(parsed, {
    approvalRequestId,
    requestHash: expectedHash,
  });
}

export function triggerDownload(
  blob: Blob,
  filename: string,
  hooks?: {
    createObjectURL?: (b: Blob) => string;
    revokeObjectURL?: (u: string) => void;
    appendAndClick?: (anchor: {
      href: string;
      download: string;
      click?: () => void;
    }) => void;
  },
): () => void {
  const createUrl =
    hooks?.createObjectURL ?? ((b: Blob) => URL.createObjectURL(b));
  const revokeUrl =
    hooks?.revokeObjectURL ?? ((u: string) => URL.revokeObjectURL(u));
  const url = createUrl(blob);

  let revoked = false;
  const revoke = () => {
    if (!revoked) {
      revoked = true;
      try {
        revokeUrl(url);
      } catch {
        // Safe revoke ignore
      }
    }
  };

  try {
    if (hooks?.appendAndClick) {
      hooks.appendAndClick({ href: url, download: filename });
    } else if (typeof document !== "undefined") {
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = filename;
      anchor.rel = "noopener noreferrer";
      anchor.style.display = "none";
      document.body.appendChild(anchor);
      anchor.click();
      document.body.removeChild(anchor);
    }
  } catch (err) {
    revoke();
    throw err;
  }

  // Revoke promptly after browser initiates download
  setTimeout(revoke, 100);
  return revoke;
}

export function downloadApprovalReviewExport(
  record: ApprovalReviewExport,
  hooks?: {
    createObjectURL?: (b: Blob) => string;
    revokeObjectURL?: (u: string) => void;
    appendAndClick?: (anchor: {
      href: string;
      download: string;
      click?: () => void;
    }) => void;
  },
): () => void {
  const filename = exportFilename(record.payload.approval.approvalRequestId);
  const jsonText = JSON.stringify(record, null, 2);
  const blob = new Blob([jsonText], { type: "application/json" });
  return triggerDownload(blob, filename, hooks);
}
