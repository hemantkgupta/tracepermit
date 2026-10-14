package io.tracepermit.application;

import java.util.List;
import java.util.Objects;

/** Secret-free result of a target lookup by stable idempotency identity. */
public record ReconciliationObservation(String outcome, String reasonCode) {
  public ReconciliationObservation {
    if (!List.of("succeeded", "failed", "unknown").contains(outcome)) {
      throw new IllegalArgumentException("The reconciliation observation outcome is invalid.");
    }
    Objects.requireNonNull(reasonCode);
    if (!reasonCode.matches("[A-Z][A-Z0-9_]{0,127}")) {
      throw new IllegalArgumentException("The reconciliation observation reason is invalid.");
    }
  }

  public boolean confirmed() {
    return !"unknown".equals(outcome);
  }
}
