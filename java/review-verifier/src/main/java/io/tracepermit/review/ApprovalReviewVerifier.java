package io.tracepermit.review;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tracepermit.contracts.CanonicalJson;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Pattern;

public final class ApprovalReviewVerifier {
  static final int MAX_INPUT_BYTES = 32_768;
  private static final int MAX_STRING_LENGTH = 512;
  private static final Pattern HASH = Pattern.compile("^sha256:[a-f0-9]{64}$");
  private static final Pattern PRINCIPAL = Pattern.compile("^principal:[a-f0-9]{64}$");
  private static final Pattern APPROVAL_REQUEST_ID = Pattern.compile("^[a-f0-9-]{36}$");
  private static final Pattern TOOL_NAME =
      Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,255}$");
  private static final Pattern TIMESTAMP = Pattern.compile(
      "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}"
          + "(?:\\.[0-9]{1,9})?(?:Z|[+-][0-9]{2}:[0-9]{2})$");
  private static final Set<String> ROOT_FIELDS = Set.of(
      "schemaVersion", "kind", "canonicalization", "payload", "payloadHash");
  private static final Set<String> PAYLOAD_FIELDS =
      Set.of("generatedAt", "mode", "assurance", "approval");
  private static final Set<String> ASSURANCE_FIELDS = Set.of(
      "signed",
      "completeHistory",
      "transactionalSnapshot",
      "independentAnchor",
      "authorizesExecution",
      "argumentsIncluded");
  private static final Set<String> APPROVAL_REQUIRED_FIELDS = Set.of(
      "mode",
      "approvalRequestId",
      "requestHash",
      "requesterId",
      "status",
      "createdAt",
      "expiresAt",
      "expired",
      "policyId",
      "policyVersion",
      "originalPolicyOutcome",
      "executionStatus",
      "toolName");
  private static final Set<String> APPROVAL_OPTIONAL_FIELDS = Set.of("reviewerId");
  private static final Set<String> APPROVAL_STATUSES =
      Set.of("pending", "approved", "denied", "expired", "consumed", "cancelled");
  private static final Set<String> EXECUTION_STATUSES =
      Set.of("not_executed", "executing", "succeeded", "failed", "unknown");
  private static final ObjectMapper STRICT_JSON = new ObjectMapper(
      JsonFactory.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .streamReadConstraints(StreamReadConstraints.builder()
              .maxDocumentLength(MAX_INPUT_BYTES)
              .maxNestingDepth(8)
              .maxNameLength(64)
              .maxNumberLength(32)
              .maxStringLength(MAX_STRING_LENGTH)
              .build())
          .build())
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  public enum Outcome {
    VALID(0),
    USAGE_ERROR(2),
    UNSUPPORTED_INPUT(3),
    INPUT_TOO_LARGE(4),
    MALFORMED_DOCUMENT(5),
    CONTRACT_INVALID(6),
    CHECKSUM_MISMATCH(7),
    CHECK_FAILED(8);

    private final int exitCode;

    Outcome(int exitCode) {
      this.exitCode = exitCode;
    }

    public int exitCode() {
      return exitCode;
    }
  }

  public Outcome verify(Path input) {
    final byte[] bytes;
    try {
      bytes = readBoundedRegularFile(input);
    } catch (InputTooLargeException exception) {
      return Outcome.INPUT_TOO_LARGE;
    } catch (UnsupportedInputException | IOException | SecurityException exception) {
      return Outcome.UNSUPPORTED_INPUT;
    }

    final String document;
    try {
      document = StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException exception) {
      return Outcome.MALFORMED_DOCUMENT;
    }

    final JsonNode root;
    try {
      root = STRICT_JSON.readTree(document);
    } catch (IOException | RuntimeException exception) {
      return Outcome.MALFORMED_DOCUMENT;
    }

    try {
      validateContract(root);
    } catch (ContractViolation exception) {
      return Outcome.CONTRACT_INVALID;
    }

    String expected = root.path("payloadHash").textValue();
    String actual;
    try {
      actual = CanonicalJson.sha256(root.path("payload"));
    } catch (RuntimeException exception) {
      return Outcome.CHECK_FAILED;
    }
    return expected.equals(actual) ? Outcome.VALID : Outcome.CHECKSUM_MISMATCH;
  }

  private static byte[] readBoundedRegularFile(Path input)
      throws IOException, InputTooLargeException, UnsupportedInputException {
    if (input == null || Files.isSymbolicLink(input)) throw new UnsupportedInputException();
    BasicFileAttributes before;
    try {
      before = Files.readAttributes(input, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    } catch (IOException exception) {
      throw new UnsupportedInputException();
    }
    if (!before.isRegularFile()) throw new UnsupportedInputException();
    if (before.size() > MAX_INPUT_BYTES) throw new InputTooLargeException();

    ByteArrayOutputStream output = new ByteArrayOutputStream((int) before.size());
    Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    try (InputStream stream = Files.newInputStream(input, options.toArray(OpenOption[]::new))) {
      byte[] buffer = new byte[4096];
      int total = 0;
      int read;
      while ((read = stream.read(buffer)) != -1) {
        total += read;
        if (total > MAX_INPUT_BYTES) throw new InputTooLargeException();
        output.write(buffer, 0, read);
      }
    }

    BasicFileAttributes after =
        Files.readAttributes(input, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!after.isRegularFile()
        || before.size() != after.size()
        || after.size() != output.size()
        || !before.lastModifiedTime().equals(after.lastModifiedTime())
        || (before.fileKey() != null
            && after.fileKey() != null
            && !before.fileKey().equals(after.fileKey()))) {
      throw new UnsupportedInputException();
    }
    return output.toByteArray();
  }

  private static void validateContract(JsonNode root) {
    requireExactObject(root, ROOT_FIELDS, Set.of());
    requireText(root, "schemaVersion", "1.0");
    requireText(root, "kind", "tracepermit-approval-review");
    requireText(root, "canonicalization", CanonicalJson.PROFILE);
    requirePattern(root, "payloadHash", HASH);

    JsonNode payload = root.path("payload");
    requireExactObject(payload, PAYLOAD_FIELDS, Set.of());
    requireTimestamp(payload, "generatedAt");
    requireText(payload, "mode", "simulated");

    JsonNode assurance = payload.path("assurance");
    requireExactObject(assurance, ASSURANCE_FIELDS, Set.of());
    for (String field : ASSURANCE_FIELDS) requireFalse(assurance, field);

    JsonNode approval = payload.path("approval");
    requireExactObject(approval, APPROVAL_REQUIRED_FIELDS, APPROVAL_OPTIONAL_FIELDS);
    requireText(approval, "mode", "simulated");
    requirePattern(approval, "approvalRequestId", APPROVAL_REQUEST_ID);
    requirePattern(approval, "requestHash", HASH);
    requirePattern(approval, "requesterId", PRINCIPAL);
    if (approval.has("reviewerId")) requirePattern(approval, "reviewerId", PRINCIPAL);
    requireMember(approval, "status", APPROVAL_STATUSES);
    requireTimestamp(approval, "createdAt");
    requireTimestamp(approval, "expiresAt");
    requireBoolean(approval, "expired");
    requireBoundedText(approval, "policyId", 1, 256);
    JsonNode policyVersion = approval.path("policyVersion");
    if (!policyVersion.isInt() || policyVersion.intValue() < 1) throw new ContractViolation();
    requireText(approval, "originalPolicyOutcome", "require_approval");
    requireMember(approval, "executionStatus", EXECUTION_STATUSES);
    requirePattern(approval, "toolName", TOOL_NAME);
  }

  private static void requireExactObject(
      JsonNode node, Set<String> required, Set<String> optional) {
    if (!node.isObject()) throw new ContractViolation();
    for (String field : required) {
      if (!node.has(field)) throw new ContractViolation();
    }
    Iterator<String> fields = node.fieldNames();
    while (fields.hasNext()) {
      String field = fields.next();
      if (!required.contains(field) && !optional.contains(field)) throw new ContractViolation();
    }
  }

  private static void requireText(JsonNode node, String field, String expected) {
    JsonNode value = node.path(field);
    if (!value.isTextual() || !expected.equals(value.textValue())) throw new ContractViolation();
  }

  private static void requirePattern(JsonNode node, String field, Pattern pattern) {
    JsonNode value = node.path(field);
    if (!value.isTextual() || !pattern.matcher(value.textValue()).matches()) {
      throw new ContractViolation();
    }
  }

  private static void requireMember(JsonNode node, String field, Set<String> values) {
    JsonNode value = node.path(field);
    if (!value.isTextual() || !values.contains(value.textValue())) throw new ContractViolation();
  }

  private static void requireBoolean(JsonNode node, String field) {
    if (!node.path(field).isBoolean()) throw new ContractViolation();
  }

  private static void requireFalse(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isBoolean() || value.booleanValue()) throw new ContractViolation();
  }

  private static void requireBoundedText(
      JsonNode node, String field, int minimumCodePoints, int maximumCodePoints) {
    JsonNode value = node.path(field);
    if (!value.isTextual()) throw new ContractViolation();
    String text = value.textValue();
    int length = text.codePointCount(0, text.length());
    if (length < minimumCodePoints || length > maximumCodePoints) throw new ContractViolation();
  }

  private static void requireTimestamp(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isTextual()) throw new ContractViolation();
    String timestamp = value.textValue();
    if (timestamp.length() > 40 || !TIMESTAMP.matcher(timestamp).matches()) {
      throw new ContractViolation();
    }
    try {
      OffsetDateTime.parse(timestamp, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    } catch (DateTimeException exception) {
      throw new ContractViolation();
    }
  }

  private static final class ContractViolation extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }

  private static final class InputTooLargeException extends Exception {
    private static final long serialVersionUID = 1L;
  }

  private static final class UnsupportedInputException extends Exception {
    private static final long serialVersionUID = 1L;
  }
}
