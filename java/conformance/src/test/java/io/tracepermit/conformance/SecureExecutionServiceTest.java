package io.tracepermit.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.application.ExecutionResult;
import io.tracepermit.application.SecureExecutionService;
import io.tracepermit.application.SecurityReadiness;
import io.tracepermit.application.TargetExecutionException;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.InMemoryEvidenceLedger;
import io.tracepermit.execution.InMemoryExecutionLedger;
import io.tracepermit.policy.PolicyEvaluator;
import io.tracepermit.security.ExecutionPermitAuthority;
import io.tracepermit.security.InMemoryPermitConsumptionStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class SecureExecutionServiceTest {
  private static final Path ROOT = Path.of(System.getProperty("tracepermit.repositoryRoot"));
  private static final Instant NOW = Instant.parse("2026-10-08T10:15:31Z");

  @Test
  void allowExecutesSimulatorOnceAndSecurityOutageFailsClosed() {
    JsonNode vector = JsonSupport.read(ROOT.resolve("contracts/vectors/execution-permit-v1.json"));
    JsonNode event = JsonSupport.read(ROOT.resolve(vector.path("fixture").asText()));
    ((ObjectNode) event.path("action").path("target")).put("credential", "must-not-reach-target");
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    AtomicInteger ids = new AtomicInteger();
    AtomicInteger invocations = new AtomicInteger();
    ExecutionPermitAuthority permits = new ExecutionPermitAuthority(
        ExecutionPermitAuthority.DEFAULT_ISSUER, "test-key", new byte[32], 30, clock,
        () -> "permit-" + ids.incrementAndGet(), new InMemoryPermitConsumptionStore());
    InMemoryEvidenceLedger evidence = new InMemoryEvidenceLedger("urn:test", clock, () -> "evidence-" + ids.incrementAndGet());
    SecureExecutionService service = new SecureExecutionService(
        new PolicyEvaluator(),
        JsonSupport.read(ROOT.resolve("policies/aws-web-readonly.json")),
        permits,
        new InMemoryExecutionLedger(() -> "attempt-1"),
        evidence,
        action -> {
          assertThat(action.path("target").has("credential")).isFalse();
          invocations.incrementAndGet();
          return JsonSupport.MAPPER.createObjectNode().put("simulated", true);
        },
        () -> SecurityReadiness.allReady(NOW),
        clock,
        "urn:tracepermit:test-executor");

    ExecutionResult first = service.execute(event);
    ExecutionResult duplicate = service.execute(event);
    assertThat(first.outcome()).isEqualTo("succeeded");
    assertThat(duplicate.outcome()).isEqualTo("succeeded");
    assertThat(invocations).hasValue(1);
    assertThat(evidence.verify(null).valid()).isTrue();

    ExecutionResult approvalRequired = service.execute(JsonSupport.read(ROOT.resolve("fixtures/api-service-aws-modify.json")));
    assertThat(approvalRequired.outcome()).isEqualTo("require_approval");
    assertThat(invocations).hasValue(1);

    for (String unavailableDependency : SecurityReadiness.REQUIRED) {
      java.util.Map<String, Boolean> states = new java.util.HashMap<>();
      SecurityReadiness.REQUIRED.forEach(name -> states.put(name, !name.equals(unavailableDependency)));
      SecureExecutionService unavailable = new SecureExecutionService(
          new PolicyEvaluator(), JsonSupport.read(ROOT.resolve("policies/aws-web-readonly.json")), permits,
          new InMemoryExecutionLedger(() -> "attempt-2"), evidence,
          action -> {
            invocations.incrementAndGet();
            return JsonSupport.MAPPER.nullNode();
          },
          () -> new SecurityReadiness(false, NOW, states),
          clock, "urn:tracepermit:test-executor");
      assertThatThrownBy(() -> unavailable.execute(event)).as(unavailableDependency).hasMessage("SECURITY_DEPENDENCY_UNAVAILABLE");
    }
    assertThat(invocations).hasValue(1);
  }

  @Test
  void confirmedSimulatorFailureIsFailedRatherThanUnknownAndAppendsEvidence() {
    JsonNode vector = JsonSupport.read(ROOT.resolve("contracts/vectors/execution-permit-v1.json"));
    JsonNode event = JsonSupport.read(ROOT.resolve(vector.path("fixture").asText()));
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    AtomicInteger ids = new AtomicInteger();
    InMemoryExecutionLedger executions =
        new InMemoryExecutionLedger(() -> "attempt-" + ids.incrementAndGet());
    InMemoryEvidenceLedger evidence =
        new InMemoryEvidenceLedger(
            "urn:test:failure", clock, () -> "evidence-" + ids.incrementAndGet());
    SecureExecutionService service =
        new SecureExecutionService(
            new PolicyEvaluator(),
            JsonSupport.read(ROOT.resolve("policies/aws-web-readonly.json")),
            new ExecutionPermitAuthority(
                ExecutionPermitAuthority.DEFAULT_ISSUER,
                "test-key",
                new byte[32],
                30,
                clock,
                () -> "permit-" + ids.incrementAndGet(),
                new InMemoryPermitConsumptionStore()),
            executions,
            evidence,
            action -> {
              throw new TargetExecutionException(
                  "SIMULATED_RESOURCE_NOT_FOUND", "The simulated resource does not exist.");
            },
            () -> SecurityReadiness.allReady(NOW),
            clock,
            "urn:tracepermit:test-executor");

    assertThatThrownBy(() -> service.execute(event))
        .isInstanceOf(TargetExecutionException.class)
        .extracting(error -> ((TargetExecutionException) error).code())
        .isEqualTo("SIMULATED_RESOURCE_NOT_FOUND");
    assertThat(executions.findByIdempotencyKey(InMemoryExecutionLedger.idempotencyKey(event, CanonicalJson.sha256(event))).status())
        .isEqualTo("failed");
    assertThat(evidence.readAll())
        .extracting(item -> item.path("payload").path("eventType").asText())
        .containsExactly(
            "action.observed",
            "policy.evaluated",
            "authorization.issued",
            "target.dispatched",
            "target.failed");
  }
}
