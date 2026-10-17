package io.tracepermit.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boundary qualification for the canonical action contract, complementing the
 * frozen vectors in contracts/vectors/validation-v1.json that conformance
 * drives (PortableContractsTest): defensive-copy isolation on both sides of
 * ActionValidator.validate, repeated-validation input stability, conservative
 * rejection of unsupported schema versions and malformed field types, causal
 * lineage structure invariants, delegation narrowing, and delegation
 * temporal-window edges. Validating the internal normalized contract does not
 * authenticate a caller, verify a token signature, enroll an endpoint, or
 * authorize an effect: trusted-adapter fields in these fixtures stand for
 * verified adapter output, not a public API that accepts self-asserted trust.
 * A causal hop is not an authority grant. Deep copies isolate caller and
 * result trees; they do not make the returned JsonNode immutable.
 */
final class ActionValidatorBoundaryQualificationTest {
  private static final Path ROOT = Path.of(System.getProperty("tracepermit.repositoryRoot"));
  private static final String WEB_FIXTURE = "fixtures/chatgpt-aws-read.json";
  private static final String CHAIN_FIXTURE = "fixtures/delegation-chain-valid.json";

  @Test
  void validatedResultIsIndependentOfCallerTreeMutationsAfterValidation() {
    ObjectNode event = webAction();
    JsonNode result = ActionValidator.validate(event);
    assertThat(result).isNotSameAs(event);
    JsonNode resultSnapshot = result.deepCopy();
    String resultHash = CanonicalJson.sha256(result);

    ((ObjectNode) event.path("identity").path("subject")).put("id", "user:mallory@example.invalid");
    ((ObjectNode) event.path("identity").path("subject")).put("assurance", "verified");
    ((ObjectNode) event.path("lineage")).put("rootPrincipalId", "user:mallory@example.invalid");
    ((ObjectNode) event.path("lineage").path("hops").get(0)).put("subjectId", "user:mallory@example.invalid");
    ((ObjectNode) event.path("action").path("target")).put("system", "jira");
    ((ObjectNode) event.path("action").path("arguments")).put("limit", 1);
    ((ObjectNode) event.path("clientClaims")).put("providerWorkspace", "relabelled");
    event.put("authenticated", true);

    assertThat(result).as("validated result must not alias the caller tree").isEqualTo(resultSnapshot);
    assertThat(CanonicalJson.sha256(result)).as("canonical hash must not shift after caller mutation").isEqualTo(resultHash);
    assertThat(result.path("identity").path("subject").path("id").asText()).isEqualTo("user:alice@example.com");
    assertThat(result.path("lineage").path("rootPrincipalId").asText()).isEqualTo("user:alice@example.com");
    assertThat(result.path("lineage").path("hops").get(0).path("subjectId").asText()).isEqualTo("user:alice@example.com");
    assertThat(result.path("action").path("target").path("system").asText()).isEqualTo("aws");
    assertThat(result.path("action").path("arguments").path("limit").asInt()).isEqualTo(50);
    assertThat(result.path("clientClaims").path("providerWorkspace").asText()).isEqualTo("unverified-workspace-label");
    assertThat(result.has("authenticated")).isFalse();
  }

  @Test
  void mutatingResultDoesNotAlterCallerTree() {
    ObjectNode event = webAction();
    JsonNode callerSnapshot = event.deepCopy();
    String callerHash = CanonicalJson.sha256(event);
    JsonNode result = ActionValidator.validate(event);
    assertThat(result).isNotSameAs(event);

    ((ObjectNode) result.path("identity").path("subject")).put("id", "user:mallory@example.invalid");
    ((ObjectNode) result.path("identity").path("evidence").get(0)).put("credentialFingerprint", "sha256:" + "f".repeat(64));
    ((ObjectNode) result.path("action").path("arguments")).put("limit", 1);
    ((ObjectNode) result.path("action").path("target")).put("environment", "dev");
    ((ObjectNode) result.path("lineage").path("hops").get(1)).put("subjectId", "tool:mutated");
    ((ObjectNode) result.path("clientClaims")).put("providerWorkspace", "mutated");

    assertThat(event).as("caller tree must not alias the validated result").isEqualTo(callerSnapshot);
    assertThat(CanonicalJson.sha256(event)).as("caller canonical hash must not shift after result mutation").isEqualTo(callerHash);
    assertThat(event.path("identity").path("subject").path("id").asText()).isEqualTo("user:alice@example.com");
    assertThat(event.path("action").path("arguments").path("limit").asInt()).isEqualTo(50);
    assertThat(event.path("lineage").path("hops").get(1).path("subjectId").asText()).isEqualTo("client:chatgpt-web");
  }

  @Test
  void repeatedValidationDoesNotMutateInputAndIsIdempotent() {
    ObjectNode event = webAction();
    ((ObjectNode) event.path("lineage")).remove("delegations");
    event.remove("clientClaims");
    JsonNode callerSnapshot = event.deepCopy();
    String callerHash = CanonicalJson.sha256(event);

    JsonNode first = ActionValidator.validate(event);
    JsonNode second = ActionValidator.validate(event);
    JsonNode revalidated = ActionValidator.validate(first.deepCopy());

    assertThat(event).as("repeated validation must not mutate the caller input").isEqualTo(callerSnapshot);
    assertThat(CanonicalJson.sha256(event)).isEqualTo(callerHash);
    assertThat(first).as("validation must be deterministic for the same input").isEqualTo(second);
    assertThat(revalidated).as("validating an already-validated document must be a fixed point").isEqualTo(first);
    assertThat(CanonicalJson.sha256(first)).isEqualTo(CanonicalJson.sha256(second));
    assertThat(first.path("lineage").path("delegations").isArray()).isTrue();
    assertThat(first.path("lineage").path("delegations").isEmpty()).isTrue();
    assertThat(first.path("clientClaims").isObject()).isTrue();
    assertThat(first.path("clientClaims").isEmpty()).isTrue();
  }

  @Test
  void unsupportedSchemaVersionsFailClosed() {
    List<String> unsupported = List.of("1.1", "2.0", "0.9", "1.0 ");
    for (String version : unsupported) {
      ObjectNode event = webAction();
      event.put("schemaVersion", version);
      assertThatThrownBy(() -> ActionValidator.validate(event))
          .as("schemaVersion=%s must fail closed", version)
          .isInstanceOfSatisfying(
              ContractViolation.class,
              violation -> {
                assertThat(violation.path()).isEqualTo("schemaVersion");
                assertThat(violation.getMessage()).isEqualTo("must equal 1.0");
              });
    }
    ObjectNode numericVersion = webAction();
    numericVersion.put("schemaVersion", 1);
    assertThatThrownBy(() -> ActionValidator.validate(numericVersion))
        .as("non-string schemaVersion must fail closed")
        .isInstanceOfSatisfying(
            ContractViolation.class,
            violation -> {
              assertThat(violation.path()).isEqualTo("schemaVersion");
              assertThat(violation.getMessage()).isEqualTo("must be a non-empty string");
            });
    assertThatCode(() -> ActionValidator.validate(webAction()))
        .as("the documented schemaVersion 1.0 remains the positive control")
        .doesNotThrowAnyException();
  }

  @Test
  void malformedRequiredFieldTypesAreRejectedOneFaultAtATime() {
    ObjectNode evidenceNotArray = webAction();
    ((ObjectNode) evidenceNotArray.path("identity"))
        .set("evidence", JsonSupport.MAPPER.createObjectNode().put("unexpected", true));
    expectViolation(evidenceNotArray, "identity.evidence", "must be an array", "evidence as object");

    ObjectNode numericEventId = webAction();
    numericEventId.put("eventId", 4711);
    expectViolation(numericEventId, "eventId", "must be a non-empty string", "numeric eventId");

    ObjectNode shortTraceId = webAction();
    shortTraceId.put("traceId", "8f3a2c1e4b5d67890123456789abcde");
    expectViolation(shortTraceId, "traceId", "must be 32 lowercase hexadecimal characters", "31-character traceId");

    ObjectNode dateOnlyObservedAt = webAction();
    dateOnlyObservedAt.put("observedAt", "2026-10-08");
    expectViolation(dateOnlyObservedAt, "observedAt", "must be an ISO-8601 timestamp", "date-only observedAt");

    ObjectNode missingLineage = webAction();
    missingLineage.remove("lineage");
    expectViolation(missingLineage, "lineage", "must be an object", "missing lineage object");
  }

  @Test
  void duplicateHopIdentifiersAreRejected() {
    ObjectNode event = webAction();
    ((ObjectNode) event.path("lineage").path("hops").get(1)).put("hopId", "hop-user");
    expectViolation(event, "lineage.hops[1].hopId", "lineage hop IDs must be unique", "duplicate hop identifier");
    assertThatCode(() -> ActionValidator.validate(webAction()))
        .as("the untouched two-hop causal chain remains valid")
        .doesNotThrowAnyException();
  }

  @Test
  void danglingParentHopReferenceIsRejected() {
    ObjectNode event = webAction();
    ((ObjectNode) event.path("lineage").path("hops").get(1)).put("parentHopId", "hop-missing");
    expectViolation(event, "lineage.hops[1].parentHopId", "must reference an existing lineage hop", "dangling parent hop");
  }

  @Test
  void rootHopInvariantsRequireExactlyOneRootForTheSubject() {
    ObjectNode twoRoots = webAction();
    ((ObjectNode) twoRoots.path("lineage").path("hops").get(1)).remove("parentHopId");
    expectViolation(
        twoRoots, "lineage.hops", "causal lineage requires exactly one root hop for rootPrincipalId", "two root hops");

    ObjectNode foreignRoot = webAction();
    ((ObjectNode) foreignRoot.path("lineage").path("hops").get(0)).put("subjectId", "user:mallory@example.invalid");
    expectViolation(
        foreignRoot, "lineage.hops", "causal lineage requires exactly one root hop for rootPrincipalId", "root hop for a foreign subject");

    ObjectNode noRoot = webAction();
    ((ObjectNode) noRoot.path("lineage").path("hops").get(0)).put("parentHopId", "hop-chatgpt");
    expectViolation(
        noRoot, "lineage.hops", "causal lineage requires exactly one root hop for rootPrincipalId", "no root hop");
  }

  @Test
  void causalLineageIsAcceptedWithoutDelegatedAuthority() {
    JsonNode result = ActionValidator.validate(webAction());
    assertThat(result.path("lineage").path("delegations").isEmpty())
        .as("a purely causal chain validates with no delegation grants at all")
        .isTrue();
    for (int index = 0; index < result.path("lineage").path("hops").size(); index++) {
      assertThat(result.path("lineage").path("hops").get(index).has("delegationTokenId"))
          .as("causal hop %d carries no authority token", index)
          .isFalse();
    }

    ObjectNode claimedDelegation = webAction();
    ((ObjectNode) claimedDelegation.path("lineage").path("hops").get(0)).put("assurance", "delegated");
    expectViolation(
        claimedDelegation,
        "lineage.hops[0].delegationTokenId",
        "delegated lineage hop requires a known delegation token",
        "delegated assurance without a known token");
  }

  @Test
  void narrowedChildDelegationIsAcceptedAsThePositiveBoundary() {
    JsonNode result = ActionValidator.validate(chainAction());
    JsonNode root = result.path("lineage").path("delegations").get(0);
    JsonNode child = result.path("lineage").path("delegations").get(1);

    assertThat(child.path("tokenId").asText()).isEqualTo("delegation-chain-child");
    assertThat(child.path("parentTokenId").asText()).isEqualTo(root.path("tokenId").asText());
    assertThat(child.path("scopes").size()).isLessThan(root.path("scopes").size());
    assertThat(root.path("scopes").toString()).contains(child.path("scopes").get(0).asText());
    assertThat(root.path("resources").toString()).contains(child.path("resources").get(0).asText());
    assertThat(child.path("audience").asText()).isEqualTo(root.path("audience").asText());
    assertThat(child.path("issuedAt").asText()).isGreaterThan(root.path("issuedAt").asText());
    assertThat(child.path("expiresAt").asText()).isLessThan(root.path("expiresAt").asText());
    assertThat(result.path("lineage").path("hops").get(2).path("delegationTokenId").asText())
        .isEqualTo(child.path("tokenId").asText());
    assertThat(CanonicalJson.sha256(result))
        .isEqualTo(CanonicalJson.sha256(ActionValidator.validate(chainAction())));
  }

  @Test
  void observedAtDelegationWindowBoundariesFollowInclusiveLowerAndExclusiveUpperEdges() {
    record Boundary(String label, String observedAt, boolean valid, String expectedPath) {}
    List<Boundary> boundaries =
        List.of(
            new Boundary("observed equals child issuance is inclusive", "2026-10-08T10:19:50.000Z", true, null),
            new Boundary("just before child issuance is rejected", "2026-10-08T10:19:49.999Z", false, "lineage.delegations[1].expiresAt"),
            new Boundary("observed equals child expiry is exclusive", "2026-10-08T10:21:50.000Z", false, "lineage.delegations[1].expiresAt"),
            new Boundary("observed equals root expiry is exclusive", "2026-10-08T10:24:40.000Z", false, "lineage.delegations[0].expiresAt"));
    for (Boundary boundary : boundaries) {
      ObjectNode event = chainAction();
      event.put("observedAt", boundary.observedAt());
      if (boundary.valid()) {
        assertThatCode(() -> ActionValidator.validate(event))
            .as(boundary.label())
            .doesNotThrowAnyException();
      } else {
        expectViolation(
            event, boundary.expectedPath(), "delegation must be valid when the action is observed", boundary.label());
      }
    }
  }

  @Test
  void collapsedDelegationWindowIsRejected() {
    ObjectNode event = chainAction();
    ((ObjectNode) event.path("lineage").path("delegations").get(1)).put("expiresAt", "2026-10-08T10:19:50.000Z");
    expectViolation(
        event, "lineage.delegations[1].expiresAt", "delegation must expire after it is issued", "expiresAt equals issuedAt");
  }

  private static ObjectNode webAction() {
    return (ObjectNode) JsonSupport.read(ROOT.resolve(WEB_FIXTURE));
  }

  private static ObjectNode chainAction() {
    return (ObjectNode) JsonSupport.read(ROOT.resolve(CHAIN_FIXTURE));
  }

  private static void expectViolation(JsonNode event, String expectedPath, String expectedMessage, String label) {
    assertThatThrownBy(() -> ActionValidator.validate(event))
        .as(label)
        .isInstanceOfSatisfying(
            ContractViolation.class,
            violation -> {
              assertThat(violation.path()).as("violation path for %s", label).isEqualTo(expectedPath);
              assertThat(violation.getMessage()).as("violation message for %s", label).isEqualTo(expectedMessage);
            });
  }
}
