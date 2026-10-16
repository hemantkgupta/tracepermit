package io.tracepermit.contracts.redaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;

/**
 * Thread-safe engine that applies field-level redaction and irreversible transformation
 * to JSON documents, action arguments, audit evidence, and diagnostic text.
 *
 * <p>Enforces fail-closed redaction bounds (SEC-012, DATA-003) and depth limits to prevent
 * recursion attacks while preserving cryptographic provenance (actionHash).
 */
public final class EvidenceRedactor {
  private static final EvidenceRedactor ENTERPRISE_DEFAULT =
      new EvidenceRedactor(RedactionProfile.enterpriseDefault());
  private static final EvidenceRedactor IRREVERSIBLE_HASH =
      new EvidenceRedactor(RedactionProfile.irreversibleHash());
  private static final EvidenceRedactor STRICT_DROP =
      new EvidenceRedactor(RedactionProfile.strictDrop());

  private final RedactionProfile profile;

  public EvidenceRedactor(RedactionProfile profile) {
    this.profile = Objects.requireNonNull(profile, "profile");
  }

  public static EvidenceRedactor enterpriseDefault() {
    return ENTERPRISE_DEFAULT;
  }

  public static EvidenceRedactor irreversibleHash() {
    return IRREVERSIBLE_HASH;
  }

  public static EvidenceRedactor strictDrop() {
    return STRICT_DROP;
  }

  public RedactionProfile profile() {
    return profile;
  }

  /**
   * Redacts any arbitrary JSON node recursively, producing a clean sanitized copy.
   */
  public JsonNode redact(JsonNode input) {
    if (input == null || input.isNull()) {
      return NullNode.getInstance();
    }
    return redactNode(input, "", 0);
  }

  /**
   * Targets and redacts {@code action.arguments} within a canonical action proposal,
   * preserving all other envelope and identity fields untouched.
   */
  public JsonNode redactActionArguments(JsonNode canonicalAction) {
    if (canonicalAction == null || !canonicalAction.isObject()) {
      return redact(canonicalAction);
    }
    ObjectNode result = (ObjectNode) canonicalAction.deepCopy();
    JsonNode actionNode = result.get("action");
    if (actionNode instanceof ObjectNode actionObj) {
      JsonNode argsNode = actionObj.get("arguments");
      if (argsNode != null) {
        JsonNode redactedArgs = redactNode(argsNode, "action.arguments", 1);
        actionObj.set("arguments", redactedArgs);
      }
    }
    return result;
  }

  /**
   * Redacts sensitive value patterns from diagnostic or log text.
   */
  public String redactText(String rawText) {
    if (rawText == null || rawText.isBlank()) {
      return rawText;
    }
    String current = rawText;
    for (RedactionRule rule : profile.rules()) {
      if (rule.valuePattern() != null) {
        Matcher matcher = rule.valuePattern().matcher(current);
        if (matcher.find()) {
          String replacement = maskIndicator(rule);
          current = matcher.replaceAll(Matcher.quoteReplacement(replacement));
        }
      }
    }
    return current;
  }

  private JsonNode redactNode(JsonNode node, String path, int depth) {
    if (depth > profile.maxDepth()) {
      throw new IllegalArgumentException(
          "Payload exceeds maximum redaction depth limit of " + profile.maxDepth());
    }
    if (node == null || node.isNull()) {
      return NullNode.getInstance();
    }
    if (node.isObject()) {
      return redactObject((ObjectNode) node, path, depth);
    }
    if (node.isArray()) {
      return redactArray((ArrayNode) node, path, depth);
    }
    if (node.isTextual()) {
      return redactTextNode((TextNode) node, path);
    }
    return node.deepCopy();
  }

  private JsonNode redactObject(ObjectNode objectNode, String currentPath, int depth) {
    ObjectNode result = JsonSupport.MAPPER.createObjectNode();
    Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();

    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> entry = fields.next();
      String fieldName = entry.getKey();
      JsonNode child = entry.getValue();
      String fieldPath = currentPath.isEmpty() ? fieldName : currentPath + "." + fieldName;

      RedactionRule matchedRule = findMatchingRule(fieldName, fieldPath);
      if (matchedRule != null) {
        switch (matchedRule.strategy()) {
          case DROP -> {
            // Field is omitted completely from result
          }
          case IRREVERSIBLE_HASH -> {
            String hash = sha256Fingerprint(child);
            result.put(fieldName, hash);
          }
          case MASK -> {
            result.put(fieldName, maskIndicator(matchedRule));
          }
        }
      } else {
        result.set(fieldName, redactNode(child, fieldPath, depth + 1));
      }
    }
    return result;
  }

  private JsonNode redactArray(ArrayNode arrayNode, String currentPath, int depth) {
    ArrayNode result = JsonSupport.MAPPER.createArrayNode();
    for (int i = 0; i < arrayNode.size(); i++) {
      JsonNode item = arrayNode.get(i);
      String itemPath = currentPath + "[" + i + "]";
      result.add(redactNode(item, itemPath, depth + 1));
    }
    return result;
  }

  private JsonNode redactTextNode(TextNode textNode, String path) {
    String text = textNode.asText();
    for (RedactionRule rule : profile.rules()) {
      if (rule.matchesFieldPath(path) || rule.matchesValue(text)) {
        return switch (rule.strategy()) {
          case DROP -> NullNode.getInstance();
          case IRREVERSIBLE_HASH -> TextNode.valueOf(sha256Text(text));
          case MASK -> TextNode.valueOf(maskIndicator(rule));
        };
      }
    }
    return textNode;
  }

  private RedactionRule findMatchingRule(String fieldName, String fieldPath) {
    for (RedactionRule rule : profile.rules()) {
      if (rule.matchesFieldPath(fieldPath) || rule.matchesFieldName(fieldName)) {
        return rule;
      }
    }
    return null;
  }

  private static String maskIndicator(RedactionRule rule) {
    return "[REDACTED:" + rule.name() + "]";
  }

  private static String sha256Fingerprint(JsonNode node) {
    if (node == null || node.isNull()) {
      return "sha256:null";
    }
    return CanonicalJson.sha256(node);
  }

  private static String sha256Text(String text) {
    return CanonicalJson.sha256(TextNode.valueOf(text));
  }
}
