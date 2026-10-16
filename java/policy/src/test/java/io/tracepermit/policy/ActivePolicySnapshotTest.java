package io.tracepermit.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class ActivePolicySnapshotTest {
  private static final Instant NOW = Instant.parse("2026-10-16T10:00:00Z");

  @Test
  void snapshotValidatesConstructorArguments() {
    JsonNode doc = samplePolicy(1);
    String hash = CanonicalJson.sha256(doc);

    assertThatThrownBy(() -> new ActivePolicySnapshot(null, "pol-1", 1, hash, doc, 1, NOW))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ActivePolicySnapshot("default", null, 1, hash, doc, 1, NOW))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ActivePolicySnapshot("default", "pol-1", 0, hash, doc, 1, NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("version must be positive");
    assertThatThrownBy(() -> new ActivePolicySnapshot("default", "pol-1", 1, null, doc, 1, NOW))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ActivePolicySnapshot("default", "pol-1", 1, "invalid-hash", doc, 1, NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid contentHash format");
    assertThatThrownBy(() -> new ActivePolicySnapshot("default", "pol-1", 1, hash, null, 1, NOW))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ActivePolicySnapshot("default", "pol-1", 1, hash, doc, 0, NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("activationGeneration must be positive");
    assertThatThrownBy(() -> new ActivePolicySnapshot("default", "pol-1", 1, hash, doc, 1, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void snapshotDocumentIsDefensivelyCopied() {
    ObjectNode doc = samplePolicy(1);
    String hash = CanonicalJson.sha256(doc);
    ActivePolicySnapshot snapshot = new ActivePolicySnapshot(
        "default", "pol-1", 1, hash, doc, 1, NOW);

    // Mutate the original document
    doc.put("name", "mutated-name");
    assertThat(snapshot.document().path("name").asText()).isEqualTo("Sample Policy 1");

    // Mutate the getter return value
    ObjectNode retrieved = (ObjectNode) snapshot.document();
    retrieved.put("name", "another-mutation");
    assertThat(snapshot.document().path("name").asText()).isEqualTo("Sample Policy 1");
  }

  @Test
  void snapshotActivationSequenceAndGenerationAgree() {
    JsonNode doc = samplePolicy(1);
    String hash = CanonicalJson.sha256(doc);
    ActivePolicySnapshot snapshot = new ActivePolicySnapshot(
        "default", "pol-1", 1, hash, doc, 42L, NOW);

    assertThat(snapshot.activationGeneration()).isEqualTo(42L);
    assertThat(snapshot.activationSequence()).isEqualTo(42L);
  }

  @Test
  void lifecycleAdvancesGenerationOnActivationAndRollbackNotIdempotentActivation() {
    PolicyLifecycleService service = new PolicyLifecycleService();
    ObjectNode v1 = samplePolicy(1);
    ObjectNode v2 = samplePolicy(2);

    service.stage(v1, NOW);
    service.stage(v2, NOW.plusSeconds(1));

    // Initially no active policy
    assertThat(service.activePolicySnapshot("pol-sample")).isEmpty();

    // 1. First activation: generation starts at 1
    service.activate("pol-sample", 1, NOW.plusSeconds(2));
    ActivePolicySnapshot snap1 = service.activePolicySnapshot("pol-sample").orElseThrow();
    assertThat(snap1.version()).isEqualTo(1);
    assertThat(snap1.activationGeneration()).isEqualTo(1L);

    // 2. Idempotent re-activation of v1: generation remains 1
    service.activate("pol-sample", 1, NOW.plusSeconds(3));
    ActivePolicySnapshot snap1Re = service.activePolicySnapshot("pol-sample").orElseThrow();
    assertThat(snap1Re.version()).isEqualTo(1);
    assertThat(snap1Re.activationGeneration()).isEqualTo(1L);

    // 3. Activating v2 advances generation to 2
    service.activate("pol-sample", 2, NOW.plusSeconds(4));
    ActivePolicySnapshot snap2 = service.activePolicySnapshot("pol-sample").orElseThrow();
    assertThat(snap2.version()).isEqualTo(2);
    assertThat(snap2.activationGeneration()).isEqualTo(2L);

    // 4. Rollback to v1 advances generation to 3 (even though version is back to 1)
    service.rollback("pol-sample", NOW.plusSeconds(5), "revert to v1");
    ActivePolicySnapshot snap3 = service.activePolicySnapshot("pol-sample").orElseThrow();
    assertThat(snap3.version()).isEqualTo(1);
    assertThat(snap3.activationGeneration()).isEqualTo(3L);
    assertThat(snap3.contentHash()).isEqualTo(snap1.contentHash());

    // Defensive mutation check on service returned snapshot
    ObjectNode doc = (ObjectNode) snap3.document();
    doc.put("name", "corrupted");
    assertThat(service.activePolicySnapshot("pol-sample").orElseThrow().document().path("name").asText())
        .isEqualTo("Sample Policy 1");
  }

  private static ObjectNode samplePolicy(int version) {
    ObjectNode policy = JsonSupport.MAPPER.createObjectNode();
    policy.put("schemaVersion", "1.0");
    policy.put("id", "pol-sample");
    policy.put("version", version);
    policy.put("name", "Sample Policy " + version);
    policy.put("description", "A sample policy for testing snapshot generation.");
    policy.put("enabled", true);
    policy.putObject("defaultDecision")
        .put("outcome", "require_approval")
        .put("reasonCode", "SAMPLE_REQUIRE_APPROVAL");
    policy.putArray("rules");
    return policy;
  }
}
