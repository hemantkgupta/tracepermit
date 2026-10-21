package io.tracepermit.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.tracepermit.review.ApprovalReviewVerifier.Outcome.CHECKSUM_MISMATCH;
import static io.tracepermit.review.ApprovalReviewVerifier.Outcome.CONTRACT_INVALID;
import static io.tracepermit.review.ApprovalReviewVerifier.Outcome.INPUT_TOO_LARGE;
import static io.tracepermit.review.ApprovalReviewVerifier.Outcome.MALFORMED_DOCUMENT;
import static io.tracepermit.review.ApprovalReviewVerifier.Outcome.UNSUPPORTED_INPUT;
import static io.tracepermit.review.ApprovalReviewVerifier.Outcome.VALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

final class ApprovalReviewVerifierTest {
  private static final String GOLDEN_HASH =
      "sha256:b906a41995ff346f31fe1a94331e60a44afb72c381d3343bc014602ac4742dae";

  @TempDir
  Path temporaryDirectory;

  private final ApprovalReviewVerifier verifier = new ApprovalReviewVerifier();

  @Test
  void acceptsPublishedGoldenVectorAndHarmlessFormattingPermutations() throws Exception {
    ObjectNode golden = golden();
    assertThat(golden.path("payloadHash").asText()).isEqualTo(GOLDEN_HASH);
    assertThat(CanonicalJson.sha256(golden.path("payload"))).isEqualTo(GOLDEN_HASH);

    ObjectNode reordered = JsonSupport.MAPPER.createObjectNode();
    reordered.set("payloadHash", golden.path("payloadHash"));
    reordered.set("payload", golden.path("payload"));
    reordered.set("canonicalization", golden.path("canonicalization"));
    reordered.set("kind", golden.path("kind"));
    reordered.set("schemaVersion", golden.path("schemaVersion"));

    assertAll(
        () -> assertThat(verify(golden.toString())).isEqualTo(VALID),
        () -> assertThat(verify(JsonSupport.MAPPER.writerWithDefaultPrettyPrinter()
            .writeValueAsString(reordered))).isEqualTo(VALID));
  }

  @Test
  void detectsPayloadChangeButExplicitlyAllowsUntrustedPayloadAndChecksumRewrite()
      throws Exception {
    ObjectNode changed = golden();
    approval(changed).put("toolName", "jira.issue.update");
    assertThat(verify(changed.toString())).isEqualTo(CHECKSUM_MISMATCH);

    recompute(changed);
    assertThat(verify(changed.toString())).isEqualTo(VALID);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "signed",
      "completeHistory",
      "transactionalSnapshot",
      "independentAnchor",
      "authorizesExecution",
      "argumentsIncluded"
  })
  void rejectsEveryTrueAssuranceFlagEvenWithRecomputedChecksum(String field) throws Exception {
    ObjectNode changed = golden();
    ((ObjectNode) changed.path("payload").path("assurance")).put(field, true);
    recompute(changed);
    assertThat(verify(changed.toString())).isEqualTo(CONTRACT_INVALID);
  }

  @Test
  void rejectsUnknownAndMissingFieldsAtEveryClosedObjectBoundary() throws Exception {
    ObjectNode rootUnknown = golden();
    rootUnknown.put("extra", "value");
    ObjectNode payloadUnknown = golden();
    ((ObjectNode) payloadUnknown.path("payload")).put("extra", "value");
    ObjectNode assuranceUnknown = golden();
    ((ObjectNode) assuranceUnknown.path("payload").path("assurance")).put("extra", false);
    ObjectNode approvalUnknown = golden();
    approval(approvalUnknown).put("extra", "value");
    ObjectNode rootMissing = golden();
    rootMissing.remove("kind");
    ObjectNode approvalMissing = golden();
    approval(approvalMissing).remove("requestHash");

    for (ObjectNode document : List.of(
        rootUnknown, payloadUnknown, assuranceUnknown, approvalUnknown, rootMissing, approvalMissing)) {
      recomputeIfPossible(document);
      assertThat(verify(document.toString())).isEqualTo(CONTRACT_INVALID);
    }
  }

  @Test
  void rejectsWrongFixedValuesAndWrongJsonTypes() throws Exception {
    ObjectNode version = mutate("schemaVersion", "2.0");
    ObjectNode kind = mutate("kind", "tracepermit-authority");
    ObjectNode profile = mutate("canonicalization", "different-profile");
    ObjectNode payloadMode = golden();
    ((ObjectNode) payloadMode.path("payload")).put("mode", "production");
    ObjectNode approvalMode = golden();
    approval(approvalMode).put("mode", "production");
    ObjectNode wrongPayloadType = golden();
    wrongPayloadType.put("payload", "not-an-object");
    ObjectNode wrongExpiredType = golden();
    approval(wrongExpiredType).put("expired", "false");
    ObjectNode falseFlagType = golden();
    ((ObjectNode) falseFlagType.path("payload").path("assurance")).put("signed", "false");

    for (ObjectNode document : List.of(
        version,
        kind,
        profile,
        payloadMode,
        approvalMode,
        wrongPayloadType,
        wrongExpiredType,
        falseFlagType)) {
      recomputeIfPossible(document);
      assertThat(verify(document.toString())).isEqualTo(CONTRACT_INVALID);
    }
  }

  @Test
  void validatesIdentifiersAndOptionalReviewer() throws Exception {
    ObjectNode withReviewer = golden();
    approval(withReviewer).put(
        "reviewerId",
        "principal:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    recompute(withReviewer);
    assertThat(verify(withReviewer.toString())).isEqualTo(VALID);

    for (String field : List.of("approvalRequestId", "requestHash", "requesterId")) {
      ObjectNode invalid = golden();
      approval(invalid).put(field, "INVALID");
      recompute(invalid);
      assertThat(verify(invalid.toString())).isEqualTo(CONTRACT_INVALID);
    }

    ObjectNode invalidReviewer = golden();
    approval(invalidReviewer).put("reviewerId", "principal:INVALID");
    recompute(invalidReviewer);
    assertThat(verify(invalidReviewer.toString())).isEqualTo(CONTRACT_INVALID);
  }

  @ParameterizedTest
  @ValueSource(strings = {"pending", "approved", "denied", "expired", "consumed", "cancelled"})
  void acceptsEveryClosedApprovalStatus(String status) throws Exception {
    ObjectNode document = golden();
    approval(document).put("status", status);
    recompute(document);
    assertThat(verify(document.toString())).isEqualTo(VALID);
  }

  @ParameterizedTest
  @ValueSource(strings = {"not_executed", "executing", "succeeded", "failed", "unknown"})
  void acceptsEveryClosedExecutionStatus(String status) throws Exception {
    ObjectNode document = golden();
    approval(document).put("executionStatus", status);
    recompute(document);
    assertThat(verify(document.toString())).isEqualTo(VALID);
  }

  @Test
  void rejectsUnknownStatusesAndInvalidToolNames() throws Exception {
    ObjectNode approvalStatus = golden();
    approval(approvalStatus).put("status", "executed");
    ObjectNode executionStatus = golden();
    approval(executionStatus).put("executionStatus", "safe_to_retry");
    ObjectNode toolName = golden();
    approval(toolName).put("toolName", " invalid");

    for (ObjectNode document : List.of(approvalStatus, executionStatus, toolName)) {
      recompute(document);
      assertThat(verify(document.toString())).isEqualTo(CONTRACT_INVALID);
    }
  }

  @Test
  void enforcesPositiveSignedIntPolicyVersionWithoutCoercion() throws Exception {
    ObjectNode zero = golden();
    approval(zero).put("policyVersion", 0);
    ObjectNode floatValue = golden();
    approval(floatValue).put("policyVersion", 2.0d);
    ObjectNode overflow = golden();
    approval(overflow).put("policyVersion", 2_147_483_648L);
    ObjectNode maximum = golden();
    approval(maximum).put("policyVersion", Integer.MAX_VALUE);
    recompute(maximum);

    assertAll(
        () -> assertThat(verify(zero.toString())).isEqualTo(CONTRACT_INVALID),
        () -> assertThat(verify(floatValue.toString())).isEqualTo(CONTRACT_INVALID),
        () -> assertThat(verify(overflow.toString())).isEqualTo(CONTRACT_INVALID),
        () -> assertThat(verify(maximum.toString())).isEqualTo(VALID));
  }

  @Test
  void validatesBoundedStringsByUnicodeCodePoint() throws Exception {
    ObjectNode emptyPolicy = golden();
    approval(emptyPolicy).put("policyId", "");
    ObjectNode maximumPolicy = golden();
    approval(maximumPolicy).put("policyId", "x".repeat(256));
    recompute(maximumPolicy);
    ObjectNode excessivePolicy = golden();
    approval(excessivePolicy).put("policyId", "x".repeat(257));
    ObjectNode excessiveTool = golden();
    approval(excessiveTool).put("toolName", "x".repeat(257));

    assertAll(
        () -> assertThat(verify(emptyPolicy.toString())).isEqualTo(CONTRACT_INVALID),
        () -> assertThat(verify(maximumPolicy.toString())).isEqualTo(VALID),
        () -> assertThat(verify(excessivePolicy.toString())).isEqualTo(CONTRACT_INVALID),
        () -> assertThat(verify(excessiveTool.toString())).isEqualTo(CONTRACT_INVALID));
  }

  @Test
  void acceptsRfc3339OffsetsAndRejectsMalformedTimestampForms() throws Exception {
    ObjectNode offset = golden();
    ((ObjectNode) offset.path("payload")).put("generatedAt", "2026-10-21T12:34:56.123+05:30");
    recompute(offset);
    assertThat(verify(offset.toString())).isEqualTo(VALID);

    for (String timestamp : List.of(
        "2026-10-21 12:34:56Z",
        "2026-10-21T12:34:56",
        "2026-02-30T12:34:56Z",
        "2026-10-21t12:34:56z",
        "2026-10-21T12:34:60Z")) {
      ObjectNode invalid = golden();
      ((ObjectNode) invalid.path("payload")).put("generatedAt", timestamp);
      recompute(invalid);
      assertThat(verify(invalid.toString())).isEqualTo(CONTRACT_INVALID);
    }
  }

  @Test
  void rejectsMalformedHashBeforeComparisonAndValidWrongHashAfterValidation() throws Exception {
    ObjectNode malformed = golden();
    malformed.put("payloadHash", "sha256:INVALID");
    ObjectNode wrong = golden();
    wrong.put("payloadHash", "sha256:" + "0".repeat(64));

    assertAll(
        () -> assertThat(verify(malformed.toString())).isEqualTo(CONTRACT_INVALID),
        () -> assertThat(verify(wrong.toString())).isEqualTo(CHECKSUM_MISMATCH));
  }

  @Test
  void rejectsDuplicateKeysTrailingJsonAndNonObjectRoots() throws Exception {
    String duplicate = goldenText().replaceFirst(
        "\\{", "{\\\"schemaVersion\\\":\\\"1.0\\\",");
    String trailing = goldenText() + "{}";

    assertAll(
        () -> assertThat(verify(duplicate)).isEqualTo(MALFORMED_DOCUMENT),
        () -> assertThat(verify(trailing)).isEqualTo(MALFORMED_DOCUMENT),
        () -> assertThat(verify("null")).isEqualTo(CONTRACT_INVALID),
        () -> assertThat(verify("[]")).isEqualTo(CONTRACT_INVALID));
  }

  @Test
  void enforcesParserDepthStringAndNumberBounds() throws Exception {
    String tooDeep = "{\"a\":".repeat(9) + "null" + "}".repeat(9);
    String tooLong = "{\"value\":\"" + "x".repeat(513) + "\"}";
    String tooLongNumber = "{\"value\":" + "1".repeat(33) + "}";

    assertAll(
        () -> assertThat(verify(tooDeep)).isEqualTo(MALFORMED_DOCUMENT),
        () -> assertThat(verify(tooLong)).isEqualTo(MALFORMED_DOCUMENT),
        () -> assertThat(verify(tooLongNumber)).isEqualTo(MALFORMED_DOCUMENT));
  }

  @Test
  void rejectsMalformedUtf8AndBoundsActualInputBytes() throws Exception {
    Path malformed = temporaryDirectory.resolve("malformed.json");
    Files.write(malformed, new byte[] {(byte) 0xc3, (byte) 0x28});
    Path oversized = temporaryDirectory.resolve("oversized.json");
    Files.write(oversized, new byte[ApprovalReviewVerifier.MAX_INPUT_BYTES + 1]);

    assertAll(
        () -> assertThat(verifier.verify(malformed)).isEqualTo(MALFORMED_DOCUMENT),
        () -> assertThat(verifier.verify(oversized)).isEqualTo(INPUT_TOO_LARGE));
  }

  @Test
  void acceptsAValidDocumentAtTheExactByteLimit() throws Exception {
    byte[] golden = goldenText().getBytes(StandardCharsets.UTF_8);
    String bounded = goldenText() + " ".repeat(ApprovalReviewVerifier.MAX_INPUT_BYTES - golden.length);

    assertThat(bounded.getBytes(StandardCharsets.UTF_8))
        .hasSize(ApprovalReviewVerifier.MAX_INPUT_BYTES);
    assertThat(verify(bounded)).isEqualTo(VALID);
  }

  @Test
  void rejectsMissingDirectoriesAndSymlinksWithoutFollowingThem() throws Exception {
    Path regular = write(goldenText());
    Path directory = temporaryDirectory.resolve("directory");
    Files.createDirectory(directory);
    Path symlink = temporaryDirectory.resolve("review-link.json");
    Files.createSymbolicLink(symlink, regular.getFileName());

    assertAll(
        () -> assertThat(verifier.verify(temporaryDirectory.resolve("missing.json")))
            .isEqualTo(UNSUPPORTED_INPUT),
        () -> assertThat(verifier.verify(directory)).isEqualTo(UNSUPPORTED_INPUT),
        () -> assertThat(verifier.verify(symlink)).isEqualTo(UNSUPPORTED_INPUT));
  }

  private ApprovalReviewVerifier.Outcome verify(String document) throws IOException {
    return verifier.verify(write(document));
  }

  private Path write(String document) throws IOException {
    Path path = temporaryDirectory.resolve("approval review.json");
    return Files.writeString(path, document, StandardCharsets.UTF_8);
  }

  private static ObjectNode mutate(String field, String value) throws Exception {
    ObjectNode document = golden();
    document.put(field, value);
    return document;
  }

  private static ObjectNode approval(ObjectNode document) {
    return (ObjectNode) document.path("payload").path("approval");
  }

  private static void recompute(ObjectNode document) {
    document.put("payloadHash", CanonicalJson.sha256(document.path("payload")));
  }

  private static void recomputeIfPossible(ObjectNode document) {
    JsonNode payload = document.path("payload");
    if (payload.isObject()) recompute(document);
  }

  private static ObjectNode golden() throws Exception {
    return (ObjectNode) JsonSupport.MAPPER.readTree(goldenText());
  }

  private static String goldenText() throws IOException {
    try (InputStream input = ApprovalReviewVerifierTest.class.getResourceAsStream(
        "/io/tracepermit/review/approval-review-export-v1.json")) {
      if (input == null) throw new IOException("Test vector resource is unavailable");
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
