package io.tracepermit.application;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.contracts.ActionValidator;
import io.tracepermit.contracts.CanonicalJson;
import java.util.List;
import java.util.Objects;

/** Internal transformed action plus a bounded, non-secret change description. */
public record SanitizedAction(
    JsonNode event, String transformerId, List<String> changes, String originalActionHash) {
  public SanitizedAction {
    event = ActionValidator.validate(event);
    transformerId = Objects.requireNonNull(transformerId);
    changes = List.copyOf(changes);
    if (transformerId.isBlank() || changes.isEmpty() || changes.size() > 8) {
      throw new IllegalArgumentException("Sanitization metadata is invalid.");
    }
    if (originalActionHash == null || !originalActionHash.matches("sha256:[a-f0-9]{64}")) {
      throw new IllegalArgumentException("The original action hash is invalid.");
    }
  }

  @Override
  public JsonNode event() {
    return event.deepCopy();
  }

  public SanitizationSummary summary() {
    return new SanitizationSummary(
        transformerId,
        changes,
        originalActionHash,
        CanonicalJson.sha256(event),
        event.path("eventId").asText());
  }
}
