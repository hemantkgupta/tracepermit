import type {
  ActionFeedOverview,
  ActionFeedPage,
  EvidenceIntegrityDetail,
  EvidenceIntegrityPage,
  LineageDetail,
  LineagePage,
  PolicyVersionDetail,
  PolicyVersionPage,
} from "./types.js";

async function readJson<T>(path: string, signal?: AbortSignal): Promise<T> {
  const init: RequestInit = { headers: { Accept: "application/json" } };
  if (signal) init.signal = signal;
  const response = await fetch(path, init);
  if (!response.ok) {
    throw new Error(`TracePermit API returned ${response.status}.`);
  }
  return (await response.json()) as T;
}

export async function loadConsoleData(signal?: AbortSignal): Promise<{
  overview: ActionFeedOverview;
  page: ActionFeedPage;
  evidence: EvidenceIntegrityPage;
  policies: PolicyVersionPage;
  lineages: LineagePage;
}> {
  const [overview, page, evidence, policies, lineages] = await Promise.all([
    readJson<ActionFeedOverview>("/api/v1/overview", signal),
    readJson<ActionFeedPage>("/api/v1/actions?limit=50", signal),
    readJson<EvidenceIntegrityPage>("/api/v1/evidence-integrity", signal),
    readJson<PolicyVersionPage>("/api/v1/policy-versions", signal),
    readJson<LineagePage>("/api/v1/lineages", signal),
  ]);
  return { overview, page, evidence, policies, lineages };
}

export function loadEvidenceIntegrity(
  eventId: string,
  signal?: AbortSignal,
): Promise<EvidenceIntegrityDetail> {
  return readJson<EvidenceIntegrityDetail>(
    `/api/v1/evidence-integrity/${encodeURIComponent(eventId)}`,
    signal,
  );
}

export function loadPolicyVersion(
  key: string,
  signal?: AbortSignal,
): Promise<PolicyVersionDetail> {
  const separator = key.lastIndexOf(":");
  const policyId = key.slice(0, separator);
  const version = key.slice(separator + 1);
  return readJson<PolicyVersionDetail>(
    `/api/v1/policy-versions/${encodeURIComponent(policyId)}/${encodeURIComponent(version)}`,
    signal,
  );
}

export function loadLineage(
  traceId: string,
  signal?: AbortSignal,
): Promise<LineageDetail> {
  return readJson<LineageDetail>(
    `/api/v1/lineages/${encodeURIComponent(traceId)}`,
    signal,
  );
}
