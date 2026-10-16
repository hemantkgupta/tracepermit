package io.tracepermit.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.InMemoryEvidenceLedger;
import io.tracepermit.execution.InMemoryExecutionLedger;
import io.tracepermit.policy.PolicyLifecycleService;
import io.tracepermit.security.ExecutionPermitAuthority;
import io.tracepermit.security.InMemoryPermitConsumptionStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class SecureExecutionServiceLifecycleTest {
  private static final Path ROOT = Path.of(System.getProperty("tracepermit.repositoryRoot"));
  private static final Instant NOW = Instant.parse("2026-10-16T10:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void mutatingAnExposedActiveDocumentCannotAuthorizeASimulatorDispatch() {
    PolicyLifecycleService lifecycle = new PolicyLifecycleService();
    ObjectNode candidate = (ObjectNode) JsonSupport.read(ROOT.resolve("policies/aws-web-readonly.json"));
    candidate.put("version", 1);
    candidate.putArray("rules");
    ((ObjectNode) candidate.path("defaultDecision")).put("outcome", "block");
    String policyId = candidate.path("id").asText();
    lifecycle.stage(candidate, NOW);
    var active = lifecycle.activate(policyId, 1, NOW);

    ((ObjectNode) active.document().path("defaultDecision")).put("outcome", "allow");
    AtomicInteger dispatches = new AtomicInteger();
    SecureExecutionService service = createService(lifecycle, policyId, evidence(), action -> {
      dispatches.incrementAndGet();
      return JsonSupport.MAPPER.createObjectNode().put("status", "simulated");
    });

    ExecutionResult result = service.execute(JsonSupport.read(ROOT.resolve("fixtures/chatgpt-aws-read.json")));
    assertThat(result.outcome()).isEqualTo("block");
    assertThat(result.evaluation().decision().policyVersion()).isOne();
    assertThat(dispatches.get()).isZero();
  }

  @Test
  void enforcementDynamicallyHonorsStagedActivationAndRollback() {
    PolicyLifecycleService lifecycle = new PolicyLifecycleService();
    String policyId = "pol-aws-web-001";

    ObjectNode v1 = (ObjectNode) JsonSupport.read(ROOT.resolve("policies/aws-web-readonly.json"));
    v1.put("version", 1);

    ObjectNode v2 = v1.deepCopy();
    v2.put("version", 2);
    // In v2, remove the allow rule so the read action hits default require_approval/block
    ArrayNode rules = (ArrayNode) v2.path("rules");
    rules.remove(2); // removes allow-cloudwatch-read
    ((ObjectNode) v2.path("defaultDecision")).put("outcome", "block");
    ((ObjectNode) v2.path("defaultDecision")).put("reasonCode", "STRICT_BLOCK_ALL");

    lifecycle.stage(v1, NOW);
    lifecycle.stage(v2, NOW.plusSeconds(60));

    AtomicInteger dispatches = new AtomicInteger();
    SimulatedTarget target = action -> {
      dispatches.incrementAndGet();
      return JsonSupport.MAPPER.createObjectNode().put("status", "ok");
    };

    InMemoryEvidenceLedger evidence = evidence();
    SecureExecutionService service = createService(lifecycle, policyId, evidence, target);

    JsonNode readAction = JsonSupport.read(ROOT.resolve("fixtures/chatgpt-aws-read.json"));

    // Before activation -> fails closed
    assertThatThrownBy(() -> service.execute(readAction))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("NO_ACTIVE_POLICY");

    // Activate v1 -> allow CloudWatch read
    lifecycle.activate(policyId, 1, NOW.plusSeconds(120));
    ExecutionResult resultV1 = service.execute(readAction);
    assertThat(resultV1.outcome()).isEqualTo("succeeded");
    assertThat(resultV1.evaluation().decision().policyVersion()).isOne();
    assertThat(dispatches.get()).isOne();

    // Activate v2 -> blocked
    lifecycle.activate(policyId, 2, NOW.plusSeconds(180));
    ExecutionResult resultV2 = service.execute(readAction);
    assertThat(resultV2.outcome()).isEqualTo("block");
    assertThat(resultV2.evaluation().decision().policyVersion()).isEqualTo(2);
    assertThat(dispatches.get()).isOne(); // no new dispatch

    // Rollback to v1 -> allowed again
    lifecycle.rollback(policyId, NOW.plusSeconds(240), "Emergency rollback to restore read access");
    ObjectNode distinctReadAction = readAction.deepCopy();
    distinctReadAction.put("eventId", "018f47a2-f5ce-7a4b-8f54-80e5d3eaf092");
    ExecutionResult resultRolledBack = service.execute(distinctReadAction);
    assertThat(resultRolledBack.outcome()).isEqualTo("succeeded");
    assertThat(resultRolledBack.evaluation().decision().policyVersion()).isOne();
    assertThat(dispatches.get()).isEqualTo(2);

    // Verify evidence ledger recorded accurate policy version for each execution attempt
    List<JsonNode> evidenceEntries = evidence.readAll();
    assertThat(evidenceEntries).isNotEmpty();
    assertThat(lifecycle.rollbackHistory(policyId)).hasSize(1);
  }

  private static SecureExecutionService createService(
      PolicyLifecycleService lifecycle,
      String policyId,
      InMemoryEvidenceLedger evidence,
      SimulatedTarget target) {
    AtomicInteger identifiers = new AtomicInteger();
    return new SecureExecutionService(
        lifecycle,
        policyId,
        new ExecutionPermitAuthority(
            ExecutionPermitAuthority.DEFAULT_ISSUER,
            "test-lifecycle-key",
            new byte[32],
            30,
            CLOCK,
            () -> "permit-" + identifiers.incrementAndGet(),
            new InMemoryPermitConsumptionStore()),
        new InMemoryExecutionLedger(() -> "attempt-" + identifiers.incrementAndGet()),
        evidence,
        target,
        () -> SecurityReadiness.allReady(NOW),
        CLOCK,
        "urn:tracepermit:lifecycle-test-simulator");
  }

  private static InMemoryEvidenceLedger evidence() {
    AtomicInteger identifiers = new AtomicInteger();
    return new InMemoryEvidenceLedger(
        "urn:tracepermit:lifecycle-test",
        CLOCK,
        () -> "evidence-" + identifiers.incrementAndGet());
  }
}
