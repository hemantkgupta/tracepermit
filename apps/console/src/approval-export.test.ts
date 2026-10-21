import { describe, expect, it, vi } from "vitest";
import {
  ApprovalApiError,
  type Approval,
  type ApprovalSession,
} from "./approval-api.js";
import {
  ApprovalReviewExport,
  canDownloadReviewExport,
  downloadApprovalReviewExport,
  EXPORT_MAX_BYTES,
  exportFilename,
  fetchApprovalReviewExport,
  isExportResponseValid,
  triggerDownload,
  validateApprovalReviewExport,
} from "./approval-export.js";

const GOLDEN_RECORD: ApprovalReviewExport = {
  schemaVersion: "1.0",
  kind: "tracepermit-approval-review",
  canonicalization: "tracepermit-canonical-json-v1",
  payload: {
    generatedAt: "2026-10-21T12:00:00Z",
    mode: "simulated",
    assurance: {
      signed: false,
      completeHistory: false,
      transactionalSnapshot: false,
      independentAnchor: false,
      authorizesExecution: false,
      argumentsIncluded: false,
    },
    approval: {
      mode: "simulated",
      approvalRequestId: "00000000-0000-0000-0000-000000000001",
      requestHash:
        "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      requesterId:
        "principal:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      status: "pending",
      createdAt: "2026-10-21T11:00:00Z",
      expiresAt: "2026-10-21T13:00:00Z",
      expired: false,
      policyId: "pol-local-simulator-approval",
      policyVersion: 2,
      originalPolicyOutcome: "require_approval",
      executionStatus: "not_executed",
      toolName: "aws.ec2.update_security_group",
    },
  },
  payloadHash:
    "sha256:b906a41995ff346f31fe1a94331e60a44afb72c381d3343bc014602ac4742dae",
};

const SAMPLE_SESSION: ApprovalSession = {
  mode: "simulated",
  principalId:
    "principal:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  tenantId: "tenant:local",
  human: true,
  roles: ["REQUESTER", "REVIEWER"],
  scopes: ["approvals.read", "approvals.request", "approvals.decide"],
  csrfToken: "csrf-token-12345",
  expiresAt: new Date(Date.now() + 3600_000).toISOString(),
};

const SAMPLE_APPROVAL: Approval = {
  mode: "simulated",
  approvalRequestId: "00000000-0000-0000-0000-000000000001",
  requestHash:
    "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  requesterId:
    "principal:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  status: "pending",
  createdAt: "2026-10-21T11:00:00Z",
  expiresAt: new Date(Date.now() + 3600_000).toISOString(),
  expired: false,
  policyId: "pol-local-simulator-approval",
  policyVersion: 2,
  originalPolicyOutcome: "require_approval",
  executionStatus: "not_executed",
  action: {
    toolName: "aws.ec2.update_security_group",
    target: { type: "security_group", id: "sg-123" },
    arguments: { port: 443 },
  },
};

describe("validateApprovalReviewExport", () => {
  it("validates a golden approval review export record", () => {
    const validated = validateApprovalReviewExport(GOLDEN_RECORD);
    expect(validated.schemaVersion).toBe("1.0");
    expect(validated.kind).toBe("tracepermit-approval-review");
    expect(validated.payload.approval.toolName).toBe(
      "aws.ec2.update_security_group",
    );
  });

  it("validates when expected ID and hash match", () => {
    const validated = validateApprovalReviewExport(GOLDEN_RECORD, {
      approvalRequestId: "00000000-0000-0000-0000-000000000001",
      requestHash:
        "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    });
    expect(validated.payload.approval.approvalRequestId).toBe(
      "00000000-0000-0000-0000-000000000001",
    );
  });

  it("rejects when expected approvalRequestId mismatches", () => {
    expect(() =>
      validateApprovalReviewExport(GOLDEN_RECORD, {
        approvalRequestId: "00000000-0000-0000-0000-000000000002",
      }),
    ).toThrow(ApprovalApiError);
  });

  it("rejects when expected requestHash mismatches", () => {
    expect(() =>
      validateApprovalReviewExport(GOLDEN_RECORD, {
        requestHash:
          "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
      }),
    ).toThrow(ApprovalApiError);
  });

  it("rejects non-object input", () => {
    expect(() => validateApprovalReviewExport(null)).toThrow(ApprovalApiError);
    expect(() => validateApprovalReviewExport("string")).toThrow(
      ApprovalApiError,
    );
    expect(() => validateApprovalReviewExport([])).toThrow(ApprovalApiError);
  });

  it("rejects wrong schemaVersion", () => {
    const clone = JSON.parse(JSON.stringify(GOLDEN_RECORD));
    clone.schemaVersion = "2.0";
    expect(() => validateApprovalReviewExport(clone)).toThrow(ApprovalApiError);
  });

  it("rejects wrong kind", () => {
    const clone = JSON.parse(JSON.stringify(GOLDEN_RECORD));
    clone.kind = "tracepermit-action";
    expect(() => validateApprovalReviewExport(clone)).toThrow(ApprovalApiError);
  });

  it("rejects wrong canonicalization profile", () => {
    const clone = JSON.parse(JSON.stringify(GOLDEN_RECORD));
    clone.canonicalization = "json-canonical";
    expect(() => validateApprovalReviewExport(clone)).toThrow(ApprovalApiError);
  });

  it("rejects invalid payloadHash format", () => {
    const clone = JSON.parse(JSON.stringify(GOLDEN_RECORD));
    clone.payloadHash = "not-a-hash";
    expect(() => validateApprovalReviewExport(clone)).toThrow(ApprovalApiError);
  });

  it("rejects extra fields on root envelope (closed shape)", () => {
    const clone = JSON.parse(JSON.stringify(GOLDEN_RECORD));
    clone.extra = "unexpected";
    expect(() => validateApprovalReviewExport(clone)).toThrow(ApprovalApiError);
  });

  it("rejects non-simulated payload mode", () => {
    const clone = JSON.parse(JSON.stringify(GOLDEN_RECORD));
    clone.payload.mode = "production";
    expect(() => validateApprovalReviewExport(clone)).toThrow(ApprovalApiError);
  });

  it("rejects invalid generatedAt timestamp", () => {
    const clone = JSON.parse(JSON.stringify(GOLDEN_RECORD));
    clone.payload.generatedAt = "not-a-date";
    expect(() => validateApprovalReviewExport(clone)).toThrow(ApprovalApiError);
  });

  it("rejects any true assurance flag", () => {
    const flags = [
      "signed",
      "completeHistory",
      "transactionalSnapshot",
      "independentAnchor",
      "authorizesExecution",
      "argumentsIncluded",
    ] as const;

    for (const flag of flags) {
      const clone = JSON.parse(JSON.stringify(GOLDEN_RECORD));
      clone.payload.assurance[flag] = true;
      expect(() => validateApprovalReviewExport(clone)).toThrow(
        ApprovalApiError,
      );
    }
  });

  it("rejects extra assurance properties", () => {
    const clone = JSON.parse(JSON.stringify(GOLDEN_RECORD));
    clone.payload.assurance.verifiedByAuthority = false;
    expect(() => validateApprovalReviewExport(clone)).toThrow(ApprovalApiError);
  });

  it("rejects non-simulated approval mode", () => {
    const clone = JSON.parse(JSON.stringify(GOLDEN_RECORD));
    clone.payload.approval.mode = "live";
    expect(() => validateApprovalReviewExport(clone)).toThrow(ApprovalApiError);
  });

  it("rejects invalid approval status", () => {
    const clone = JSON.parse(JSON.stringify(GOLDEN_RECORD));
    clone.payload.approval.status = "in_progress";
    expect(() => validateApprovalReviewExport(clone)).toThrow(ApprovalApiError);
  });

  it("accepts valid optional reviewerId", () => {
    const clone = JSON.parse(JSON.stringify(GOLDEN_RECORD));
    clone.payload.approval.reviewerId =
      "principal:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    const validated = validateApprovalReviewExport(clone);
    expect(validated.payload.approval.reviewerId).toBe(
      "principal:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
    );
  });

  it("rejects malformed reviewerId", () => {
    const clone = JSON.parse(JSON.stringify(GOLDEN_RECORD));
    clone.payload.approval.reviewerId = "bad-reviewer";
    expect(() => validateApprovalReviewExport(clone)).toThrow(ApprovalApiError);
  });

  it("rejects malformed toolName pattern", () => {
    const clone = JSON.parse(JSON.stringify(GOLDEN_RECORD));
    clone.payload.approval.toolName = "_invalid";
    expect(() => validateApprovalReviewExport(clone)).toThrow(ApprovalApiError);
  });

  it("rejects non-positive policyVersion", () => {
    const clone = JSON.parse(JSON.stringify(GOLDEN_RECORD));
    clone.payload.approval.policyVersion = 0;
    expect(() => validateApprovalReviewExport(clone)).toThrow(ApprovalApiError);
  });

  it("rejects extra fields on approval object", () => {
    const clone = JSON.parse(JSON.stringify(GOLDEN_RECORD));
    clone.payload.approval.actionArguments = { secret: "leak" };
    expect(() => validateApprovalReviewExport(clone)).toThrow(ApprovalApiError);
  });
});

describe("canDownloadReviewExport", () => {
  it("allows download with valid active session and approval", () => {
    expect(canDownloadReviewExport(SAMPLE_SESSION, SAMPLE_APPROVAL)).toBe(true);
  });

  it("disallows download when session is null", () => {
    expect(canDownloadReviewExport(null, SAMPLE_APPROVAL)).toBe(false);
  });

  it("disallows download when approval is undefined", () => {
    expect(canDownloadReviewExport(SAMPLE_SESSION, undefined)).toBe(false);
  });

  it("disallows download when session is expired", () => {
    const expiredSession: ApprovalSession = {
      ...SAMPLE_SESSION,
      expiresAt: new Date(Date.now() - 1000).toISOString(),
    };
    expect(canDownloadReviewExport(expiredSession, SAMPLE_APPROVAL)).toBe(
      false,
    );
  });

  it("disallows download when session has no read scope or role", () => {
    const noReadSession: ApprovalSession = {
      ...SAMPLE_SESSION,
      roles: [],
      scopes: ["unrelated.scope"],
    };
    expect(canDownloadReviewExport(noReadSession, SAMPLE_APPROVAL)).toBe(false);
  });
});

describe("exportFilename", () => {
  it("derives safe fixed filename from valid UUID", () => {
    expect(exportFilename("00000000-0000-0000-0000-000000000001")).toBe(
      "tracepermit-approval-00000000-0000-0000-0000-000000000001.json",
    );
  });

  it("rejects non-UUID identifiers", () => {
    expect(() => exportFilename("../../etc/passwd")).toThrow(ApprovalApiError);
  });
});

describe("fetchApprovalReviewExport", () => {
  it("fetches and validates export successfully", async () => {
    const jsonStr = JSON.stringify(GOLDEN_RECORD);
    const mockResponse = new Response(jsonStr, {
      status: 200,
      headers: { "Content-Type": "application/json" },
    });

    const originalFetch = globalThis.fetch;
    globalThis.fetch = vi.fn().mockResolvedValue(mockResponse);

    try {
      const result = await fetchApprovalReviewExport(
        "00000000-0000-0000-0000-000000000001",
        "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      );
      expect(result.schemaVersion).toBe("1.0");
      expect(result.payload.approval.approvalRequestId).toBe(
        "00000000-0000-0000-0000-000000000001",
      );
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  it("aborts and rejects oversized response exceeding 32,768 bytes", async () => {
    // Construct stream exceeding 32KB
    const chunk = new Uint8Array(1024).fill(65);
    let chunksSent = 0;
    const stream = new ReadableStream({
      pull(controller) {
        if (chunksSent < 35) {
          chunksSent++;
          controller.enqueue(chunk);
        } else {
          controller.close();
        }
      },
    });

    const mockResponse = new Response(stream, {
      status: 200,
      headers: { "Content-Type": "application/json" },
    });

    const originalFetch = globalThis.fetch;
    globalThis.fetch = vi.fn().mockResolvedValue(mockResponse);

    try {
      await expect(
        fetchApprovalReviewExport("00000000-0000-0000-0000-000000000001"),
      ).rejects.toThrow(ApprovalApiError);
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  it("propagates HTTP 401, 403, 404, 503 errors as ApprovalApiError", async () => {
    const originalFetch = globalThis.fetch;
    for (const status of [401, 403, 404, 503]) {
      globalThis.fetch = vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ error: { code: "ERROR" } }), {
          status,
        }),
      );
      try {
        await expect(
          fetchApprovalReviewExport("00000000-0000-0000-0000-000000000001"),
        ).rejects.toThrow(ApprovalApiError);
      } finally {
        globalThis.fetch = originalFetch;
      }
    }
  });

  it("rejects malformed JSON body with 502", async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = vi
      .fn()
      .mockResolvedValue(new Response("not valid json {", { status: 200 }));
    try {
      await expect(
        fetchApprovalReviewExport("00000000-0000-0000-0000-000000000001"),
      ).rejects.toThrow(ApprovalApiError);
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  it("rejects malformed approvalRequestId without making network request", async () => {
    const fetchSpy = vi.fn();
    const originalFetch = globalThis.fetch;
    globalThis.fetch = fetchSpy;
    try {
      await expect(fetchApprovalReviewExport("invalid-id")).rejects.toThrow(
        ApprovalApiError,
      );
      expect(fetchSpy).not.toHaveBeenCalled();
    } finally {
      globalThis.fetch = originalFetch;
    }
  });
});

describe("isExportResponseValid (in-flight race and invalidation guards)", () => {
  const baseInFlight = {
    generation: 1,
    authority:
      "principal:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa:tenant:local:REQUESTER,REVIEWER:approvals.decide,approvals.read,approvals.request",
    approvalRequestId: "00000000-0000-0000-0000-000000000001",
    requestHash:
      "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    sessionExpiresAt: Date.now() + 60_000,
  };

  const baseCurrent = {
    generation: 1,
    session: SAMPLE_SESSION,
    selectedId: "00000000-0000-0000-0000-000000000001",
    currentHash:
      "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    now: Date.now(),
  };

  it("validates when all in-flight conditions match current state", () => {
    expect(isExportResponseValid(baseInFlight, baseCurrent)).toBe(true);
  });

  it("invalidates late response after logout (current session null)", () => {
    expect(
      isExportResponseValid(baseInFlight, {
        ...baseCurrent,
        session: null,
      }),
    ).toBe(false);
  });

  it("invalidates late response after query/filter generation advance", () => {
    expect(
      isExportResponseValid(baseInFlight, {
        ...baseCurrent,
        generation: 2,
      }),
    ).toBe(false);
  });

  it("invalidates late response after selection changes to another request", () => {
    expect(
      isExportResponseValid(baseInFlight, {
        ...baseCurrent,
        selectedId: "00000000-0000-0000-0000-000000000002",
      }),
    ).toBe(false);
  });

  it("invalidates late response if selection cleared", () => {
    expect(
      isExportResponseValid(baseInFlight, {
        ...baseCurrent,
        selectedId: null,
      }),
    ).toBe(false);
  });

  it("invalidates late response if request hash mutated", () => {
    expect(
      isExportResponseValid(baseInFlight, {
        ...baseCurrent,
        currentHash:
          "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
      }),
    ).toBe(false);
  });

  it("invalidates late response if session authority/roles changed", () => {
    const alteredSession: ApprovalSession = {
      ...SAMPLE_SESSION,
      roles: ["VIEWER"],
    };
    expect(
      isExportResponseValid(baseInFlight, {
        ...baseCurrent,
        session: alteredSession,
      }),
    ).toBe(false);
  });

  it("invalidates late response if session expired before arrival", () => {
    expect(
      isExportResponseValid(baseInFlight, {
        ...baseCurrent,
        now: baseInFlight.sessionExpiresAt + 1000,
      }),
    ).toBe(false);
  });
});

describe("downloadApprovalReviewExport and triggerDownload", () => {
  it("creates blob and revokes object URL in prompt cleanup", async () => {
    let createdUrl = "";
    let revokedUrl = "";
    let clickedDownloadName = "";

    const revoke = downloadApprovalReviewExport(GOLDEN_RECORD, {
      createObjectURL: () => {
        createdUrl = "blob:http://127.0.0.1/mock-blob-uuid";
        return createdUrl;
      },
      revokeObjectURL: (url) => {
        revokedUrl = url;
      },
      appendAndClick: (anchor) => {
        clickedDownloadName = anchor.download;
      },
    });

    expect(clickedDownloadName).toBe(
      "tracepermit-approval-00000000-0000-0000-0000-000000000001.json",
    );
    expect(createdUrl).toBe("blob:http://127.0.0.1/mock-blob-uuid");

    // Explicit manual invocation revokes promptly
    revoke();
    expect(revokedUrl).toBe("blob:http://127.0.0.1/mock-blob-uuid");
  });
});
