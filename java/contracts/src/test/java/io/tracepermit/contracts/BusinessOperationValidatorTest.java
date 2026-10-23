package io.tracepermit.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class BusinessOperationValidatorTest {
  private static final Path ROOT = Path.of(System.getProperty("tracepermit.repositoryRoot"));
  private static final Path VECTOR = ROOT.resolve("contracts/vectors/business-operation-v1.json");

  @Test
  void acceptsFrozenSyntheticDraftPullRequestOperationAndPreservesDistinctIdentities() {
    ObjectNode operation = operation();

    JsonNode validated = BusinessOperationValidator.validate(operation);

    assertThat(validated).isEqualTo(operation).isNotSameAs(operation);
    assertThat(validated.path("operationId").asText())
        .isNotEqualTo(validated.path("canonicalAction").path("eventId").asText())
        .isNotEqualTo(validated.path("canonicalAction").path("trigger").path("requestId").asText());
    assertThat(
            Set.of(
                validated.path("clientRequestKeyHash").asText(),
                validated.path("canonicalActionHash").asText(),
                validated.path("semanticPayloadDigest").asText(),
                validated.path("resourceBindingHash").asText(),
                validated.path("targetIdempotencyKeyHash").asText()))
        .hasSize(5);
    assertThat(validated.path("authorization").path("status").asText())
        .isEqualTo("awaiting_approval");
    assertThat(validated.path("execution").path("status").asText())
        .isEqualTo("not_started");
    assertThat(validated.path("evidence").path("status").asText()).isEqualTo("pending");
  }

  @Test
  void goldenHashesBindActionPayloadResourceAndTargetGeneration() {
    ObjectNode operation = operation();

    assertThat(operation.path("canonicalActionHash").asText())
        .isEqualTo(CanonicalJson.sha256(operation.path("canonicalAction")));
    assertThat(operation.path("semanticPayloadDigest").asText())
        .isEqualTo(CanonicalJson.sha256(operation.path("canonicalAction").path("action")));
    assertThat(operation.path("resourceBindingHash").asText())
        .isEqualTo(CanonicalJson.sha256(operation.path("resourceBinding")));
    assertThat(operation.path("targetIdempotencyKeyHash").asText())
        .isEqualTo(
            BusinessOperationValidator.targetIdempotencyKeyHash(
                operation.path("operationId").asText(),
                operation.path("adapterId").asText(),
                operation.path("recoveryGeneration").asLong()));
  }

  @Test
  void validationDeepCopiesBothDirections() {
    ObjectNode input = operation();
    JsonNode validated = BusinessOperationValidator.validate(input);
    JsonNode inputSnapshot = input.deepCopy();
    JsonNode validatedSnapshot = validated.deepCopy();

    ((ObjectNode) input.path("trustedContext"))
        .put("requesterPrincipalId", "principal:changed-after-validation");
    ((ObjectNode) input.path("canonicalAction").path("action").path("arguments"))
        .put("title", "Changed caller tree");
    assertThat(validated).isEqualTo(validatedSnapshot);

    ((ObjectNode) validated.path("trustedContext"))
        .put("requesterPrincipalId", "principal:changed-result");
    ((ObjectNode) validated.path("canonicalAction").path("action").path("arguments"))
        .put("title", "Changed returned tree");
    assertThat(inputSnapshot.path("trustedContext").path("requesterPrincipalId").asText())
        .isEqualTo("principal:synthetic-requester");
    assertThat(inputSnapshot.path("canonicalAction").path("action").path("arguments").path("title").asText())
        .isEqualTo("Synthetic governed change");
  }

  @Test
  void sharedInvalidMutationVectorsFailAtTheirNamedBoundary() {
    JsonNode vectors = JsonSupport.read(VECTOR);
    for (JsonNode mutation : vectors.path("invalidMutations")) {
      ObjectNode candidate = operation();
      applyMutation(candidate, mutation);
      assertThatThrownBy(() -> BusinessOperationValidator.validate(candidate))
          .as(mutation.path("name").asText())
          .isInstanceOfSatisfying(
              ContractViolation.class,
              violation ->
                  assertThat(violation.path())
                      .isEqualTo(mutation.path("expectedPath").asText()));
    }
  }

  @Test
  void trustedContextAndCanonicalActionRejectUnknownOrUnnormalizedFields() {
    ObjectNode untrusted = operation();
    ((ObjectNode) untrusted.path("trustedContext")).put("authenticated", true);
    expectViolation(untrusted, "trustedContext", "contains unsupported fields");

    ObjectNode actionClaim = operation();
    ((ObjectNode) actionClaim.path("canonicalAction")).put("authenticated", true);
    expectViolation(
        actionClaim,
        "canonicalAction",
        "must already be normalized and contain no unsupported fields");

    ObjectNode unsupportedSchema = operation();
    unsupportedSchema.put("schemaVersion", "1.1");
    expectViolation(unsupportedSchema, "schemaVersion", "must equal 1.0");

    ObjectNode mismatchedRequester = operation();
    ((ObjectNode) mismatchedRequester.path("trustedContext"))
        .put("requesterPrincipalId", "principal:other-requester");
    expectViolation(
        mismatchedRequester,
        "trustedContext.requesterPrincipalId",
        "must match canonicalAction.identity.subject.id");

    ObjectNode mismatchedTenant = operation();
    ((ObjectNode) mismatchedTenant.path("trustedContext")).put("tenantId", "other-tenant");
    expectViolation(
        mismatchedTenant,
        "trustedContext.tenantId",
        "must match canonicalAction.identity.subject.organizationId");

    ObjectNode invalidWorkspace = operation();
    ((ObjectNode) invalidWorkspace.path("trustedContext")).put("workspaceId", "../other");
    expectViolation(
        invalidWorkspace,
        "trustedContext.workspaceId",
        "contains an invalid partition identifier");
  }

  @Test
  void resourcePreconditionAndRetentionMustBeExplicitAndBound() {
    ObjectNode noPrecondition = operation();
    ObjectNode precondition = (ObjectNode) noPrecondition.path("resourceBinding").path("precondition");
    precondition.put("kind", "none");
    precondition.remove("valueHash");
    noPrecondition.put(
        "resourceBindingHash", CanonicalJson.sha256(noPrecondition.path("resourceBinding")));
    assertThatCode(() -> BusinessOperationValidator.validate(noPrecondition))
        .doesNotThrowAnyException();

    ObjectNode missingBoundValue = operation();
    ((ObjectNode) missingBoundValue.path("resourceBinding").path("precondition"))
        .remove("valueHash");
    expectViolation(
        missingBoundValue,
        "resourceBinding.precondition.valueHash",
        "must be a non-empty string");

    ObjectNode indefiniteRetention = operation();
    ((ObjectNode) indefiniteRetention.path("retention"))
        .put("retainUntil", indefiniteRetention.path("createdAt").asText());
    expectViolation(
        indefiniteRetention, "retention.retainUntil", "must be after createdAt");

    ObjectNode predatesObservation = operation();
    predatesObservation.put("createdAt", "2026-10-23T09:59:58.000Z");
    ((ObjectNode) predatesObservation.path("transitions").get(0))
        .put("at", "2026-10-23T09:59:58.000Z");
    expectViolation(
        predatesObservation,
        "createdAt",
        "must not be before canonicalAction.observedAt");
  }

  @Test
  void stateHistoryRejectsInvalidEdgesVersionsTimesAndSnapshotMutation() {
    ObjectNode invalidEdge = operation();
    ObjectNode transition = (ObjectNode) invalidEdge.path("transitions").get(3);
    transition.put("to", "revoked");
    ((ObjectNode) invalidEdge.path("authorization")).put("status", "revoked");
    expectViolation(invalidEdge, "transitions[3].to", "contains an invalid state transition");

    ObjectNode skippedVersion = operation();
    ((ObjectNode) skippedVersion.path("transitions").get(3)).put("version", 3);
    ((ObjectNode) skippedVersion.path("authorization")).put("version", 3);
    expectViolation(
        skippedVersion,
        "transitions[3].version",
        "must be contiguous within its dimension");

    ObjectNode reversedTime = operation();
    ((ObjectNode) reversedTime.path("transitions").get(3))
        .put("at", "2026-10-23T10:00:00.001Z");
    ((ObjectNode) reversedTime.path("authorization"))
        .put("updatedAt", "2026-10-23T10:00:00.001Z");
    expectViolation(
        reversedTime, "transitions[3].at", "must be globally nondecreasing");

    ObjectNode changedSnapshotTime = operation();
    ((ObjectNode) changedSnapshotTime.path("authorization"))
        .put("updatedAt", "2026-10-23T10:00:00.002Z");
    expectViolation(
        changedSnapshotTime,
        "authorization.updatedAt",
        "must match transition history");
  }

  @Test
  void targetKeyCannotBeReusedAcrossRecoveryGenerationsWithoutRebinding() {
    ObjectNode candidate = operation();
    candidate.put("recoveryGeneration", 2);

    expectViolation(
        candidate,
        "targetIdempotencyKeyHash",
        "must derive from operationId, adapterId, and recoveryGeneration");

    candidate.put(
        "targetIdempotencyKeyHash",
        BusinessOperationValidator.targetIdempotencyKeyHash(
            candidate.path("operationId").asText(), candidate.path("adapterId").asText(), 2));
    assertThatCode(() -> BusinessOperationValidator.validate(candidate))
        .doesNotThrowAnyException();
  }

  @Test
  void artifactKindsAreUniqueAndSecretsDoNotBelongInTheAggregate() {
    ObjectNode duplicate = operation();
    ArrayNode artifacts = (ArrayNode) duplicate.path("artifactDigests");
    artifacts.add(artifacts.get(0).deepCopy());
    expectViolation(duplicate, "artifactDigests[2].kind", "must be unique");

    ObjectNode missingArtifacts = operation();
    ((ArrayNode) missingArtifacts.path("artifactDigests")).removeAll();
    expectViolation(missingArtifacts, "artifactDigests", "requires at least one item");

    String serialized = operation().toString();
    assertThat(serialized)
        .doesNotContain(
            "BEGIN PRIVATE KEY",
            "client_secret",
            "access_token",
            "refresh_token",
            "Authorization");
  }

  private static ObjectNode operation() {
    return (ObjectNode) JsonSupport.read(VECTOR).path("operation").deepCopy();
  }

  private static void applyMutation(ObjectNode operation, JsonNode mutation) {
    String path = mutation.path("path").asText();
    ObjectNode parent =
        (ObjectNode) operation.at(path.substring(0, path.lastIndexOf('/')));
    String field = path.substring(path.lastIndexOf('/') + 1);
    if ("remove".equals(mutation.path("operation").asText())) {
      parent.remove(field);
    } else {
      parent.set(field, mutation.path("value"));
    }
  }

  private static void expectViolation(
      JsonNode candidate, String path, String message) {
    assertThatThrownBy(() -> BusinessOperationValidator.validate(candidate))
        .isInstanceOfSatisfying(
            ContractViolation.class,
            violation -> {
              assertThat(violation.path()).isEqualTo(path);
              assertThat(violation.getMessage()).isEqualTo(message);
            });
  }
}
