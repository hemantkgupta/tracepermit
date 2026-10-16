package io.tracepermit.contracts.redaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.tracepermit.contracts.JsonSupport;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Retained independent review probes; all credential-like values are synthetic canaries. */
final class RedactionBoundaryReviewTest {
  @Test
  void hashingMatchedSubtreesMustRespectConfiguredTraversalDepth() {
    var profile = new RedactionProfile("review-bounded-hash", List.of(
        RedactionRule.forFieldNames("secret", RedactionStrategy.IRREVERSIBLE_HASH, "secret")), 2);
    ObjectNode root = JsonSupport.MAPPER.createObjectNode();
    ObjectNode child = root.putObject("secret");
    for (int i = 0; i < 10; i++) child = child.putObject("nested");
    child.put("value", "synthetic-review-canary");

    assertThatThrownBy(() -> new EvidenceRedactor(profile).redact(root))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void hashProfileMustHonorItsStrategyForDiagnosticText() {
    var redactor = EvidenceRedactor.irreversibleHash();
    String raw = "Bearer synthetic-review-token";
    String structured = redactor.redact(TextNode.valueOf(raw)).asText();

    assertThat(structured).startsWith("sha256:");
    assertThat(redactor.redactText(raw)).isEqualTo(structured);
  }

  @Test
  void defaultCredentialProjectionMustHideStandardAwsAndApiHeaderAliases() {
    ObjectNode root = JsonSupport.MAPPER.createObjectNode();
    root.put("AWS_SECRET_ACCESS_KEY", "synthetic-review-aws-canary");
    root.put("AWS_SESSION_TOKEN", "synthetic-review-session-canary");
    root.put("X-Api-Key", "synthetic-review-api-canary");

    assertThat(EvidenceRedactor.enterpriseDefault().redact(root).toString())
        .doesNotContain("synthetic-review-aws-canary", "synthetic-review-session-canary",
            "synthetic-review-api-canary");
  }
}
