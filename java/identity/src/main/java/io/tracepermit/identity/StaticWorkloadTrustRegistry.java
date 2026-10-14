package io.tracepermit.identity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class StaticWorkloadTrustRegistry implements WorkloadTrustRegistry {
  private final Map<String, TrustedWorkloadKey> keys;

  public StaticWorkloadTrustRegistry(List<TrustedWorkloadKey> trustedKeys) {
    Map<String, TrustedWorkloadKey> configured = new HashMap<>();
    for (TrustedWorkloadKey key : List.copyOf(trustedKeys)) {
      if (configured.put(index(key.issuer(), key.keyId()), key) != null) {
        throw new IllegalArgumentException(
            "Workload registry entries must be unique by issuer and key ID.");
      }
    }
    keys = Map.copyOf(configured);
  }

  @Override
  public Optional<TrustedWorkloadKey> resolve(String issuer, String keyId) {
    return Optional.ofNullable(keys.get(index(issuer, keyId)));
  }

  private static String index(String issuer, String keyId) {
    return issuer + "\u0000" + keyId;
  }
}
