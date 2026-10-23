package io.tracepermit.application;

import static io.tracepermit.application.BusinessOperationTransitionException.Code.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.BusinessOperationValidator;
import io.tracepermit.contracts.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Pure proposal builder, NOT an authorization service or an atomic state store.
 *
 * <p>Scope construction and a structurally valid approved/claimed/published label prove no authority
 * or target outcome. A future trusted service must independently verify policy, approval, permits,
 * recovery and observed outcomes, and atomically recheck state under its database lock. This class
 * has no repository, credential, network, signing, execution or publication port.
 */
public final class BusinessOperationTransitionPlanner {
  private static final int MAX_SNAPSHOT_BYTES = 262_144;
  private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

  public enum Dimension {
    AUTHORIZATION("authorization"), EXECUTION("execution"), EVIDENCE("evidence");

    private final String field;

    Dimension(String field) {
      this.field = field;
    }
  }

  /** Optimistic state identity only: forgeable by callers, never an authority capability. */
  public record StateToken(
      String operationId,
      long recoveryGeneration,
      long lastSequence,
      long authorizationVersion,
      long executionVersion,
      long evidenceVersion,
      String snapshotHash) {
    public StateToken {
      try {
        if (!UUID.fromString(operationId).toString().equalsIgnoreCase(operationId)
            || snapshotHash == null || !snapshotHash.matches("sha256:[a-f0-9]{64}")) {
          throw new IllegalArgumentException();
        }
        for (long value : new long[] {recoveryGeneration, lastSequence, authorizationVersion,
            executionVersion, evidenceVersion}) {
          if (value < 1 || value > MAX_SAFE_INTEGER) throw new IllegalArgumentException();
        }
      } catch (IllegalArgumentException | NullPointerException failure) {
        throw error(OPERATION_TRANSITION_INVALID);
      }
    }

    @Override
    public String toString() {
      return "BusinessOperationStateToken[redacted; not authority]";
    }
  }

  /** Candidate data only. No commit, claim, target effect or receipt verification has occurred. */
  public static final class Proposal {
    private final StateToken expected;
    private final JsonNode snapshot;

    private Proposal(StateToken expected, JsonNode snapshot) {
      this.expected = expected;
      this.snapshot = snapshot.deepCopy();
    }

    public StateToken expected() {
      return expected;
    }

    public JsonNode snapshot() {
      return snapshot.deepCopy();
    }

    @Override
    public String toString() {
      return "BusinessOperationProposal[uncommitted; not authority]";
    }
  }

  public StateToken observe(BusinessOperationScope scope, JsonNode snapshot) {
    return token(scoped(scope, snapshot));
  }

  public Proposal propose(
      BusinessOperationScope scope,
      JsonNode snapshot,
      StateToken expected,
      Dimension dimension,
      String nextStatus,
      String reasonCode,
      Instant at) {
    ObjectNode candidate = scoped(scope, snapshot);
    if (expected == null || dimension == null || at == null
        || nextStatus == null || reasonCode == null) {
      throw error(OPERATION_TRANSITION_INVALID);
    }
    if (!token(candidate).equals(expected)) throw error(OPERATION_STATE_STALE);

    ObjectNode selected = (ObjectNode) candidate.path(dimension.field);
    String from = selected.path("status").asText();
    long version = selected.path("version").asLong() + 1;
    ArrayNode history = (ArrayNode) candidate.path("transitions");
    ObjectNode transition = history.addObject();
    transition.put("sequence", history.size());
    transition.put("dimension", dimension.field);
    transition.put("from", from);
    transition.put("to", nextStatus);
    transition.put("version", version);
    transition.put("at", at.toString());
    transition.put("reasonCode", reasonCode);
    selected.put("status", nextStatus);
    selected.put("version", version);
    selected.put("updatedAt", at.toString());
    candidate.put("updatedAt", at.toString());

    // Existing v1 graph/time/version rules are the sole structural transition vocabulary.
    return new Proposal(expected, validated(candidate));
  }

  private static ObjectNode scoped(BusinessOperationScope scope, JsonNode snapshot) {
    ObjectNode current = validated(snapshot);
    if (scope == null || !scope.trustedContext().equals(current.path("trustedContext"))) {
      throw error(OPERATION_SCOPE_MISMATCH);
    }
    return current;
  }

  private static ObjectNode validated(JsonNode input) {
    ObjectNode validated;
    try {
      validated = (ObjectNode) BusinessOperationValidator.validate(input);
    } catch (RuntimeException failure) {
      throw error(OPERATION_TRANSITION_INVALID);
    }
    if (CanonicalJson.canonicalize(validated).getBytes(StandardCharsets.UTF_8).length
        > MAX_SNAPSHOT_BYTES) {
      throw error(OPERATION_TOO_LARGE);
    }
    return validated;
  }

  private static StateToken token(ObjectNode operation) {
    return new StateToken(
        operation.path("operationId").asText(),
        operation.path("recoveryGeneration").asLong(),
        operation.path("transitions").size(),
        operation.path("authorization").path("version").asLong(),
        operation.path("execution").path("version").asLong(),
        operation.path("evidence").path("version").asLong(),
        CanonicalJson.sha256(operation));
  }

  private static BusinessOperationTransitionException error(
      BusinessOperationTransitionException.Code code) {
    return new BusinessOperationTransitionException(code);
  }
}
