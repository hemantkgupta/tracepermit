import { afterEach, describe, expect, it, vi } from "vitest";
import {
  loadConsoleData,
  loadEvidenceIntegrity,
  loadLineage,
  loadPolicyVersion,
} from "./api.js";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("read-only console API", () => {
  it("loads every list projection concurrently using read requests only", async () => {
    const fetchMock = vi.fn(
      (input: string | URL | Request, _init?: RequestInit) =>
        Promise.resolve(jsonResponse(payloadFor(String(input)))),
    );
    vi.stubGlobal("fetch", fetchMock);

    const result = await loadConsoleData();

    expect(result.overview.total).toBe(8);
    expect(result.page.items).toEqual([]);
    expect(result.evidence.items).toEqual([]);
    expect(result.policies.items).toEqual([]);
    expect(result.lineages.items).toEqual([]);
    expect(fetchMock.mock.calls.map(([path]) => path)).toEqual([
      "/api/v1/overview",
      "/api/v1/actions?limit=50",
      "/api/v1/evidence-integrity",
      "/api/v1/policy-versions",
      "/api/v1/lineages",
    ]);
    for (const [, init] of fetchMock.mock.calls) {
      expect(init).not.toHaveProperty("method");
      expect(init).not.toHaveProperty("body");
      expect(init).not.toHaveProperty("Authorization");
    }
  });

  it("uses encoded identifiers for every detail endpoint", async () => {
    const fetchMock = vi.fn(
      (_input: string | URL | Request, _init?: RequestInit) =>
        Promise.resolve(jsonResponse({})),
    );
    vi.stubGlobal("fetch", fetchMock);

    await Promise.all([
      loadEvidenceIntegrity("018f47a2-f5ce-7a4b-8f54-80e5d3eaf092"),
      loadPolicyVersion("pol-aws-web-001:1"),
      loadLineage("9f3a2c1e4b5d67890123456789abcdef"),
    ]);

    expect(fetchMock.mock.calls.map(([path]) => path)).toEqual([
      "/api/v1/evidence-integrity/018f47a2-f5ce-7a4b-8f54-80e5d3eaf092",
      "/api/v1/policy-versions/pol-aws-web-001/1",
      "/api/v1/lineages/9f3a2c1e4b5d67890123456789abcdef",
    ]);
  });

  it("fails closed on a non-success response", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(() => Promise.resolve(jsonResponse({ error: {} }, 404))),
    );

    await expect(
      loadLineage("00000000000000000000000000000000"),
    ).rejects.toThrow("TracePermit API returned 404.");
  });
});

function payloadFor(path: string): object {
  if (path === "/api/v1/overview") {
    return {
      schemaVersion: "1.0",
      simulated: true,
      total: 8,
      allowed: 2,
      blocked: 3,
      approvalRequired: 3,
      p95LatencyMs: 0,
      latencySampleSize: 0,
      latencySampleScope: "current_run",
    };
  }
  return {
    schemaVersion: "1.0",
    simulated: true,
    total: 0,
    nextCursor: null,
    items: [],
  };
}

function jsonResponse(body: object, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}
