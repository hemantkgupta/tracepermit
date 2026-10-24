package io.tracepermit.application;

import static io.tracepermit.application.BusinessOperationTransitionException.Code.*;
import static io.tracepermit.application.BusinessOperationTransitionPlanner.Dimension.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.application.BusinessOperationTransitionPlanner.StateToken;
import io.tracepermit.contracts.BusinessOperationValidator;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

final class BusinessOperationTransitionPlannerTest {
  private static final BusinessOperationScope SCOPE = new BusinessOperationScope(
      "synthetic-tenant", "synthetic-github-sandbox", "principal:synthetic-requester");
  private final BusinessOperationTransitionPlanner planner = new BusinessOperationTransitionPlanner();

  @Test
  void appendsExactlyOneFactAndPreservesEveryOtherFieldAndHistoryPrefix() {
    ObjectNode input = operation();
    JsonNode before = input.deepCopy();
    StateToken expected = planner.observe(SCOPE, input);
    var proposal = planner.propose(SCOPE, input, expected, AUTHORIZATION,
        "approved", "SYNTHETIC_REVIEW_SHAPE", time(input).plusSeconds(1));
    JsonNode after = proposal.snapshot();

    assertThat(input).isEqualTo(before);
    assertThat(proposal.expected()).isEqualTo(expected);
    assertThat(BusinessOperationValidator.validate(after)).isEqualTo(after);
    assertThat(after.path("transitions").size()).isEqualTo(before.path("transitions").size() + 1);
    for (int index = 0; index < before.path("transitions").size(); index++) {
      assertThat(after.path("transitions").get(index)).isEqualTo(before.path("transitions").get(index));
    }
    assertThat(after.path("authorization").path("version").asLong())
        .isEqualTo(before.path("authorization").path("version").asLong() + 1);
    before.fieldNames().forEachRemaining(field -> {
      if (!List.of("authorization", "updatedAt", "transitions").contains(field)) {
        assertThat(after.path(field)).as(field).isEqualTo(before.path(field));
      }
    });
  }

  @Test
  void changesInAnyDimensionOrImmutableBindingInvalidateTheWholeStateToken() {
    ObjectNode original = operation();
    StateToken token = planner.observe(SCOPE, original);
    JsonNode leased = next(original, EVIDENCE, "leased");
    assertCode(() -> planner.propose(SCOPE, leased, token, AUTHORIZATION,
        "approved", "SYNTHETIC_REASON", time(leased)), OPERATION_STATE_STALE);
    ObjectNode changedPolicy = original.deepCopy();
    ((ObjectNode) changedPolicy.path("policyRelease")).put("version", 2);
    assertCode(() -> planner.propose(SCOPE, changedPolicy, token, AUTHORIZATION,
        "approved", "SYNTHETIC_REASON", time(changedPolicy)), OPERATION_STATE_STALE);
    ObjectNode changedGeneration = original.deepCopy();
    changedGeneration.put("recoveryGeneration", 2);
    changedGeneration.put("targetIdempotencyKeyHash", BusinessOperationValidator.targetIdempotencyKeyHash(
        changedGeneration.path("operationId").asText(), changedGeneration.path("adapterId").asText(), 2));
    assertCode(() -> planner.propose(SCOPE, changedGeneration, token, AUTHORIZATION,
        "approved", "SYNTHETIC_REASON", time(changedGeneration)), OPERATION_STATE_STALE);
  }

  @Test
  void everyExpectedTokenComponentMustMatchEvenWhenSnapshotHashMatches() {
    ObjectNode input = operation();
    StateToken t = planner.observe(SCOPE, input);
    for (StateToken forged : List.of(
        new StateToken("00000000-0000-0000-0000-000000000000", t.recoveryGeneration(), t.lastSequence(), t.authorizationVersion(), t.executionVersion(), t.evidenceVersion(), t.snapshotHash()),
        new StateToken(t.operationId(), 2, t.lastSequence(), t.authorizationVersion(), t.executionVersion(), t.evidenceVersion(), t.snapshotHash()),
        new StateToken(t.operationId(), t.recoveryGeneration(), t.lastSequence() + 1, t.authorizationVersion(), t.executionVersion(), t.evidenceVersion(), t.snapshotHash()),
        new StateToken(t.operationId(), t.recoveryGeneration(), t.lastSequence(), t.authorizationVersion() + 1, t.executionVersion(), t.evidenceVersion(), t.snapshotHash()),
        new StateToken(t.operationId(), t.recoveryGeneration(), t.lastSequence(), t.authorizationVersion(), t.executionVersion() + 1, t.evidenceVersion(), t.snapshotHash()),
        new StateToken(t.operationId(), t.recoveryGeneration(), t.lastSequence(), t.authorizationVersion(), t.executionVersion(), t.evidenceVersion() + 1, t.snapshotHash()),
        new StateToken(t.operationId(), t.recoveryGeneration(), t.lastSequence(), t.authorizationVersion(), t.executionVersion(), t.evidenceVersion(), "sha256:" + "f".repeat(64)))) {
      assertCode(() -> planner.propose(SCOPE, input, forged, AUTHORIZATION,
          "approved", "SYNTHETIC_REASON", time(input)), OPERATION_STATE_STALE);
    }
  }

  @Test
  void staleProposalsDoNotComposeAndCancelledAuthorizationDoesNotStopExecution() {
    JsonNode approved = next(operation(), AUTHORIZATION, "approved");
    JsonNode claimed = next(approved, EXECUTION, "claimed");
    JsonNode executing = next(claimed, EXECUTION, "executing");
    StateToken expected = planner.observe(SCOPE, executing);
    JsonNode cancelled = planner.propose(SCOPE, executing, expected, AUTHORIZATION,
        "cancelled", "SYNTHETIC_CANCEL_SHAPE", time(executing)).snapshot();
    assertThat(cancelled.path("execution")).isEqualTo(executing.path("execution"));
    assertThat(cancelled.path("execution").path("status").asText()).isEqualTo("executing");
    assertCode(() -> planner.propose(SCOPE, cancelled, expected, EXECUTION,
        "unknown", "SYNTHETIC_UNKNOWN", time(cancelled)), OPERATION_STATE_STALE);
  }

  @Test
  void twoCandidatesFromOneSnapshotAreNotAnAtomicWinnerOrAuthorityClaim() {
    ObjectNode input = operation();
    StateToken expected = planner.observe(SCOPE, input);
    var approved = planner.propose(SCOPE, input, expected, AUTHORIZATION,
        "approved", "SYNTHETIC_SHAPE_ONLY", time(input));
    var denied = planner.propose(SCOPE, input, expected, AUTHORIZATION,
        "denied", "SYNTHETIC_SHAPE_ONLY", time(input));
    assertThat(approved.expected()).isEqualTo(denied.expected());
    assertThat(approved.snapshot().path("authorization").path("status").asText()).isEqualTo("approved");
    assertThat(denied.snapshot().path("authorization").path("status").asText()).isEqualTo("denied");
    assertThat(input.path("authorization").path("status").asText()).isEqualTo("awaiting_approval");
  }

  @Test
  void unknownNeverResetsAndTerminalStatesCannotBeReopened() {
    JsonNode approved = next(operation(), AUTHORIZATION, "approved");
    JsonNode claimed = next(approved, EXECUTION, "claimed");
    JsonNode unknown = next(claimed, EXECUTION, "unknown");
    for (String status : List.of("not_started", "claimed", "executing")) {
      assertCode(() -> next(unknown, EXECUTION, status), OPERATION_TRANSITION_INVALID);
    }
    JsonNode succeeded = next(unknown, EXECUTION, "succeeded");
    assertCode(() -> next(succeeded, EXECUTION, "unknown"), OPERATION_TRANSITION_INVALID);
    assertCode(() -> next(next(operation(), AUTHORIZATION, "denied"), AUTHORIZATION, "approved"),
        OPERATION_TRANSITION_INVALID);
    JsonNode leased = next(operation(), EVIDENCE, "leased");
    assertThat(next(leased, EVIDENCE, "pending").path("evidence").path("status").asText())
        .isEqualTo("pending");
  }

  @Test
  void scopeMismatchInvalidInputsBackwardTimeAndMalformedReasonsAreRedacted() {
    ObjectNode input = operation();
    StateToken token = planner.observe(SCOPE, input);
    assertCode(() -> planner.observe(new BusinessOperationScope("other", "workspace", "requester"), input),
        OPERATION_SCOPE_MISMATCH);
    assertCode(() -> planner.observe(null, input), OPERATION_SCOPE_MISMATCH);
    assertCode(() -> planner.observe(SCOPE, JsonSupport.MAPPER.createObjectNode()), OPERATION_TRANSITION_INVALID);
    assertCode(() -> planner.propose(SCOPE, input, token, AUTHORIZATION,
        "approved", "SYNTHETIC_REASON", time(input).minusNanos(1)), OPERATION_TRANSITION_INVALID);
    for (String reason : List.of("secret with spaces", "lowercase", "A".repeat(129))) {
      assertCode(() -> planner.propose(SCOPE, input, token, AUTHORIZATION,
          "approved", reason, time(input)), OPERATION_TRANSITION_INVALID);
    }
    assertCode(() -> next(input, AUTHORIZATION, "UNSUPPORTED_SECRET_STATUS"), OPERATION_TRANSITION_INVALID);
    assertCode(() -> planner.propose(SCOPE, input, null, AUTHORIZATION, "approved", "REASON", time(input)), OPERATION_TRANSITION_INVALID);
    assertCode(() -> planner.propose(SCOPE, input, token, null, "approved", "REASON", time(input)), OPERATION_TRANSITION_INVALID);
    assertCode(() -> planner.propose(SCOPE, input, token, AUTHORIZATION, null, "REASON", time(input)), OPERATION_TRANSITION_INVALID);
    assertCode(() -> planner.propose(SCOPE, input, token, AUTHORIZATION, "approved", null, time(input)), OPERATION_TRANSITION_INVALID);
    assertCode(() -> planner.propose(SCOPE, input, token, AUTHORIZATION, "approved", "REASON", null), OPERATION_TRANSITION_INVALID);
  }

  @Test
  void candidateOverflowAndUnsafeTokenValuesAreRejected() {
    ObjectNode input = operation();
    ObjectNode arguments = (ObjectNode) input.path("canonicalAction").path("action").path("arguments");
    arguments.put("body", "");
    rehash(input);
    int padding = 262_144 - CanonicalJson.canonicalize(input).getBytes(StandardCharsets.UTF_8).length;
    arguments.put("body", "x".repeat(padding));
    rehash(input);
    assertThat(CanonicalJson.canonicalize(input).getBytes(StandardCharsets.UTF_8)).hasSize(262_144);
    StateToken token = planner.observe(SCOPE, input);
    assertCode(() -> planner.propose(SCOPE, input, token, AUTHORIZATION,
        "approved", "SYNTHETIC_REASON", time(input)), OPERATION_TOO_LARGE);
    arguments.put("body", "é".repeat(150_000));
    rehash(input);
    assertCode(() -> planner.observe(SCOPE, input), OPERATION_TOO_LARGE);
    assertCode(() -> new StateToken("bad", 1, 1, 1, 1, 1, token.snapshotHash()), OPERATION_TRANSITION_INVALID);
    assertCode(() -> new StateToken(token.operationId(), 0, 1, 1, 1, 1, token.snapshotHash()), OPERATION_TRANSITION_INVALID);
    assertCode(() -> new StateToken(token.operationId(), Long.MAX_VALUE, 1, 1, 1, 1, token.snapshotHash()), OPERATION_TRANSITION_INVALID);
    assertCode(() -> new StateToken(token.operationId(), 1, 1, 1, 1, 1, "secret"), OPERATION_TRANSITION_INVALID);
    assertCode(() -> new StateToken(null, 1, 1, 1, 1, 1, token.snapshotHash()), OPERATION_TRANSITION_INVALID);
    assertCode(() -> new StateToken(token.operationId(), 1, 1, 1, 1, 1, null), OPERATION_TRANSITION_INVALID);
  }

  @Test
  void proposalAndTokenExposeNoPayloadInDiagnosticsAndDefensivelyCopyBothDirections() {
    ObjectNode input = operation();
    StateToken token = planner.observe(SCOPE, input);
    var proposal = planner.propose(SCOPE, input, token, AUTHORIZATION,
        "approved", "SYNTHETIC_SHAPE_ONLY", time(input));
    JsonNode before = proposal.snapshot();
    ((ObjectNode) input.path("policyRelease")).put("version", 9);
    ((ObjectNode) proposal.snapshot().path("authorization")).put("status", "denied");
    assertThat(proposal.snapshot()).isEqualTo(before);
    assertThat(proposal.toString()).contains("uncommitted", "not authority")
        .doesNotContain("canonicalAction", token.operationId(), token.snapshotHash());
    assertThat(token.toString()).contains("redacted", "not authority")
        .doesNotContain(token.operationId(), token.snapshotHash());
  }

  private JsonNode next(JsonNode input, BusinessOperationTransitionPlanner.Dimension dimension, String status) {
    return planner.propose(SCOPE, input, planner.observe(SCOPE, input), dimension,
        status, "SYNTHETIC_SHAPE_ONLY", time(input).plusSeconds(1)).snapshot();
  }

  private static ObjectNode operation() {
    return (ObjectNode) JsonSupport.read(Path.of(System.getProperty("tracepermit.repositoryRoot"))
        .resolve("contracts/vectors/business-operation-v1.json")).path("operation").deepCopy();
  }

  private static Instant time(JsonNode input) {
    return Instant.parse(input.path("updatedAt").asText());
  }

  private static void rehash(ObjectNode operation) {
    operation.put("canonicalActionHash", CanonicalJson.sha256(operation.path("canonicalAction")));
    operation.put("semanticPayloadDigest", CanonicalJson.sha256(operation.path("canonicalAction").path("action")));
  }

  private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
      BusinessOperationTransitionException.Code expected) {
    assertThatThrownBy(action).isInstanceOfSatisfying(BusinessOperationTransitionException.class,
        failure -> { assertThat(failure.code()).isEqualTo(expected); assertThat(failure).hasMessage(expected.name()).hasNoCause(); });
  }
}
