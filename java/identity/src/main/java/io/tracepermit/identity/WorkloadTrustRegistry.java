package io.tracepermit.identity;

import java.security.PublicKey;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Operator-controlled public-key trust input for schedulers and delegating principals. */
public interface WorkloadTrustRegistry {
  Optional<TrustedWorkloadKey> resolve(String issuer, String keyId);

  enum KeyUsage {
    WORKLOAD_IDENTITY,
    DELEGATION
  }

  enum KeyState {
    ACTIVE,
    REVOKED
  }

  record TrustedWorkloadKey(
      String issuer,
      String keyId,
      String tenantId,
      String principalId,
      Set<KeyUsage> usages,
      KeyState state,
      PublicKey publicKey) {
    public TrustedWorkloadKey {
      requireIdentifier(issuer, 256, "issuer");
      requireIdentifier(keyId, 128, "key ID");
      requireIdentifier(tenantId, 128, "tenant ID");
      requireIdentifier(principalId, 256, "principal ID");
      usages = Set.copyOf(Objects.requireNonNull(usages));
      if (usages.isEmpty()) throw new IllegalArgumentException("A workload key requires a usage.");
      Objects.requireNonNull(state);
      if (publicKey == null || !"EdDSA".equals(publicKey.getAlgorithm())) {
        throw new IllegalArgumentException("Workload registry keys must be Ed25519 public keys.");
      }
    }
  }

  private static void requireIdentifier(String value, int maximum, String label) {
    Objects.requireNonNull(value);
    if (value.isEmpty()
        || value.length() > maximum
        || !value.matches("^[A-Za-z0-9][A-Za-z0-9._:@/+\\-]*$")) {
      throw new IllegalArgumentException("The " + label + " is invalid.");
    }
  }
}
