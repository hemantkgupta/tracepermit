package io.tracepermit.application;

import io.tracepermit.execution.ReconciliationCandidate;

/** Read-only target lookup port. It must never invoke or retry the original effect. */
@FunctionalInterface
public interface TargetOutcomeResolver {
  ReconciliationObservation lookup(ReconciliationCandidate candidate);
}
