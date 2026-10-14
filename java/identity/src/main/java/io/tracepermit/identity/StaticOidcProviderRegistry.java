package io.tracepermit.identity;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class StaticOidcProviderRegistry implements OidcProviderRegistry {
  private final Map<String, OidcProviderConfiguration> providers;

  public StaticOidcProviderRegistry(Collection<OidcProviderConfiguration> providers) {
    Objects.requireNonNull(providers);
    Map<String, OidcProviderConfiguration> configured = new HashMap<>();
    for (OidcProviderConfiguration provider : providers) {
      String issuer = provider.issuer().toString();
      if (configured.put(issuer, provider) != null) {
        throw new IllegalArgumentException("OIDC issuers must be unique.");
      }
    }
    if (configured.isEmpty()) throw new IllegalArgumentException("At least one OIDC provider is required.");
    this.providers = Map.copyOf(configured);
  }

  @Override
  public Optional<OidcProviderConfiguration> resolve(String issuer) {
    return Optional.ofNullable(providers.get(issuer));
  }
}
