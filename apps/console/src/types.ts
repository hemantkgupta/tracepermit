export type DecisionOutcome =
  "allow" | "block" | "sanitize" | "require_approval";
export type Severity = "low" | "medium" | "high" | "critical";

export interface PrincipalView {
  id: string;
  displayName: string;
  type: string;
  assurance: string;
}

export interface ActionFeedEntry {
  eventId: string;
  observedAt: string;
  traceId: string;
  intent: string;
  identity: {
    subject: PrincipalView;
    actor: PrincipalView | null;
    createdBy: PrincipalView | null;
  };
  trigger: {
    kind: string;
    origin: string;
    clientName: string;
  };
  target: {
    system: string;
    environment: string;
    accountId: string;
    resourceType: string;
    toolName: string;
  };
  risk: {
    actionClass: string;
    severity: Severity;
    score: number;
    signals: string[];
    semanticIntents: string[];
  };
  decision: {
    outcome: DecisionOutcome;
    reasonCode: string;
    policyId: string;
    policyVersion: number;
    matchedRuleId: string | null;
  };
  latencyMs: number;
  executionStatus: string;
  sanitization: {
    transformerId: string;
    changes: string[];
    effectiveEventId: string;
    finalOutcome: DecisionOutcome;
  } | null;
  lineage: Array<{
    hopId: string;
    parentHopId: string | null;
    kind: string;
    subjectId: string;
    assurance: string;
    source: string;
  }>;
  simulated: boolean;
}

export interface ActionFeedPage {
  schemaVersion: string;
  simulated: boolean;
  total: number;
  nextCursor: string | null;
  items: ActionFeedEntry[];
}

export interface ActionFeedOverview {
  schemaVersion: string;
  simulated: boolean;
  total: number;
  allowed: number;
  blocked: number;
  sanitized: number;
  approvalRequired: number;
  p95LatencyMs: number;
  latencySampleSize: number;
  latencySampleScope: "current_run";
}

export interface EvidenceIntegritySummary {
  eventId: string;
  traceId: string;
  streamId: string;
  valid: boolean;
  anchored: boolean;
  eventCount: number;
  headHash: string;
  issueCode: string | null;
  simulated: boolean;
}

export interface EvidenceIntegrityDetail extends EvidenceIntegritySummary {
  events: Array<{
    evidenceEventId: string;
    sequence: number;
    eventType: string;
    occurredAt: string;
    recordedAt: string;
    previousEventHash: string | null;
    eventHash: string;
  }>;
}

export interface EvidenceIntegrityPage {
  schemaVersion: string;
  simulated: boolean;
  total: number;
  items: EvidenceIntegritySummary[];
}

export interface PolicyVersionSummary {
  policyId: string;
  version: number;
  name: string;
  status: string;
  schemaVersion: string;
  contentHash: string;
  evaluationCount: number;
  activatedAt: string;
  simulated: boolean;
}

export interface PolicyVersionDetail extends PolicyVersionSummary {
  description: string;
  defaultOutcome: DecisionOutcome;
  runtimeVersions: {
    canonicalSchema: string;
    classifier: string;
    adapter: string;
    executionKey: string;
  };
  rules: Array<{
    ruleId: string;
    priority: number;
    description: string;
    outcome: DecisionOutcome;
    reasonCode: string;
  }>;
}

export interface PolicyVersionPage {
  schemaVersion: string;
  simulated: boolean;
  total: number;
  items: PolicyVersionSummary[];
}

export type AuthoritySummary = "causal_only" | "validated_delegation_claim";

export interface LineageSummary {
  eventId: string;
  traceId: string;
  observedAt: string;
  intent: string;
  rootPrincipalId: string;
  hopCount: number;
  delegationClaimCount: number;
  authoritySummary: AuthoritySummary;
  simulated: boolean;
}

export interface LineageDetail extends LineageSummary {
  targetSystem: string;
  targetTool: string;
  hops: Array<{
    hopId: string;
    parentHopId: string | null;
    kind: string;
    subjectId: string;
    assurance: string;
    source: string;
    relation: "root" | "causal" | "delegation_claim";
  }>;
}

export interface LineagePage {
  schemaVersion: string;
  simulated: boolean;
  total: number;
  items: LineageSummary[];
}
