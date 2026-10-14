package io.tracepermit.policy;

public record PolicyDecision(
    String outcome,
    String reasonCode,
    String approvalGroup,
    String matchedRuleId,
    String policyId,
    int policyVersion) {}
