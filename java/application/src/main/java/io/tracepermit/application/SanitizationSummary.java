package io.tracepermit.application;

import java.util.List;
import java.util.Objects;

/** Secret-free linkage between the immutable original and effective action. */
public record SanitizationSummary(
    String transformerId,
    List<String> changes,
    String originalActionHash,
    String effectiveActionHash,
    String effectiveEventId) {
  public SanitizationSummary {
    Objects.requireNonNull(transformerId);
    changes = List.copyOf(changes);
    if (transformerId.isBlank() || changes.isEmpty() || changes.size() > 8) {
      throw new IllegalArgumentException("Sanitization summary is invalid.");
    }
    if (!validHash(originalActionHash) || !validHash(effectiveActionHash)) {
      throw new IllegalArgumentException("Sanitization hashes are invalid.");
    }
    if (effectiveEventId == null || effectiveEventId.isBlank()) {
      throw new IllegalArgumentException("The effective event ID is invalid.");
    }
  }

  private static boolean validHash(String value) {
    return value != null && value.matches("sha256:[a-f0-9]{64}");
  }
}
