package io.tracepermit.persistence.operations;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.adapters.remote.github.GitHubBusinessOperationBinding;
import io.tracepermit.adapters.remote.github.GitHubOperationProfile;
import io.tracepermit.application.BusinessOperationScope;
import io.tracepermit.application.BusinessOperationStorageException;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic in-memory simulation ONLY. No transport, credentials, provider or extensible port. */
public final class SyntheticGitHubDeliveryTarget {
  public enum Outcome { CONFIRMED_SUCCESS, CONFIRMED_REJECTION, UNKNOWN }
  private final GitHubOperationProfile expected;
  private final Outcome outcome;
  private final ConcurrentHashMap<String, AtomicInteger> calls = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, String> observations = new ConcurrentHashMap<>();

  /** Original Track E descriptor retained for compiled and source compatibility. */
  public SyntheticGitHubDeliveryTarget(
      GitHubBusinessOperationBinding.ExpectedProfile expected, Outcome outcome) {
    this((GitHubOperationProfile) expected, outcome);
  }

  /** Explicit operator-owned synthetic scenario; never a public request or selectable provider. */
  public SyntheticGitHubDeliveryTarget(GitHubOperationProfile expected, Outcome outcome) {
    if (expected == null || outcome == null) throw new IllegalArgumentException("SYNTHETIC_TARGET_CONFIGURATION_INVALID");
    this.expected = expected; this.outcome = outcome;
  }

  void requirePreconditions(BusinessOperationScope scope, JsonNode admitted, String expectedProfile) {
    try {
      var bound = expected.bind(scope, admitted);
      if (!bound.profileFingerprint().equals(expectedProfile)) throw new IllegalArgumentException();
    } catch (RuntimeException failure) {
      throw new BusinessOperationStorageException(BusinessOperationStorageException.Code.OPERATION_TARGET_PRECONDITION_FAILED);
    }
  }

  String invoke(String operationId) {
    // Count EVERY invocation, including duplicates: deduplication must never conceal a sender bug.
    calls.computeIfAbsent(operationId, ignored -> new AtomicInteger()).incrementAndGet();
    String value = switch (outcome) { case CONFIRMED_SUCCESS -> "succeeded"; case CONFIRMED_REJECTION -> "failed"; case UNKNOWN -> "unknown"; };
    observations.put(operationId, value);
    return value;
  }
  int invocations(String operationId) { AtomicInteger value = calls.get(operationId); return value == null ? 0 : value.get(); }
  String observation(String operationId) { return observations.getOrDefault(operationId, "unknown"); }
  static String receiptFingerprint(String operationId, String attemptId, int fence, String outcome) {
    return CanonicalJson.sha256(JsonSupport.MAPPER.createObjectNode().put("format", "tracepermit-synthetic-delivery-receipt-v1")
        .put("operationId", operationId).put("attemptId", attemptId).put("fence", fence).put("outcome", outcome));
  }
  @Override public String toString() { return "SyntheticGitHubDeliveryTarget[synthetic only; redacted]"; }
}
