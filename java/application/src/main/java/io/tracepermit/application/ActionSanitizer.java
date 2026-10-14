package io.tracepermit.application;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.policy.PolicyEvaluation;
import java.time.Instant;
import java.util.Optional;

/** Allowlisted transformation boundary. Absence or failure never implies allow. */
@FunctionalInterface
public interface ActionSanitizer {
  ActionSanitizer NONE = (event, evaluation, now) -> Optional.empty();

  Optional<SanitizedAction> sanitize(
      JsonNode event, PolicyEvaluation evaluation, Instant now);
}
