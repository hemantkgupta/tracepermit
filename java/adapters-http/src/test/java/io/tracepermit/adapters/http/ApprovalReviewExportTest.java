package io.tracepermit.adapters.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import java.time.Instant;
import org.junit.jupiter.api.Test;

final class ApprovalReviewExportTest {
  @Test void matchesPublishedSyntheticGoldenVectorUsingExistingJavaCanonicalization() throws Exception {
    var path = java.nio.file.Path.of(System.getProperty("tracepermit.repositoryRoot"),
        "contracts/vectors/approval-review-export-v1.json");
    var golden = JsonSupport.MAPPER.readTree(path.toFile());
    assertThat(ApprovalReviewExport.create(metadata(), "aws.ec2.update_security_group", Instant.EPOCH))
        .isEqualTo(golden);
  }

  @Test void closedProjectionOmitsUnexpectedFieldsAndDoesNotMutateInput() throws Exception {
    ObjectNode input = metadata();
    input.putObject("action").put("password", "synthetic-secret-canary");
    input.put("accessToken", "synthetic-token-canary");
    var original = input.deepCopy();
    var report = ApprovalReviewExport.create(input, "aws.ec2.update_security_group", Instant.EPOCH);
    assertThat(input).isEqualTo(original);
    assertThat(report.toString()).doesNotContain("synthetic-secret-canary", "synthetic-token-canary", "\"action\"", "accessToken");
    assertThat(report.path("payloadHash").asText()).isEqualTo(CanonicalJson.sha256(report.path("payload")));
    assertThat(report.path("payload").path("assurance").properties()).hasSize(6)
        .allMatch(entry -> entry.getValue().isBoolean() && !entry.getValue().asBoolean());
    var changed = report.deepCopy();
    ((ObjectNode) changed.path("payload").path("approval")).put("status", "approved");
    assertThat(CanonicalJson.sha256(changed.path("payload"))).isNotEqualTo(report.path("payloadHash").asText());
    input.put("reviewerId", "principal:" + "b".repeat(64));
    assertThat(ApprovalReviewExport.create(input, "aws.ec2.update_security_group", Instant.EPOCH)
        .path("payload").path("approval").path("reviewerId")).isEqualTo(input.path("reviewerId"));
  }

  @Test void missingMetadataInvalidModeToolAndOversizedReportHaveFixedFailure() throws Exception {
    ObjectNode input = metadata();
    input.remove("requestHash");
    assertUnavailable(input, "aws.ec2.update_security_group");
    input = metadata(); input.put("mode", "real");
    assertUnavailable(input, "aws.ec2.update_security_group");
    assertUnavailable(metadata(), "unsafe\r\ntool");
    input = metadata(); input.put("policyId", "x".repeat(ApprovalReviewExport.MAX_BYTES));
    assertUnavailable(input, "aws.ec2.update_security_group");
  }

  private static void assertUnavailable(ObjectNode input, String tool) {
    assertThatThrownBy(() -> ApprovalReviewExport.create(input, tool, Instant.EPOCH))
        .isInstanceOf(IllegalStateException.class).hasMessage("APPROVAL_EXPORT_UNAVAILABLE");
  }

  private static ObjectNode metadata() throws Exception {
    return (ObjectNode) JsonSupport.MAPPER.readTree("""
        {"mode":"simulated","approvalRequestId":"00000000-0000-0000-0000-000000000001",
         "requestHash":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
         "requesterId":"principal:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
         "status":"pending","createdAt":"1970-01-01T00:00:00Z","expiresAt":"1970-01-01T00:05:00Z",
         "expired":false,"policyId":"pol-local-simulator-approval","policyVersion":2,
         "originalPolicyOutcome":"require_approval","executionStatus":"not_executed"}
        """);
  }
}
