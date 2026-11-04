package io.tracepermit.auth0reviewer;

import io.tracepermit.agentcore.organizationcli.Auth0OrganizationReview;
import java.io.DataInputStream;
import java.io.InputStream;
import java.util.Arrays;

/** Three exact anonymous-input frames and mandatory EOF, before browser, token, JDBC or effects. */
final class OperationReviewInput {
  final ReviewerConfiguration reviewer;
  final Auth0OrganizationReview.Plan plan;

  private OperationReviewInput(ReviewerConfiguration reviewer, Auth0OrganizationReview.Plan plan) {
    this.reviewer = reviewer; this.plan = plan;
  }

  static OperationReviewInput read(InputStream source) throws java.io.IOException {
    byte[] reviewer = null, organization = null, request = null;
    try {
      DataInputStream input = new DataInputStream(source);
      reviewer = frame(input, 16_384);
      organization = frame(input, 65_536);
      request = frame(input, 1_024);
      if (input.read() != -1) throw new IllegalArgumentException("REVIEW_INPUT_INVALID");
      var config = ReviewerConfiguration.parse(reviewer);
      var plan = Auth0OrganizationReview.prepare(organization, request);
      plan.requireReviewerBinding(config.issuer().toString(), config.audience(), config.client(),
          config.subject(), config.tenant(), config.keyId(), config.key().getEncoded());
      return new OperationReviewInput(config, plan);
    } finally {
      if (reviewer != null) Arrays.fill(reviewer, (byte)0);
      if (organization != null) Arrays.fill(organization, (byte)0);
      if (request != null) Arrays.fill(request, (byte)0);
    }
  }

  private static byte[] frame(DataInputStream input, int maximum) throws java.io.IOException {
    int n = input.readInt();
    if (n < 1 || n > maximum) throw new IllegalArgumentException("REVIEW_INPUT_INVALID");
    byte[] result = input.readNBytes(n);
    if (result.length != n) {
      Arrays.fill(result, (byte)0);throw new IllegalArgumentException("REVIEW_INPUT_INVALID");
    }
    return result;
  }

  @Override public String toString() { return "OperationReviewInput[redacted]"; }
}
