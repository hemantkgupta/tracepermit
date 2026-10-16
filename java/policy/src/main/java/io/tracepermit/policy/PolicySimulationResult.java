package io.tracepermit.policy;

import java.time.Instant;
import java.util.Objects;

public record PolicySimulationResult(
    String policyId,
    int version,
    PolicyEvaluation evaluation,
    Instant simulatedAt,
    boolean dryRun) {

  public PolicySimulationResult {
    Objects.requireNonNull(policyId, "policyId must not be null");
    Objects.requireNonNull(evaluation, "evaluation must not be null");
    Objects.requireNonNull(simulatedAt, "simulatedAt must not be null");
  }

  public String outcome() {
    return evaluation.decision().outcome();
  }

  public String reasonCode() {
    return evaluation.decision().reasonCode();
  }

  public String matchedRuleId() {
    return evaluation.decision().matchedRuleId();
  }

  public String approvalGroup() {
    return evaluation.decision().approvalGroup();
  }
}
