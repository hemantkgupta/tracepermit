package io.tracepermit.identity;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class CachingOidcJwkResolverTest {
  @Test
  void cachesKnownKeysAndRefreshesOnceForRotationAfterCooldown() {
    KeyPair first = OidcTestSupport.rsaKeyPair();
    KeyPair second = OidcTestSupport.rsaKeyPair();
    AtomicReference<String> document =
        new AtomicReference<>(OidcTestSupport.publicJwks(first));
    AtomicInteger fetches = new AtomicInteger();
    CachingOidcJwkResolver resolver =
        new CachingOidcJwkResolver(
            ignored -> {
              fetches.incrementAndGet();
              return document.get();
            });

    assertThat(
            resolver.resolve(
                OidcTestSupport.provider(),
                OidcTestSupport.KEY_ID,
                "RS256",
                OidcTestSupport.NOW))
        .isEqualTo(first.getPublic());
    assertThat(
            resolver.resolve(
                OidcTestSupport.provider(),
                OidcTestSupport.KEY_ID,
                "RS256",
                OidcTestSupport.NOW.plusSeconds(1)))
        .isEqualTo(first.getPublic());
    assertThat(fetches).hasValue(1);

    document.set(OidcTestSupport.publicJwks(first, second));
    assertThat(
            resolver.resolve(
                OidcTestSupport.provider(),
                "oidc-key-002",
                "RS256",
                OidcTestSupport.NOW.plusSeconds(31)))
        .isEqualTo(second.getPublic());
    assertThat(fetches).hasValue(2);
  }

  @Test
  void unknownKeyWithinRefreshCooldownDoesNotCreateARefetchLoop() {
    KeyPair key = OidcTestSupport.rsaKeyPair();
    AtomicInteger fetches = new AtomicInteger();
    CachingOidcJwkResolver resolver =
        new CachingOidcJwkResolver(
            ignored -> {
              fetches.incrementAndGet();
              return OidcTestSupport.publicJwks(key);
            });
    resolver.resolve(
        OidcTestSupport.provider(), OidcTestSupport.KEY_ID, "RS256", OidcTestSupport.NOW);

    assertCode(
        () ->
            resolver.resolve(
                OidcTestSupport.provider(),
                "attacker-controlled-kid",
                "RS256",
                OidcTestSupport.NOW.plusSeconds(1)),
        "INVALID_ACCESS_TOKEN");
    assertThat(fetches).hasValue(1);
  }

  @Test
  void expiredCacheAndUnavailableSourceFailClosedWithoutStaleKeyUse() {
    KeyPair key = OidcTestSupport.rsaKeyPair();
    AtomicReference<String> document =
        new AtomicReference<>(OidcTestSupport.publicJwks(key));
    CachingOidcJwkResolver resolver =
        new CachingOidcJwkResolver(
            ignored -> {
              String current = document.get();
              if (current == null) throw new IllegalStateException("unavailable");
              return current;
            });
    resolver.resolve(
        OidcTestSupport.provider(), OidcTestSupport.KEY_ID, "RS256", OidcTestSupport.NOW);
    document.set(null);

    assertCode(
        () ->
            resolver.resolve(
                OidcTestSupport.provider(),
                OidcTestSupport.KEY_ID,
                "RS256",
                OidcTestSupport.NOW.plusSeconds(61)),
        "OIDC_KEY_SET_UNAVAILABLE");
  }

  @Test
  void malformedAmbiguousPrivateWeakAndUnusableKeySetsFailClosed() throws Exception {
    KeyPair valid = OidcTestSupport.rsaKeyPair();
    assertUnavailable("malformed", "not-json");
    assertUnavailable("null", null);
    assertUnavailable("empty-document", "");
    assertUnavailable(
        "duplicate-json-field", "{\"keys\":[],\"keys\":[]}");
    assertUnavailable("missing-keys", "{}");
    assertUnavailable("empty", "{\"keys\":[]}");
    assertUnavailable(
        "oversized", "x".repeat(CachingOidcJwkResolver.MAXIMUM_JWKS_LENGTH + 1));

    RSAKey publicKey =
        new RSAKey.Builder((RSAPublicKey) valid.getPublic())
            .keyID(OidcTestSupport.KEY_ID)
            .keyUse(KeyUse.SIGNATURE)
            .algorithm(JWSAlgorithm.RS256)
            .build();
    assertUnavailable("duplicate", new JWKSet(List.of(publicKey, publicKey)).toString(false));
    RSAKey privateKey = new RSAKey.Builder(publicKey).privateKey(valid.getPrivate()).build();
    assertUnavailable("private", new JWKSet(privateKey).toString(false));

    KeyPairGenerator weakGenerator = KeyPairGenerator.getInstance("RSA");
    weakGenerator.initialize(1024);
    KeyPair weak = weakGenerator.generateKeyPair();
    RSAKey weakKey =
        new RSAKey.Builder((RSAPublicKey) weak.getPublic())
            .keyID(OidcTestSupport.KEY_ID)
            .keyUse(KeyUse.SIGNATURE)
            .algorithm(JWSAlgorithm.RS256)
            .build();
    assertUnavailable("weak", new JWKSet(weakKey).toString(false));

    RSAKey wrongUse =
        new RSAKey.Builder((RSAPublicKey) valid.getPublic())
            .keyID(OidcTestSupport.KEY_ID)
            .keyUse(KeyUse.ENCRYPTION)
            .algorithm(JWSAlgorithm.RS256)
            .build();
    assertUnavailable("wrong-use", new JWKSet(wrongUse).toString(false));
  }

  @Test
  void concurrentUnknownKeyRotationPerformsOneRefresh() throws Exception {
    KeyPair first = OidcTestSupport.rsaKeyPair();
    KeyPair second = OidcTestSupport.rsaKeyPair();
    AtomicReference<String> document =
        new AtomicReference<>(OidcTestSupport.publicJwks(first));
    AtomicInteger fetches = new AtomicInteger();
    CachingOidcJwkResolver resolver =
        new CachingOidcJwkResolver(
            ignored -> {
              fetches.incrementAndGet();
              return document.get();
            });
    resolver.resolve(
        OidcTestSupport.provider(), OidcTestSupport.KEY_ID, "RS256", OidcTestSupport.NOW);
    document.set(OidcTestSupport.publicJwks(first, second));
    Instant afterCooldown = OidcTestSupport.NOW.plusSeconds(31);

    var executor = Executors.newFixedThreadPool(16);
    try {
      List<Callable<RSAPublicKey>> calls = new ArrayList<>();
      for (int index = 0; index < 32; index++) {
        calls.add(
            () ->
                resolver.resolve(
                    OidcTestSupport.provider(), "oidc-key-002", "RS256", afterCooldown));
      }
      var results = executor.invokeAll(calls);
      for (var result : results) assertThat(result.get()).isEqualTo(second.getPublic());
    } finally {
      executor.shutdown();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(fetches).hasValue(2);
  }

  @Test
  void algorithmsAndKeyIdentifiersAreAllowlisted() {
    CachingOidcJwkResolver resolver =
        new CachingOidcJwkResolver(
            ignored -> OidcTestSupport.publicJwks(OidcTestSupport.rsaKeyPair()));
    assertCode(
        () ->
            resolver.resolve(
                OidcTestSupport.provider(), OidcTestSupport.KEY_ID, "RS512", OidcTestSupport.NOW),
        "INVALID_ACCESS_TOKEN");
    assertCode(
        () ->
            resolver.resolve(
                OidcTestSupport.provider(), "bad\nkey", "RS256", OidcTestSupport.NOW),
        "INVALID_ACCESS_TOKEN");
  }

  private static void assertUnavailable(String label, String document) {
    CachingOidcJwkResolver resolver = new CachingOidcJwkResolver(ignored -> document);
    assertCode(
        label,
        () ->
            resolver.resolve(
                OidcTestSupport.provider(),
                OidcTestSupport.KEY_ID,
                "RS256",
                OidcTestSupport.NOW),
        "OIDC_KEY_SET_UNAVAILABLE");
  }

  private static void assertCode(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable operation, String expectedCode) {
    assertCode("operation", operation, expectedCode);
  }

  private static void assertCode(
      String label,
      org.assertj.core.api.ThrowableAssert.ThrowingCallable operation,
      String expectedCode) {
    assertThatThrownBy(operation)
        .as(label)
        .isInstanceOf(AuthenticationException.class)
        .extracting(error -> ((AuthenticationException) error).code())
        .isEqualTo(expectedCode);
  }
}
