package io.tracepermit.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;

/** Durable registration port. All scope values come from the authenticated application boundary. */
public interface BusinessOperationRepository {
  /**
   * Inserts an initial evaluating/not_started/pending aggregate and its three initial facts
   * atomically. A scoped key retry returns the original aggregate only when its semantic action,
   * adapter, resource/precondition and recovery generation match. Transport/event/operation IDs,
   * timestamps and proposed policy/artifact releases do not replace the first stored values.
   */
  BusinessOperationRegistration createOrGet(BusinessOperationScope scope, JsonNode initialOperation);

  /** Returns only a matching tenant/workspace/requester aggregate, with integrity validation. */
  Optional<JsonNode> find(BusinessOperationScope scope, String operationId);
}
