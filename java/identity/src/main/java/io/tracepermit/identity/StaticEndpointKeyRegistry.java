package io.tracepermit.identity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class StaticEndpointKeyRegistry implements EndpointKeyRegistry {
  private final Map<String, EnrolledEndpointKey> keys;

  public StaticEndpointKeyRegistry(List<EnrolledEndpointKey> enrolledKeys) {
    Map<String, EnrolledEndpointKey> configured = new HashMap<>();
    for (EnrolledEndpointKey key : List.copyOf(enrolledKeys)) {
      if (configured.put(index(key.issuer(), key.keyId()), key) != null) {
        throw new IllegalArgumentException(
            "Endpoint registry entries must be unique by issuer and key ID.");
      }
    }
    keys = Map.copyOf(configured);
  }

  @Override
  public Optional<EnrolledEndpointKey> resolve(String issuer, String keyId) {
    return Optional.ofNullable(keys.get(index(issuer, keyId)));
  }

  private static String index(String issuer, String keyId) {
    return issuer + "\u0000" + keyId;
  }
}
