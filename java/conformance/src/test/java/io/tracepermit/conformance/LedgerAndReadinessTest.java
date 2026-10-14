package io.tracepermit.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.application.SecurityReadiness;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.BeginExecutionResult;
import io.tracepermit.execution.EvidenceIntegrityResult;
import io.tracepermit.execution.ExecutionAttempt;
import io.tracepermit.execution.InMemoryEvidenceLedger;
import io.tracepermit.execution.InMemoryExecutionLedger;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class LedgerAndReadinessTest {
  private static final Path ROOT = Path.of(System.getProperty("tracepermit.repositoryRoot"));
  private static final Instant NOW = Instant.parse("2026-10-08T10:15:31Z");

  @Test
  void duplicateExecutionIsClaimedOnceAndUnknownOutcomeRequiresReconciliation() {
    JsonNode event = JsonSupport.read(ROOT.resolve("fixtures/chatgpt-aws-read.json"));
    InMemoryExecutionLedger ledger = new InMemoryExecutionLedger(() -> "attempt-1");
    String key = InMemoryExecutionLedger.idempotencyKey(event, CanonicalJson.sha256(event));
    BeginExecutionResult first = ledger.begin(event.path("eventId").asText(), "permit-1", key, NOW);
    BeginExecutionResult duplicate = ledger.begin(event.path("eventId").asText(), "permit-2", key, NOW);
    assertThat(first.started()).isTrue();
    assertThat(duplicate.started()).isFalse();
    assertThat(duplicate.attempt().attemptId()).isEqualTo(first.attempt().attemptId());
    ledger.markUnknown(first.attempt().attemptId(), NOW.plusSeconds(1), "TARGET_OUTCOME_UNCONFIRMED");
    ExecutionAttempt reconciled = ledger.reconcile(key, NOW.plusSeconds(2), "succeeded", "OPERATOR_CONFIRMED_SUCCESS", JsonSupport.MAPPER.createObjectNode().put("ok", true));
    assertThat(reconciled.status()).isEqualTo("succeeded");
    assertThat(reconciled.transitions()).extracting(item -> item.to()).containsExactly("executing", "unknown", "succeeded");
  }

  @Test
  void concurrentExecutionClaimsHaveExactlyOneStarter() throws Exception {
    JsonNode event = JsonSupport.read(ROOT.resolve("fixtures/chatgpt-aws-read.json"));
    AtomicInteger identifiers = new AtomicInteger();
    InMemoryExecutionLedger ledger = new InMemoryExecutionLedger(() -> "attempt-" + identifiers.incrementAndGet());
    String key = InMemoryExecutionLedger.idempotencyKey(event, CanonicalJson.sha256(event));
    CountDownLatch start = new CountDownLatch(1);
    java.util.List<Callable<Boolean>> tasks = java.util.stream.IntStream.range(0, 16)
        .mapToObj(index -> (Callable<Boolean>) () -> {
          start.await();
          return ledger.begin(event.path("eventId").asText(), "permit-" + index, key, NOW).started();
        }).toList();
    try (var executor = Executors.newFixedThreadPool(8)) {
      var futures = tasks.stream().map(executor::submit).toList();
      start.countDown();
      long starters = 0;
      for (var future : futures) if (future.get()) starters++;
      assertThat(starters).isEqualTo(1);
    }
  }

  @Test
  void evidenceDetectsMutationAndExternalHeadReplacement() {
    AtomicInteger ids = new AtomicInteger();
    InMemoryEvidenceLedger ledger = new InMemoryEvidenceLedger("urn:test", Clock.fixed(NOW, ZoneOffset.UTC), () -> "evidence-" + ids.incrementAndGet());
    ObjectNode fields = JsonSupport.MAPPER.createObjectNode().put("eventType", "policy.evaluated").put("subjectId", "user:alice@example.com");
    ledger.append(fields, NOW);
    ledger.append(JsonSupport.MAPPER.createObjectNode().put("eventType", "target.succeeded"), NOW.plusSeconds(1));
    EvidenceIntegrityResult valid = ledger.verify(null);
    assertThat(valid.valid()).isTrue();
    assertThat(ledger.verify("sha256:" + "0".repeat(64)).issueCode()).isEqualTo("ANCHOR_MISMATCH");
    java.util.List<JsonNode> copiedRecords = ledger.readAll();
    JsonNode copied = copiedRecords.getFirst();
    ((ObjectNode) copied.path("payload")).put("subjectId", "user:mallory@example.invalid");
    assertThat(InMemoryEvidenceLedger.verifyRecords(copiedRecords, valid.headHash()).issueCode()).isEqualTo("HASH_MISMATCH");
    assertThat(ledger.verify(valid.headHash()).valid()).isTrue();
  }

  @Test
  void readinessCannotClaimReadyWithARequiredDependencyDown() {
    Map<String, Boolean> states = new java.util.HashMap<>();
    SecurityReadiness.REQUIRED.forEach(name -> states.put(name, true));
    states.put("replay_store", false);
    assertThatThrownBy(() -> new SecurityReadiness(true, NOW, states)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void runtimeReadinessMatchesTheLoopbackAndRemoteSafetyProfiles() {
    SecurityReadiness loopback = SecurityReadiness.forRuntime(true, NOW);
    assertThat(loopback.ready()).isTrue();
    assertThat(loopback.dependencies()).allSatisfy((name, ready) -> assertThat(ready).as(name).isTrue());

    SecurityReadiness remote = SecurityReadiness.forRuntime(false, NOW);
    assertThat(remote.ready()).isFalse();
    assertThat(remote.dependencies()).containsEntry("policy", true);
    assertThat(SecurityReadiness.REQUIRED.stream()
        .filter(name -> !remote.dependencies().get(name))
        .toList())
        .containsExactly("authentication", "permit_signer", "replay_store", "evidence_store");
  }
}
