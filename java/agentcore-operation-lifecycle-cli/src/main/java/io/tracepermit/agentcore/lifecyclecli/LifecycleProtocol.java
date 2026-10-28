package io.tracepermit.agentcore.lifecyclecli;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Exact private frames; EOF precedes parsing, authentication, JDBC, signing or delivery. */
final class LifecycleProtocol {
  static final String PROFILE = "tracepermit-local-synthetic-operation-lifecycle-v1";
  static final int CONFIG_BYTES = 65_536;
  static final int AUTH_BYTES = 16_384;
  static final int PRIVATE_KEY_BYTES = 128;
  static final int COMMAND_BYTES = 1_024;
  static final int RECEIPT_BYTES = 2_048;
  static final Set<String> COMMANDS =
      Set.of("check-config", "inspect", "evaluate", "review", "deliver-synthetic", "reconcile");

  static final ObjectMapper JSON =
      new ObjectMapper(
              JsonFactory.builder()
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(12)
                          .maxNameLength(64)
                          .maxStringLength(16_384)
                          .maxNumberLength(32)
                          .build())
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private LifecycleProtocol() {}

  static Input read(InputStream source, String command) {
    if (!COMMANDS.contains(command)) throw new LifecycleFailure("USAGE");
    byte[] configuration;
    byte[] bearer = null;
    byte[] privateKey = null;
    byte[] commandFrame = null;
    try {
      var input = new DataInputStream(source);
      configuration = frame(input, CONFIG_BYTES);
      if (!command.equals("check-config")) {
        bearer = frame(input, AUTH_BYTES);
        if (command.equals("deliver-synthetic")) {
          privateKey = frame(input, PRIVATE_KEY_BYTES);
        }
        commandFrame = frame(input, COMMAND_BYTES);
      }
      if (input.read() != -1) throw new LifecycleFailure("INPUT_INVALID");
    } catch (LifecycleFailure failure) {
      erase(privateKey);
      throw failure;
    } catch (Exception failure) {
      erase(privateKey);
      throw new LifecycleFailure("INPUT_INVALID");
    }

    try {
      LifecycleOperatorConfig parsed = LifecycleOperatorConfig.parse(object(configuration));
      if (command.equals("check-config")) return new Input(parsed, null, null, null);
      String token = utf8(bearer);
      if (token.length() > AUTH_BYTES
          || !token.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) {
        throw new LifecycleFailure("INPUT_INVALID");
      }
      Command parsedCommand;
      try {
        parsedCommand = command(object(commandFrame), command);
      } catch (LifecycleFailure invalid) {
        throw new LifecycleFailure("INPUT_INVALID");
      }
      return new Input(parsed, token, privateKey, parsedCommand);
    } catch (RuntimeException failure) {
      erase(privateKey);
      if (failure instanceof LifecycleFailure fixed) throw fixed;
      throw new LifecycleFailure("CONFIG_INVALID");
    }
  }

  private static Command command(ObjectNode json, String command) {
    Set<String> common = Set.of("schemaVersion", "profile", "operationId");
    Set<String> extra =
        command.equals("review")
            ? Set.of("decision")
            : command.equals("deliver-synthetic") ? Set.of("syntheticOutcome") : Set.of();
    fields(json, common, extra);
    if (!"1.0".equals(text(json, "schemaVersion", 3))
        || !PROFILE.equals(text(json, "profile", 96))) {
      throw new LifecycleFailure("INPUT_INVALID");
    }
    String operationId = text(json, "operationId", 36);
    try {
      if (!UUID.fromString(operationId).toString().equals(operationId)) {
        throw new IllegalArgumentException();
      }
    } catch (RuntimeException invalid) {
      throw new LifecycleFailure("INPUT_INVALID");
    }
    String decision = command.equals("review") ? text(json, "decision", 7) : null;
    if (decision != null && !Set.of("APPROVE", "DENY").contains(decision)) {
      throw new LifecycleFailure("INPUT_INVALID");
    }
    String outcome =
        command.equals("deliver-synthetic") ? text(json, "syntheticOutcome", 19) : null;
    if (outcome != null
        && !Set.of("CONFIRMED_SUCCESS", "CONFIRMED_REJECTION", "UNKNOWN").contains(outcome)) {
      throw new LifecycleFailure("INPUT_INVALID");
    }
    return new Command(operationId, decision, outcome);
  }

  static byte[] frame(DataInputStream input, int maximum) throws java.io.IOException {
    int length = input.readInt();
    if (length < 1 || length > maximum) throw new LifecycleFailure("INPUT_INVALID");
    byte[] value = input.readNBytes(length);
    if (value.length != length) throw new LifecycleFailure("INPUT_INVALID");
    return value;
  }

  static ObjectNode object(byte[] bytes) {
    try {
      JsonNode value = JSON.readTree(utf8(bytes));
      if (value == null || !value.isObject()) throw new LifecycleFailure("INPUT_INVALID");
      return (ObjectNode) value;
    } catch (LifecycleFailure failure) {
      throw failure;
    } catch (Exception invalid) {
      throw new LifecycleFailure("INPUT_INVALID");
    }
  }

  static String utf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (Exception invalid) {
      throw new LifecycleFailure("INPUT_INVALID");
    }
  }

  static void fields(JsonNode node, Set<String> required, Set<String> optional) {
    if (node == null || !node.isObject()) throw new LifecycleFailure("CONFIG_INVALID");
    var names = new HashSet<String>();
    node.fieldNames().forEachRemaining(names::add);
    if (!names.containsAll(required)
        || names.size() != required.size() + optional.size()
        || names.stream().anyMatch(name -> !required.contains(name) && !optional.contains(name))) {
      throw new LifecycleFailure("CONFIG_INVALID");
    }
  }

  static String text(JsonNode node, String key, int maximum) {
    JsonNode value = node.get(key);
    if (value == null || !value.isTextual()) throw new LifecycleFailure("CONFIG_INVALID");
    String text = value.textValue();
    if (text.isBlank() || text.length() > maximum || text.chars().anyMatch(Character::isISOControl)) {
      throw new LifecycleFailure("CONFIG_INVALID");
    }
    for (int index = 0; index < text.length(); index++) {
      char current = text.charAt(index);
      if (Character.isHighSurrogate(current)) {
        if (++index == text.length() || !Character.isLowSurrogate(text.charAt(index))) {
          throw new LifecycleFailure("CONFIG_INVALID");
        }
      } else if (Character.isLowSurrogate(current)) {
        throw new LifecycleFailure("CONFIG_INVALID");
      }
    }
    return text;
  }

  static long number(JsonNode node, String key, long minimum, long maximum) {
    JsonNode value = node.get(key);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new LifecycleFailure("CONFIG_INVALID");
    }
    long result = value.longValue();
    if (result < minimum || result > maximum) throw new LifecycleFailure("CONFIG_INVALID");
    return result;
  }

  static void erase(byte[] value) {
    if (value != null) Arrays.fill(value, (byte) 0);
  }

  static final class Input implements AutoCloseable {
    private final LifecycleOperatorConfig configuration;
    private final String bearer;
    private final byte[] privateKey;
    private final Command command;

    private Input(
        LifecycleOperatorConfig configuration, String bearer, byte[] privateKey, Command command) {
      this.configuration = configuration;
      this.bearer = bearer;
      this.privateKey = privateKey;
      this.command = command;
    }

    LifecycleOperatorConfig configuration() {
      return configuration;
    }

    String bearer() {
      return bearer;
    }

    byte[] privateKey() {
      return privateKey == null ? null : privateKey.clone();
    }

    Command command() {
      return command;
    }

    @Override
    public void close() {
      erase(privateKey);
    }

    @Override
    public String toString() {
      return "LifecycleInput[redacted]";
    }
  }

  record Command(String operationId, String decision, String syntheticOutcome) {
    @Override
    public String toString() {
      return "LifecycleCommand[redacted]";
    }
  }
}
