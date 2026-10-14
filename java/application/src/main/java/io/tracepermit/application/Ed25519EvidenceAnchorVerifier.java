package io.tracepermit.application;

import io.tracepermit.execution.EvidenceAnchorReceipt;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;

/** Exact Ed25519 receipt verifier whose trust keys are operator supplied, never receipt supplied. */
public final class Ed25519EvidenceAnchorVerifier implements EvidenceAnchorVerifier {
  private final Map<String, PublicKey> trustedKeys;

  public Ed25519EvidenceAnchorVerifier(Map<String, PublicKey> trustedKeys) {
    this.trustedKeys = Map.copyOf(trustedKeys);
    if (this.trustedKeys.isEmpty() || this.trustedKeys.values().stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("At least one trusted evidence anchor key is required.");
    }
  }

  @Override
  public void verify(EvidenceAnchorReceipt receipt) {
    Objects.requireNonNull(receipt);
    PublicKey key = trustedKeys.get(receipt.provider() + ":" + receipt.keyId());
    if (key == null || !"EdDSA".equals(key.getAlgorithm())) {
      throw new IllegalArgumentException("The evidence anchor receipt key is not trusted.");
    }
    try {
      Signature verifier = Signature.getInstance("Ed25519");
      verifier.initVerify(key);
      verifier.update(receipt.signingInput());
      if (!verifier.verify(Base64.getUrlDecoder().decode(receipt.signature()))) {
        throw new IllegalArgumentException("The evidence anchor receipt signature is invalid.");
      }
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("The evidence anchor receipt could not be verified.", failure);
    }
  }
}
