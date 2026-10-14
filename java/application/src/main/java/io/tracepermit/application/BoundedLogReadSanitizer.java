package io.tracepermit.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.ActionValidator;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.policy.PolicyEvaluation;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Reviewed POC transformer for one simulated CloudWatch-compatible read tool. */
public final class BoundedLogReadSanitizer implements ActionSanitizer {
  public static final String TRANSFORMER_ID = "bounded-log-read/v1";
  public static final int MAX_RESULTS = 100;

  @Override
  public Optional<SanitizedAction> sanitize(
      JsonNode event, PolicyEvaluation evaluation, Instant now) {
    JsonNode validated = ActionValidator.validate(event);
    if (!"sanitize".equals(evaluation.decision().outcome())
        || !"LOG_RESULT_WINDOW_REQUIRES_SANITIZATION"
            .equals(evaluation.decision().reasonCode())
        || !"aws.logs.filter_log_events"
            .equals(validated.path("action").path("toolName").asText())) {
      return Optional.empty();
    }
    JsonNode limit = validated.path("action").path("arguments").path("limit");
    if (!limit.isIntegralNumber() || limit.asInt() <= MAX_RESULTS || limit.asInt() > 1_000) {
      return Optional.empty();
    }
    String originalHash = CanonicalJson.sha256(validated);
    ObjectNode transformed = validated.deepCopy();
    transformed.put(
        "eventId",
        UUID.nameUUIDFromBytes(
                (originalHash + "|" + TRANSFORMER_ID).getBytes(StandardCharsets.UTF_8))
            .toString());
    ((ObjectNode) transformed.path("action").path("arguments")).put("limit", MAX_RESULTS);
    JsonNode effective = ActionValidator.validate(transformed);
    return Optional.of(
        new SanitizedAction(
            effective,
            TRANSFORMER_ID,
            List.of("limit_clamped_to_100"),
            originalHash));
  }
}
