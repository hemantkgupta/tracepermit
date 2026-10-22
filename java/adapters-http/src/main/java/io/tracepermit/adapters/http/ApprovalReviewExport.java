package io.tracepermit.adapters.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import java.time.Instant;
import java.util.Set;

/** A bounded, unsigned metadata projection. Neither an authority token nor a chain proof. */
final class ApprovalReviewExport {
  static final int MAX_BYTES = 32768;
  private static final Set<String> REQUIRED = Set.of("mode", "approvalRequestId", "requestHash",
      "requesterId", "status", "createdAt", "expiresAt", "expired", "policyId", "policyVersion",
      "originalPolicyOutcome", "executionStatus");

  private ApprovalReviewExport() {}

  static ObjectNode create(JsonNode metadata, String toolName, Instant generatedAt) {
    // Copy only this schema's explicit fields. Future detail fields must not silently enter exports.
    ObjectNode approval = JsonSupport.MAPPER.createObjectNode();
    for (String name : REQUIRED) {
      if (!metadata.hasNonNull(name)) throw unavailable();
      approval.set(name, metadata.get(name).deepCopy());
    }
    if (metadata.hasNonNull("reviewerId")) approval.set("reviewerId", metadata.get("reviewerId").deepCopy());
    if (!"simulated".equals(approval.path("mode").asText())
        || !toolName.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}")) throw unavailable();
    approval.put("toolName", toolName);
    ObjectNode payload = JsonSupport.MAPPER.createObjectNode()
        .put("generatedAt", generatedAt.toString()).put("mode", "simulated");
    payload.set("approval", approval);
    payload.putObject("assurance")
        .put("signed", false).put("completeHistory", false).put("transactionalSnapshot", false)
        .put("independentAnchor", false).put("authorizesExecution", false).put("argumentsIncluded", false);
    ObjectNode report = JsonSupport.MAPPER.createObjectNode()
        .put("schemaVersion", "1.0").put("kind", "tracepermit-approval-review")
        .put("canonicalization", CanonicalJson.PROFILE);
    report.set("payload", payload);
    report.put("payloadHash", CanonicalJson.sha256(payload));
    try {
      if (JsonSupport.MAPPER.writeValueAsBytes(report).length > MAX_BYTES) throw unavailable();
    } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
      throw unavailable();
    }
    return report;
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("APPROVAL_EXPORT_UNAVAILABLE");
  }
}
