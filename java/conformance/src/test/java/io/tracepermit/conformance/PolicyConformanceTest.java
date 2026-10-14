package io.tracepermit.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.policy.PolicyEvaluation;
import io.tracepermit.policy.PolicyEvaluator;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

final class PolicyConformanceTest {
  private static final Path ROOT = Path.of(System.getProperty("tracepermit.repositoryRoot"));

  @Test
  void classifierAndPolicyMatchSharedGoldenVectors() {
    JsonNode vectors = JsonSupport.read(ROOT.resolve("contracts/vectors/policy-v1.json"));
    JsonNode policy = JsonSupport.read(ROOT.resolve(vectors.path("policy").asText()));
    PolicyEvaluator evaluator = new PolicyEvaluator();
    for (JsonNode item : vectors.path("cases")) {
      JsonNode event = JsonSupport.read(ROOT.resolve(item.path("fixture").asText()));
      PolicyEvaluation actual = evaluator.evaluate(event, policy);
      JsonNode expected = item.path("expected");
      assertThat(CanonicalJson.sha256(event)).isEqualTo(item.path("actionHash").asText());
      assertThat(actual.classification().actionClass()).isEqualTo(expected.path("classification").path("actionClass").asText());
      assertThat(actual.classification().severity()).isEqualTo(expected.path("classification").path("severity").asText());
      assertThat(actual.classification().score()).isEqualTo(expected.path("classification").path("score").asInt());
      assertThat(actual.classification().signals()).containsExactlyElementsOf(JsonSupport.MAPPER.convertValue(expected.path("classification").path("signals"), JsonSupport.MAPPER.getTypeFactory().constructCollectionType(java.util.List.class, String.class)));
      assertThat(actual.decision().outcome()).isEqualTo(expected.path("decision").path("outcome").asText());
      assertThat(actual.decision().reasonCode()).isEqualTo(expected.path("decision").path("reasonCode").asText());
      String matched = expected.path("decision").has("matchedRuleId") ? expected.path("decision").path("matchedRuleId").asText() : null;
      assertThat(actual.decision().matchedRuleId()).isEqualTo(matched);
    }
  }
}
