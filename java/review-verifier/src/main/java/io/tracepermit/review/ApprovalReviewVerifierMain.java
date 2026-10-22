package io.tracepermit.review;

import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

public final class ApprovalReviewVerifierMain {
  static final String SUCCESS =
      "STRUCTURE_AND_CHECKSUM_VALID UNSIGNED SIMULATOR_ONLY NOT_AUTHORITY";
  private static final String PREFIX = "TRACEPERMIT_APPROVAL_REVIEW_CHECK: ";

  private ApprovalReviewVerifierMain() {}

  public static void main(String[] args) {
    int exitCode = execute(args, System.out, System.err);
    if (exitCode != 0) System.exit(exitCode);
  }

  public static int execute(String[] args, PrintStream output, PrintStream diagnostics) {
    if (args == null || args.length != 1 || args[0] == null || args[0].isBlank()) {
      diagnostics.println(PREFIX + ApprovalReviewVerifier.Outcome.USAGE_ERROR);
      return ApprovalReviewVerifier.Outcome.USAGE_ERROR.exitCode();
    }

    ApprovalReviewVerifier.Outcome outcome;
    try {
      outcome = new ApprovalReviewVerifier().verify(Path.of(args[0]));
    } catch (InvalidPathException | SecurityException exception) {
      outcome = ApprovalReviewVerifier.Outcome.UNSUPPORTED_INPUT;
    } catch (RuntimeException exception) {
      outcome = ApprovalReviewVerifier.Outcome.CHECK_FAILED;
    }

    if (outcome == ApprovalReviewVerifier.Outcome.VALID) {
      output.println(SUCCESS);
      return 0;
    }
    diagnostics.println(PREFIX + outcome);
    return outcome.exitCode();
  }
}
