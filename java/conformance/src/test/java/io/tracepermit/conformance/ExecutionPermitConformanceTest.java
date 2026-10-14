package io.tracepermit.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.security.ExecutionPermitAuthority;
import io.tracepermit.security.ExecutionPermitException;
import io.tracepermit.security.InMemoryPermitConsumptionStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class ExecutionPermitConformanceTest {
  private static final Path ROOT = Path.of(System.getProperty("tracepermit.repositoryRoot"));

  @Test
  void issuedPermitMatchesGoldenVectorAndIsSingleUse() {
    JsonNode vector = JsonSupport.read(ROOT.resolve("contracts/vectors/execution-permit-v1.json"));
    JsonNode event = JsonSupport.read(ROOT.resolve(vector.path("fixture").asText()));
    Instant now = Instant.parse("2026-10-08T10:15:31.000Z");
    ExecutionPermitAuthority authority = new ExecutionPermitAuthority(
        ExecutionPermitAuthority.DEFAULT_ISSUER,
        "portability-test-key",
        HexFormat.of().parseHex(vector.path("testOnlySigningKeyHex").asText()),
        30,
        Clock.fixed(now, ZoneOffset.UTC),
        () -> "018f47a2-f5ce-7a4b-8f54-80e5d3eaf080",
        new InMemoryPermitConsumptionStore());
    String permit = authority.issue(event, vector.path("evaluation"), vector.path("audience").asText());
    assertThat(permit).isEqualTo(vector.path("expectedCompactPermit").asText());
    String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    int finalIndex = alphabet.indexOf(permit.charAt(permit.length() - 1));
    String nonCanonical = permit.substring(0, permit.length() - 1) + alphabet.charAt(finalIndex + 1);
    assertThatThrownBy(() -> authority.verifyAndConsume(nonCanonical, event, vector.path("audience").asText()))
        .isInstanceOf(ExecutionPermitException.class)
        .extracting(error -> ((ExecutionPermitException) error).code()).isEqualTo("INVALID_EXECUTION_PERMIT");
    assertThat(authority.verify(permit, event, vector.path("audience").asText()).path("permitId").asText())
        .isEqualTo("018f47a2-f5ce-7a4b-8f54-80e5d3eaf080");
    assertThat(authority.verify(permit, event, vector.path("audience").asText()).path("permitId").asText())
        .isEqualTo("018f47a2-f5ce-7a4b-8f54-80e5d3eaf080");
    assertThat(authority.verifyAndConsume(permit, event, vector.path("audience").asText()).path("actionHash").asText())
        .isEqualTo(vector.path("expectedActionHash").asText());
    assertThatThrownBy(() -> authority.verifyAndConsume(permit, event, vector.path("audience").asText()))
        .isInstanceOf(ExecutionPermitException.class)
        .extracting(error -> ((ExecutionPermitException) error).code()).isEqualTo("EXECUTION_PERMIT_REPLAYED");
  }

  @Test
  void concurrentPermitConsumptionHasExactlyOneWinner() throws Exception {
    JsonNode vector = JsonSupport.read(ROOT.resolve("contracts/vectors/execution-permit-v1.json"));
    JsonNode event = JsonSupport.read(ROOT.resolve(vector.path("fixture").asText()));
    ExecutionPermitAuthority authority = new ExecutionPermitAuthority(
        ExecutionPermitAuthority.DEFAULT_ISSUER,
        "concurrency-test-key",
        new byte[32],
        30,
        Clock.fixed(Instant.parse("2026-10-08T10:15:31Z"), ZoneOffset.UTC),
        () -> "018f47a2-f5ce-7a4b-8f54-80e5d3eaf082",
        new InMemoryPermitConsumptionStore());
    String permit = authority.issue(event, vector.path("evaluation"), vector.path("audience").asText());
    CountDownLatch start = new CountDownLatch(1);
    java.util.List<Callable<Boolean>> tasks = java.util.stream.IntStream.range(0, 16)
        .mapToObj(ignored -> (Callable<Boolean>) () -> {
          start.await();
          try {
            authority.verifyAndConsume(permit, event, vector.path("audience").asText());
            return true;
          } catch (ExecutionPermitException error) {
            assertThat(error.code()).isEqualTo("EXECUTION_PERMIT_REPLAYED");
            return false;
          }
        }).toList();
    try (var executor = Executors.newFixedThreadPool(8)) {
      var futures = tasks.stream().map(executor::submit).toList();
      start.countDown();
      long winners = 0;
      for (var future : futures) if (future.get()) winners++;
      assertThat(winners).isEqualTo(1);
    }
  }

  @Test
  void approvalDerivedPermitPreservesOriginalPolicyOutcomeAndApprovalProvenance() {
    JsonNode event = JsonSupport.read(ROOT.resolve("fixtures/api-service-aws-modify.json"));
    ObjectNode decision = JsonSupport.MAPPER.createObjectNode();
    decision.put("outcome", "require_approval");
    decision.put("policyId", "pol-aws-web-001");
    decision.put("policyVersion", 2);
    ObjectNode evaluation = JsonSupport.MAPPER.createObjectNode();
    evaluation.put("evaluationId", event.path("eventId").asText());
    evaluation.put("eventId", event.path("eventId").asText());
    evaluation.set("decision", decision);
    ExecutionPermitAuthority authority = new ExecutionPermitAuthority(
        ExecutionPermitAuthority.DEFAULT_ISSUER,
        "approval-derived-key",
        new byte[32],
        30,
        Clock.fixed(Instant.parse("2026-10-08T10:15:31Z"), ZoneOffset.UTC),
        () -> "permit-approved-1",
        new InMemoryPermitConsumptionStore());

    String permit = authority.issueApproved(
        event, evaluation, "urn:tracepermit:approval-executor", "approval-1", "decision-1");
    JsonNode claims = authority.verify(permit, event, "urn:tracepermit:approval-executor");
    assertThat(claims.path("schemaVersion").asText()).isEqualTo("2.0");
    assertThat(claims.path("decision").path("outcome").asText()).isEqualTo("allow");
    assertThat(claims.path("authorization").path("kind").asText()).isEqualTo("human_approval");
    assertThat(claims.path("authorization").path("approvalRequestId").asText())
        .isEqualTo("approval-1");
    assertThat(claims.path("authorization").path("originalPolicyOutcome").asText())
        .isEqualTo("require_approval");

    JsonNode changed = event.deepCopy();
    ((ObjectNode) changed.path("action").path("arguments")).put("port", 8443);
    assertThatThrownBy(() -> authority.verify(
        permit, changed, "urn:tracepermit:approval-executor"))
        .isInstanceOf(ExecutionPermitException.class)
        .extracting(error -> ((ExecutionPermitException) error).code())
        .isEqualTo("PERMIT_ACTION_MISMATCH");
  }
}
