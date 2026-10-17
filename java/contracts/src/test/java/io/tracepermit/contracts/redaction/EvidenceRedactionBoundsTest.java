package io.tracepermit.contracts.redaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.POJONode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Synthetic fixtures only. Projections must never change the source used for approval. */
final class EvidenceRedactionBoundsTest {
  @Test
  void validatesMatchedSubtreesForEveryStrategyWithInclusiveDepthBoundary() {
    for (RedactionStrategy strategy : RedactionStrategy.values()) {
      var redactor = new EvidenceRedactor(new RedactionProfile("bounded", List.of(
          RedactionRule.forFieldNames("secret", strategy, "secret")), 2));
      ObjectNode root = JsonSupport.MAPPER.createObjectNode();
      root.putArray("secret").add("synthetic-canary");
      JsonNode result = redactor.redact(root);
      if (strategy == RedactionStrategy.IRREVERSIBLE_HASH) {
        assertThat(result.path("secret").asText()).isEqualTo(CanonicalJson.sha256(root.get("secret")));
      }
      root.withArray("secret").addArray().add("synthetic-deeper-canary");
      assertThatThrownBy(() -> redactor.redact(root))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("Payload exceeds maximum redaction depth limit of 2");
    }
  }

  @Test
  void cyclesFailBeforeHashingOrCopyingIncludingOutsideArgumentProjection() {
    ObjectNode cyclic = JsonSupport.MAPPER.createObjectNode();
    cyclic.set("secret", cyclic);
    for (var redactor : List.of(EvidenceRedactor.enterpriseDefault(),
        EvidenceRedactor.irreversibleHash(), EvidenceRedactor.strictDrop())) {
      assertThatThrownBy(() -> redactor.redact(cyclic))
          .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("depth limit");
      ObjectNode envelope = JsonSupport.MAPPER.createObjectNode();
      envelope.set("metadata", cyclic);
      assertThatThrownBy(() -> redactor.redactActionArguments(envelope))
          .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("depth limit");
    }
  }

  @Test
  void enforcesNodeAndTextBudgetsEvenForDroppedOrHashedValues() {
    var wide = JsonSupport.MAPPER.createArrayNode();
    for (int i = 0; i < EvidenceRedactor.MAX_NODES - 1; i++) wide.add(i);
    assertThat(EvidenceRedactor.strictDrop().redact(wide).size()).isEqualTo(wide.size());
    wide.add(0);
    assertThatThrownBy(() -> EvidenceRedactor.strictDrop().redact(wide))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("REDACTION_NODE_LIMIT");

    String tooLong = "s".repeat(EvidenceRedactor.MAX_TEXT_LENGTH + 1);
    ObjectNode root = JsonSupport.MAPPER.createObjectNode().put("secret", tooLong);
    assertThatThrownBy(() -> EvidenceRedactor.irreversibleHash().redact(root))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("REDACTION_TEXT_LIMIT");
    assertThatThrownBy(() -> EvidenceRedactor.strictDrop().redact(root))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("REDACTION_TEXT_LIMIT");
    assertThatThrownBy(() -> EvidenceRedactor.enterpriseDefault().redactText(tooLong))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("REDACTION_TEXT_LIMIT");

    var largeText = JsonSupport.MAPPER.createArrayNode();
    String chunk = "s".repeat(EvidenceRedactor.MAX_TEXT_LENGTH);
    for (int i = 0; i < 17; i++) largeText.add(chunk);
    assertThatThrownBy(() -> EvidenceRedactor.enterpriseDefault().redact(largeText))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("REDACTION_TOTAL_TEXT_LIMIT");
    assertThatThrownBy(() -> EvidenceRedactor.enterpriseDefault().redact(new POJONode(tooLong)))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("REDACTION_UNSUPPORTED_VALUE");
  }

  @Test
  void diagnosticTextHonorsEachStrategyWithoutRescanningGeneratedReplacements() {
    String first = "Bearer synthetic-one";
    String second = "Bearer synthetic-two";
    String original = "prefix $\\ " + first + "; " + second + " suffix";
    for (RedactionStrategy strategy : RedactionStrategy.values()) {
      var redactor = new EvidenceRedactor(new RedactionProfile("text", List.of(
          RedactionRule.forValuePattern("bearer", strategy, RedactionProfile.BEARER_TOKEN_PATTERN),
          RedactionRule.forValuePattern("generated", RedactionStrategy.MASK,
              Pattern.compile("sha256:[a-f0-9]+|\\[REDACTED:bearer\\]"))), 3));
      String a = switch (strategy) {
        case MASK -> "[REDACTED:bearer]";
        case DROP -> "";
        case IRREVERSIBLE_HASH -> CanonicalJson.sha256(TextNode.valueOf(first));
      };
      String b = switch (strategy) {
        case MASK -> "[REDACTED:bearer]";
        case DROP -> "";
        case IRREVERSIBLE_HASH -> CanonicalJson.sha256(TextNode.valueOf(second));
      };
      assertThat(redactor.redactText(original)).isEqualTo("prefix $\\ " + a + "; " + b + " suffix");
    }
    var overlap = new EvidenceRedactor(new RedactionProfile("overlap", List.of(
        RedactionRule.forValuePattern("first", RedactionStrategy.MASK, Pattern.compile("abc")),
        RedactionRule.forValuePattern("second", RedactionStrategy.DROP, Pattern.compile("bc"))), 3));
    assertThat(overlap.redactText("abc!")).isEqualTo("[REDACTED:first]!");
    var empty = new EvidenceRedactor(new RedactionProfile("empty", List.of(
        RedactionRule.forValuePattern("empty", RedactionStrategy.MASK, Pattern.compile("(?=a)"))), 3));
    assertThatThrownBy(() -> empty.redactText("a"))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("REDACTION_EMPTY_PATTERN_MATCH");
  }

  @Test
  void diagnosticExpansionFailsClosedWithoutReturningPartialOutput() {
    var redactor = new EvidenceRedactor(new RedactionProfile("expansion", List.of(
        RedactionRule.forValuePattern("large".repeat(1000), RedactionStrategy.MASK, Pattern.compile("a"))), 3));
    assertThatThrownBy(() -> redactor.redactText("a".repeat(300)))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("REDACTION_OUTPUT_LIMIT");
  }

  @Test
  void aliasesApplyAcrossProfilesWithoutMaskingBenignNamesOrChangingSource() {
    ObjectNode input = JsonSupport.MAPPER.createObjectNode();
    for (String field : List.of("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "Aws_Session_Token",
        "AWS_SECURITY_TOKEN", "X-Api-Key", "x_api_key", "api-key", "access-token",
        "refresh-token", "client-secret", "private-key", "Proxy-Authorization")) {
      input.put(field, "synthetic-alias-canary");
    }
    input.put("aws_region", "simulated-region");
    input.put("session_token_count", 2);
    input.put("enabled", true);
    for (var redactor : List.of(EvidenceRedactor.enterpriseDefault(),
        EvidenceRedactor.irreversibleHash(), EvidenceRedactor.strictDrop())) {
      JsonNode result = redactor.redact(input);
      assertThat(result.toString()).doesNotContain("synthetic-alias-canary");
      assertThat(result.path("aws_region").asText()).isEqualTo("simulated-region");
      assertThat(result.path("session_token_count").asInt()).isEqualTo(2);
      assertThat(result.path("enabled").asBoolean()).isTrue();
    }
    assertThat(input.path("X-Api-Key").asText()).isEqualTo("synthetic-alias-canary");
  }

  @Test
  void projectionsDoNotMutateCanonicalHashesOrAliasMutableSourceContainers() throws Exception {
    ObjectNode input = (ObjectNode) JsonSupport.MAPPER.readTree("""
        {"action":{"arguments":{"secret":"synthetic-projection-canary","port":443}},
         "identity":{"label":"simulated-requester"},"evidence":{"actionHash":"sha256:synthetic"}}
        """);
    String hash = CanonicalJson.sha256(input);
    ObjectNode result = (ObjectNode) EvidenceRedactor.enterpriseDefault().redactActionArguments(input);
    assertThat(result.at("/action/arguments/secret").asText()).isEqualTo("[REDACTED:credentials]");
    assertThat(result.get("evidence")).isEqualTo(input.get("evidence"));
    ((ObjectNode) result.get("identity")).put("label", "changed-projection");
    ((ObjectNode) result.at("/action/arguments")).put("port", 8443);
    assertThat(CanonicalJson.sha256(input)).isEqualTo(hash);
    assertThat(input.at("/identity/label").asText()).isEqualTo("simulated-requester");
    ObjectNode emptyEnvelope = JsonSupport.MAPPER.createObjectNode().put("eventId", "synthetic-event");
    assertThat(EvidenceRedactor.enterpriseDefault().redactActionArguments(emptyEnvelope)).isEqualTo(emptyEnvelope);
    assertThat(EvidenceRedactor.enterpriseDefault().redactActionArguments(TextNode.valueOf("safe")))
        .isEqualTo(TextNode.valueOf("safe"));
    assertThat(EvidenceRedactor.enterpriseDefault().redactText("  ")).isEqualTo("  ");
    ObjectNode nullSecret = JsonSupport.MAPPER.createObjectNode().putNull("secret");
    assertThat(EvidenceRedactor.irreversibleHash().redact(nullSecret).path("secret").asText())
        .isEqualTo(CanonicalJson.sha256(null));
  }

  @Test
  void dropForStandaloneAndArrayValuesPreservesArrayPositions() {
    var redactor = new EvidenceRedactor(new RedactionProfile("drop-text", List.of(
        RedactionRule.forValuePattern("canary", RedactionStrategy.DROP, Pattern.compile("synthetic"))), 3));
    var array = JsonSupport.MAPPER.createArrayNode().add("synthetic-canary").add("safe").addNull();
    assertThat(redactor.redact(array)).isEqualTo(JsonSupport.MAPPER.createArrayNode().addNull().add("safe").addNull());
    assertThat(redactor.redact(TextNode.valueOf("synthetic-canary")).isNull()).isTrue();
    assertThat(redactor.redactText("synthetic")).isEmpty();
  }
}
