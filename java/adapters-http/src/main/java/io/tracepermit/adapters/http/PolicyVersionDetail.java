package io.tracepermit.adapters.http;

import java.util.List;

public record PolicyVersionDetail(
    String policyId,
    int version,
    String name,
    String description,
    String status,
    String schemaVersion,
    String contentHash,
    int evaluationCount,
    String activatedAt,
    String defaultOutcome,
    RuntimeVersions runtimeVersions,
    List<PolicyRuleView> rules,
    boolean simulated) {

  public PolicyVersionDetail {
    rules = List.copyOf(rules);
  }

  public PolicyVersionSummary summary() {
    return new PolicyVersionSummary(
        policyId,
        version,
        name,
        status,
        schemaVersion,
        contentHash,
        evaluationCount,
        activatedAt,
        simulated);
  }

  public record RuntimeVersions(
      String canonicalSchema,
      String classifier,
      String adapter,
      String executionKey) {}

  public record PolicyRuleView(
      String ruleId,
      int priority,
      String description,
      String outcome,
      String reasonCode) {}
}
