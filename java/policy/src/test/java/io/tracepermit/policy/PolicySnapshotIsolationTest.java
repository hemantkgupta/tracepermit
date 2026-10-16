package io.tracepermit.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

final class PolicySnapshotIsolationTest {
  private static final Path ROOT = Path.of(System.getProperty("tracepermit.repositoryRoot"));
  private static final Instant NOW = Instant.parse("2026-10-16T10:00:00Z");

  @Test
  void callerAndStagedQueryMutationsCannotChangeFutureActivation() {
    PolicyLifecycleService service = new PolicyLifecycleService();
    ObjectNode candidate = blockedPolicy(1);
    JsonNode expected = candidate.deepCopy();
    PolicyVersionRecord staged = service.stage(candidate, NOW);
    changeToAllow(candidate);

    List<PolicyVersionRecord> exposed = List.of(
        staged,
        service.getVersion(staged.policyId(), 1).orElseThrow(),
        service.listVersions(staged.policyId()).getFirst());
    for (PolicyVersionRecord record : exposed) {
      changeToAllow((ObjectNode) record.document());
      assertThat(record.document()).isEqualTo(expected);
      assertThat(record.contentHash()).isEqualTo(CanonicalJson.sha256(expected));
    }

    service.activate(staged.policyId(), 1, NOW);
    assertThat(service.evaluateActive(staged.policyId(), readAction(), ClassificationContext.EMPTY)
        .decision().outcome()).isEqualTo("block");
  }

  @Test
  void everyActiveRecordAccessorPreservesDecisionVersionAndHash() {
    PolicyLifecycleService service = new PolicyLifecycleService();
    ObjectNode candidate = blockedPolicy(1);
    PolicyVersionRecord staged = service.stage(candidate, NOW);
    PolicyVersionRecord activated = service.activate(staged.policyId(), 1, NOW);
    List<PolicyVersionRecord> exposed = List.of(
        activated,
        service.activeVersion(staged.policyId()).orElseThrow(),
        service.getVersion(staged.policyId(), 1).orElseThrow(),
        service.listVersions(staged.policyId()).getFirst(),
        service.stage(candidate, NOW.plusSeconds(1)));

    for (PolicyVersionRecord record : exposed) {
      ObjectNode copy = (ObjectNode) record.document();
      changeToAllow(copy);
      copy.put("version", 99);
      copy.putArray("rules").addObject().put("unrecognized", true);
      PolicyVersionRecord active = service.activeVersion(staged.policyId()).orElseThrow();
      assertThat(active.document()).isEqualTo(candidate);
      assertThat(active.contentHash()).isEqualTo(CanonicalJson.sha256(active.document()));
      PolicyDecision decision = service.evaluateActive(
          staged.policyId(), readAction(), ClassificationContext.EMPTY).decision();
      assertThat(decision.outcome()).isEqualTo("block");
      assertThat(decision.policyVersion()).isOne();
      assertThat(service.simulate(staged.policyId(), 1, readAction(), NOW).outcome())
          .isEqualTo("block");
    }
  }

  @Test
  void deprecatedDocumentMutationCannotChangeRollbackTarget() {
    PolicyLifecycleService service = new PolicyLifecycleService();
    ObjectNode first = blockedPolicy(1);
    ObjectNode second = blockedPolicy(2);
    changeToAllow(second);
    PolicyVersionRecord staged = service.stage(first, NOW);
    service.stage(second, NOW);
    service.activate(staged.policyId(), 1, NOW);
    service.activate(staged.policyId(), 2, NOW.plusSeconds(1));
    changeToAllow((ObjectNode) service.getVersion(staged.policyId(), 1).orElseThrow().document());

    service.rollback(staged.policyId(), NOW.plusSeconds(2), "synthetic regression rollback");
    PolicyVersionRecord restored = service.activeVersion(staged.policyId()).orElseThrow();
    changeToAllow((ObjectNode) restored.document());
    assertThat(restored.document()).isEqualTo(first);
    assertThat(restored.contentHash()).isEqualTo(CanonicalJson.sha256(first));
    assertThat(service.evaluateActive(staged.policyId(), readAction(), ClassificationContext.EMPTY)
        .decision().outcome()).isEqualTo("block");
  }

  private static ObjectNode blockedPolicy(int version) {
    ObjectNode policy = (ObjectNode) JsonSupport.read(ROOT.resolve("policies/aws-web-readonly.json"));
    policy.put("version", version);
    policy.putArray("rules");
    ((ObjectNode) policy.path("defaultDecision")).put("outcome", "block");
    return policy;
  }

  private static void changeToAllow(ObjectNode policy) {
    ((ObjectNode) policy.path("defaultDecision")).put("outcome", "allow");
  }

  private static JsonNode readAction() {
    return JsonSupport.read(ROOT.resolve("fixtures/chatgpt-aws-read.json"));
  }
}
