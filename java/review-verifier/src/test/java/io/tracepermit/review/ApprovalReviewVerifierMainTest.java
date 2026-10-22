package io.tracepermit.review;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

final class ApprovalReviewVerifierMainTest {
  @TempDir
  Path temporaryDirectory;

  @Test
  void mainEntryReturnsOnlyFixedSuccessWording() throws Exception {
    Path input = copyGolden("private approval review.json");
    Result result = execute(new String[] {input.toString()});

    assertAll(
        () -> assertThat(result.exitCode()).isZero(),
        () -> assertThat(result.output()).isEqualTo(ApprovalReviewVerifierMain.SUCCESS + "\n"),
        () -> assertThat(result.diagnostics()).isEmpty());
  }

  @Test
  void mainEntryReturnsStableUsageAndFailureCategories() throws Exception {
    Path malformed = temporaryDirectory.resolve("secret path malformed.json");
    Files.writeString(malformed, "{not-json SECRET_DOCUMENT_VALUE", StandardCharsets.UTF_8);
    Path changed = copyGolden("checksum mismatch.json");
    String content = Files.readString(changed, StandardCharsets.UTF_8)
        .replace("aws.ec2.update_security_group", "jira.issue.update");
    Files.writeString(changed, content, StandardCharsets.UTF_8);

    Result noArgs = execute(new String[0]);
    Result tooMany = execute(new String[] {"one", "two"});
    Result blank = execute(new String[] {" "});
    Result invalidPath = execute(new String[] {"invalid\u0000path"});
    Result remoteUrl = execute(new String[] {"https://example.invalid/review.json"});
    Result malformedResult = execute(new String[] {malformed.toString()});
    Result mismatch = execute(new String[] {changed.toString()});

    assertAll(
        () -> assertFailure(noArgs, 2, "USAGE_ERROR"),
        () -> assertFailure(tooMany, 2, "USAGE_ERROR"),
        () -> assertFailure(blank, 2, "USAGE_ERROR"),
        () -> assertFailure(invalidPath, 3, "UNSUPPORTED_INPUT"),
        () -> assertFailure(remoteUrl, 3, "UNSUPPORTED_INPUT"),
        () -> assertFailure(malformedResult, 5, "MALFORMED_DOCUMENT"),
        () -> assertFailure(mismatch, 7, "CHECKSUM_MISMATCH"),
        () -> assertThat(malformedResult.combined()).doesNotContain(
            malformed.toString(), "SECRET_DOCUMENT_VALUE", "JsonParseException"));
  }

  @Test
  void nullArgumentArrayIsAUsageError() {
    Result result = execute(null);
    assertFailure(result, 2, "USAGE_ERROR");
  }

  private Result execute(String[] args) {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
    int exitCode = ApprovalReviewVerifierMain.execute(
        args,
        new PrintStream(output, true, StandardCharsets.UTF_8),
        new PrintStream(diagnostics, true, StandardCharsets.UTF_8));
    return new Result(
        exitCode,
        output.toString(StandardCharsets.UTF_8),
        diagnostics.toString(StandardCharsets.UTF_8));
  }

  private Path copyGolden(String name) throws IOException {
    Path path = temporaryDirectory.resolve(name);
    Files.copy(Path.of(requiredProperty("tracepermit.approvalReviewVector")), path);
    return path;
  }

  private static String requiredProperty(String name) {
    String value = System.getProperty(name);
    if (value == null || value.isBlank()) throw new IllegalStateException("Missing test property");
    return value;
  }

  private static void assertFailure(Result result, int exitCode, String category) {
    assertAll(
        () -> assertThat(result.exitCode()).isEqualTo(exitCode),
        () -> assertThat(result.output()).isEmpty(),
        () -> assertThat(result.diagnostics())
            .isEqualTo("TRACEPERMIT_APPROVAL_REVIEW_CHECK: " + category + "\n"));
  }

  private record Result(int exitCode, String output, String diagnostics) {
    String combined() {
      return output + diagnostics;
    }
  }
}
