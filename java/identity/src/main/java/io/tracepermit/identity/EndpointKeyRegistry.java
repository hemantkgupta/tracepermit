package io.tracepermit.identity;

import java.security.PublicKey;
import java.util.Optional;

public interface EndpointKeyRegistry {
  Optional<EnrolledEndpointKey> resolve(String issuer, String keyId);

  record EnrolledEndpointKey(String issuer, String keyId, String deviceId, PublicKey publicKey) {
    public EnrolledEndpointKey {
      if (issuer == null || issuer.isBlank()) throw new IllegalArgumentException("Issuer is required.");
      if (keyId == null || keyId.isBlank()) throw new IllegalArgumentException("Key ID is required.");
      if (deviceId == null || deviceId.isBlank()) throw new IllegalArgumentException("Device ID is required.");
      if (publicKey == null || !"EdDSA".equals(publicKey.getAlgorithm())) {
        throw new IllegalArgumentException("Endpoint registry keys must be Ed25519 public keys.");
      }
    }
  }
}
