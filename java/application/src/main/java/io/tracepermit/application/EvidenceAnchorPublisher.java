package io.tracepermit.application;

import io.tracepermit.execution.EvidenceAnchorReceipt;
import io.tracepermit.execution.EvidenceOutboxEntry;

/** External side-effect port. Implementations must use outbox ID as their idempotency key. */
@FunctionalInterface
public interface EvidenceAnchorPublisher {
  EvidenceAnchorReceipt publish(EvidenceOutboxEntry entry);
}
