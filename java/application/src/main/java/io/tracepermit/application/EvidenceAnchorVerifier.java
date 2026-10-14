package io.tracepermit.application;

import io.tracepermit.execution.EvidenceAnchorReceipt;

/** Verifies an anchor receipt against independently configured public trust. */
@FunctionalInterface
public interface EvidenceAnchorVerifier {
  void verify(EvidenceAnchorReceipt receipt);
}
