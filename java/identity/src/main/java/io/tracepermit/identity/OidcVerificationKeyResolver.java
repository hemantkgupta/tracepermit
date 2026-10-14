package io.tracepermit.identity;

import java.security.interfaces.RSAPublicKey;
import java.time.Instant;

@FunctionalInterface
public interface OidcVerificationKeyResolver {
  RSAPublicKey resolve(
      OidcProviderConfiguration provider, String keyId, String algorithm, Instant now);
}
