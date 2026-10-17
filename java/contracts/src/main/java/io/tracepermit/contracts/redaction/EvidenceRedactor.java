package io.tracepermit.contracts.redaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;

import java.util.Iterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;

/**
 * Applies configured redaction to bounded JSON projections and diagnostic text.
 *
 * <p>Enforces fail-closed redaction bounds (SEC-012, DATA-003) and depth limits to prevent
 * recursion attacks. Never use the returned projection for authorization or dispatch;
 * retain the original canonical action and its binding separately.
 */
public final class EvidenceRedactor {
  public static final int MAX_NODES = 10_000;
  public static final int MAX_TEXT_LENGTH = 65_536;
  public static final int MAX_TOTAL_TEXT_LENGTH = 1_048_576;
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
    validateInput(input, 0, new InputBudget());
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
    // Validate the entire envelope before recursive copying or hashing, including
    // subtrees that a matching field rule would otherwise mask/drop without visiting.
    validateInput(canonicalAction, 0, new InputBudget());
    ObjectNode result = (ObjectNode) canonicalAction.deepCopy();
    JsonNode actionNode = result.get("action");
    if (actionNode instanceof ObjectNode actionObj) {
      JsonNode argsNode = actionObj.get("arguments");
      if (argsNode != null) {
        JsonNode redactedArgs = redactNode(argsNode, "action.arguments", 2);
        actionObj.set("arguments", redactedArgs);
      }
    }
    return result;
  }

  /**
   * Transforms each matching span in the original diagnostic text. Leftmost matches
   * win; profile order breaks ties. Replacement text is never scanned again.
   * DROP removes the span, HASH fingerprints its canonical JSON string, MASK uses
   * the rule indicator. Field-name/path rules do not apply to unstructured text.
   */
  public String redactText(String rawText) {
    if (rawText == null) {
      return rawText;
    }
    checkTextLength(rawText);
    List<Matcher> matchers = new ArrayList<>();
    List<RedactionRule> rules = new ArrayList<>();
    for (RedactionRule rule : profile.rules()) {
      if (rule.valuePattern() != null) {
        matchers.add(rule.valuePattern().matcher(rawText));
        rules.add(rule);
      }
    }
    StringBuilder result = new StringBuilder();
    int offset = 0;
    while (offset <= rawText.length()) {
      int selected = -1;
      int start = Integer.MAX_VALUE;
      for (int i = 0; i < matchers.size(); i++) {
        Matcher candidate = matchers.get(i);
        if (candidate.find(offset) && candidate.start() < start) {
          selected = i;
          start = candidate.start();
        }
      }
      if (selected == -1) break;
      Matcher match = matchers.get(selected);
      if (match.start() == match.end()) {
        throw new IllegalArgumentException("REDACTION_EMPTY_PATTERN_MATCH");
      }
      result.append(rawText, offset, start);
      RedactionRule rule = rules.get(selected);
      result.append(switch (rule.strategy()) {
        case MASK -> maskIndicator(rule);
        case IRREVERSIBLE_HASH -> sha256Text(match.group());
        case DROP -> "";
      });
      if (result.length() > MAX_TOTAL_TEXT_LENGTH) {
        throw new IllegalArgumentException("REDACTION_OUTPUT_LIMIT");
      }
      offset = match.end();
    }
    result.append(rawText, offset, rawText.length());
    if (result.length() > MAX_TOTAL_TEXT_LENGTH) {
      throw new IllegalArgumentException("REDACTION_OUTPUT_LIMIT");
    }
    return result.toString();
  }

  private void validateInput(JsonNode node, int depth, InputBudget budget) {
    if (depth > profile.maxDepth()) {
      throw new IllegalArgumentException(
          "Payload exceeds maximum redaction depth limit of " + profile.maxDepth());
    }
    if (++budget.nodes > MAX_NODES) {
      throw new IllegalArgumentException("REDACTION_NODE_LIMIT");
    }
    if (node == null || node.isNull()) return;
    if (node.isObject()) {
      Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> entry = fields.next();
        budget.addText(entry.getKey());
        validateInput(entry.getValue(), depth + 1, budget);
      }
    } else if (node.isArray()) {
      for (JsonNode child : node) validateInput(child, depth + 1, budget);
    } else if (node.isTextual()) {
      budget.addText(node.textValue());
    } else if (!node.isBoolean() && !node.isNumber()) {
      throw new IllegalArgumentException("REDACTION_UNSUPPORTED_VALUE");
    }
  }

  private static void checkTextLength(String text) {
    if (text.length() > MAX_TEXT_LENGTH) {
      throw new IllegalArgumentException("REDACTION_TEXT_LIMIT");
    }
  }

  private static final class InputBudget {
    private int nodes;
    private int textLength;

    private void addText(String text) {
      checkTextLength(text);
      textLength += text.length();
      if (textLength > MAX_TOTAL_TEXT_LENGTH) {
        throw new IllegalArgumentException("REDACTION_TOTAL_TEXT_LIMIT");
      }
    }
  }

  private JsonNode redactNode(JsonNode node, String path, int depth) {
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
    return CanonicalJson.sha256(node);
  }

  private static String sha256Text(String text) {
    return CanonicalJson.sha256(TextNode.valueOf(text));
  }
}
