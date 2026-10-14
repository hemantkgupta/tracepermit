package io.tracepermit.identity;

import java.util.Optional;

@FunctionalInterface
public interface OidcProviderRegistry {
  Optional<OidcProviderConfiguration> resolve(String issuer);
}
