package io.tracepermit.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;

/**
 * Internal lifecycle port. Scope/context must be derived by a trusted adapter, never deserialized
 * from a public body. No method grants target, approval, signing or publication authority.
 */
public interface BusinessOperationLifecycleRepository {
  /** Coherent current snapshot with immutable-registration and journal integrity validation. */
  Optional<JsonNode> findCurrent(BusinessOperationScope scope, String operationId);

  /**
   * Withdraws only pending, not-started work after locked expected-state/identity/deadline checks.
   * No automatic retry or implicit idempotent success: acknowledgement loss remains unknown.
   */
  JsonNode withdraw(BusinessOperationWithdrawal command);
}
