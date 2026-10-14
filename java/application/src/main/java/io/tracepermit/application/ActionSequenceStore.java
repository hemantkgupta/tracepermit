package io.tracepermit.application;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.policy.Classification;
import io.tracepermit.policy.ClassificationContext;
import java.time.Instant;

/** Server-owned history used to derive bounded cross-action classification context. */
public interface ActionSequenceStore {
  ActionSequenceStore DISABLED = new ActionSequenceStore() {
    @Override
    public ClassificationContext contextFor(JsonNode event, Instant now) {
      return ClassificationContext.EMPTY;
    }

    @Override
    public void record(JsonNode event, Classification classification, Instant now) {}
  };

  ClassificationContext contextFor(JsonNode event, Instant now);

  void record(JsonNode event, Classification classification, Instant now);
}
