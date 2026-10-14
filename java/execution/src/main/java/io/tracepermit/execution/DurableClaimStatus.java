package io.tracepermit.execution;

public enum DurableClaimStatus {
  STARTED,
  PERMIT_REPLAYED,
  IDEMPOTENCY_DUPLICATE
}
