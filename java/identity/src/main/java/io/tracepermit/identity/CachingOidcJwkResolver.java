package io.tracepermit.identity;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.text.ParseException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class CachingOidcJwkResolver implements OidcVerificationKeyResolver, AutoCloseable {
  static final int MAXIMUM_JWKS_LENGTH = 65_536;
  private static final int MINIMUM_RSA_MODULUS_BITS = 2048;
  private static final Set<String> PRIVATE_JWK_FIELDS =
      Set.of("d", "p", "q", "dp", "dq", "qi", "oth", "k");
  private static final ObjectMapper STRICT_MAPPER =
      new ObjectMapper(
              JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private final OidcJwkSetSource source;
  private final JwksRefreshCoordinator coordinator;
  private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

  public CachingOidcJwkResolver(OidcJwkSetSource source) {
    this(source, new JwksRefreshCoordinator());
  }

  CachingOidcJwkResolver(OidcJwkSetSource source, JwksRefreshCoordinator coordinator) {
    this.source = Objects.requireNonNull(source);
    this.coordinator = Objects.requireNonNull(coordinator);
  }

  @Override
  public RSAPublicKey resolve(
      OidcProviderConfiguration provider, String keyId, String algorithm, Instant now) {
    Objects.requireNonNull(provider);
    Objects.requireNonNull(now);
    if (!"RS256".equals(algorithm) || !isSafeKeyId(keyId)) throw invalidToken();

    String cacheKey = cacheKey(provider);
    CacheEntry current = cache.get(cacheKey);
    if (current != null && current.expiresAt().isAfter(now)) {
      RSAPublicKey cached = current.keys().get(keyId);
      if (cached != null) return cached;
      if (current.nextForcedRefreshAt().isAfter(now)) throw invalidToken();
    }

    CacheEntry refreshed =
        coordinator.refresh(
            cacheKey,
            provider,
            now,
            () -> {
              CacheEntry latest = cache.get(cacheKey);
              if (latest != null && latest.expiresAt().isAfter(now)) {
                if (latest.keys().containsKey(keyId) || latest.nextForcedRefreshAt().isAfter(now)) {
                  return latest;
                }
              }
              return refresh(provider, now);
            },
            entry -> updateCacheIfMonotonic(cacheKey, entry));
    if (!refreshed.expiresAt().isAfter(now)) {
      CacheEntry latest = cache.get(cacheKey);
      if (latest != null && latest.expiresAt().isAfter(now)) {
        refreshed = latest;
      } else {
        throw unavailable(null);
      }
    }
    RSAPublicKey resolved = refreshed.keys().get(keyId);
    if (resolved == null) throw invalidToken();
    return resolved;
  }

  private CacheEntry refresh(OidcProviderConfiguration provider, Instant now) {
    String document;
    try {
      document = source.fetch(provider.jwksUri());
    } catch (RuntimeException exception) {
      throw unavailable(exception);
    }
    if (document == null
        || document.isEmpty()
        || document.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_JWKS_LENGTH) {
      throw unavailable(null);
    }

    Map<String, RSAPublicKey> keys = parseKeys(document);
    return new CacheEntry(
        Map.copyOf(keys),
        now.plus(provider.jwksCacheLifetime()),
        now.plus(provider.forcedRefreshCooldown()));
  }

  private void updateCacheIfMonotonic(String cacheKey, CacheEntry refreshed) {
    cache.compute(
        cacheKey,
        (key, current) -> {
          if (current == null || refreshed.expiresAt().isAfter(current.expiresAt())) {
            return refreshed;
          }
          return current;
        });
  }

  private static Map<String, RSAPublicKey> parseKeys(String document) {
    validateStructure(document);
    JWKSet set;
    try {
      set = JWKSet.parse(document);
    } catch (ParseException | RuntimeException exception) {
      throw unavailable(exception);
    }
    if (set.getKeys().isEmpty()) throw unavailable(null);

    Set<String> observedKeyIds = new HashSet<>();
    Map<String, RSAPublicKey> resolved = new HashMap<>();
    for (JWK key : set.getKeys()) {
      String keyId = key.getKeyID();
      if (!isSafeKeyId(keyId) || !observedKeyIds.add(keyId) || key.isPrivate()) {
        throw unavailable(null);
      }
      if (!(key instanceof RSAKey rsaKey)) continue;
      if (!KeyUse.SIGNATURE.equals(rsaKey.getKeyUse())
          || !JWSAlgorithm.RS256.equals(rsaKey.getAlgorithm())) {
        continue;
      }
      try {
        RSAPublicKey publicKey = toPublicKey(rsaKey);
        if (publicKey.getModulus().bitLength() < MINIMUM_RSA_MODULUS_BITS) {
          throw unavailable(null);
        }
        resolved.put(keyId, publicKey);
      } catch (RuntimeException exception) {
        throw unavailable(exception);
      }
    }
    if (resolved.isEmpty()) throw unavailable(null);
    return resolved;
  }

  private static void validateStructure(String document) {
    try {
      JsonNode root = STRICT_MAPPER.readTree(document);
      JsonNode keys = root == null ? null : root.get("keys");
      if (root == null || !root.isObject() || keys == null || !keys.isArray()) {
        throw unavailable(null);
      }
      for (JsonNode key : keys) {
        if (!key.isObject()) throw unavailable(null);
        for (String privateField : PRIVATE_JWK_FIELDS) {
          if (key.has(privateField)) throw unavailable(null);
        }
      }
    } catch (IOException exception) {
      throw unavailable(null);
    }
  }

  private static RSAPublicKey toPublicKey(RSAKey key) {
    try {
      var specification =
          new RSAPublicKeySpec(
              key.getModulus().decodeToBigInteger(),
              key.getPublicExponent().decodeToBigInteger());
      return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(specification);
    } catch (Exception exception) {
      throw new IllegalArgumentException(
          "The configured JWKS contains an unusable RSA key.", exception);
    }
  }

  private static boolean isSafeKeyId(String keyId) {
    return keyId != null
        && !keyId.isEmpty()
        && keyId.length() <= 128
        && keyId.chars().allMatch(value -> value >= 0x21 && value <= 0x7e);
  }

  private static String cacheKey(OidcProviderConfiguration provider) {
    return provider.issuer() + "\u0000" + provider.jwksUri();
  }

  private static AuthenticationException invalidToken() {
    return new AuthenticationException(
        "INVALID_ACCESS_TOKEN", "The bearer token verification key is not trusted.");
  }

  private static AuthenticationException unavailable(Throwable ignoredCause) {
    return new AuthenticationException(
        "OIDC_KEY_SET_UNAVAILABLE", "The configured OIDC verification keys are unavailable.");
  }

  @Override
  public void close() {
    coordinator.close();
  }

  record CacheEntry(
      Map<String, RSAPublicKey> keys, Instant expiresAt, Instant nextForcedRefreshAt) {}
}
