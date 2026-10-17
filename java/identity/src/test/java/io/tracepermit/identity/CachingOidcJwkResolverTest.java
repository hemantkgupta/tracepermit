package io.tracepermit.identity;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

  @Test
  void latchControlledProviderABlockedWhileWarmProviderBResolves() throws Exception {
    KeyPair keyA = OidcTestSupport.rsaKeyPair();
    KeyPair keyB = OidcTestSupport.rsaKeyPair();
    OidcProviderConfiguration providerA = OidcTestSupport.provider();
    OidcProviderConfiguration providerB = providerWithIssuer("https://provider-b.example.invalid");

    CountDownLatch fetchAStarted = new CountDownLatch(1);
    CountDownLatch releaseFetchA = new CountDownLatch(1);

    CachingOidcJwkResolver resolver =
        new CachingOidcJwkResolver(
            uri -> {
              if (uri.equals(providerA.jwksUri())) {
                fetchAStarted.countDown();
                try {
                  if (!releaseFetchA.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("latch timed out");
                  }
                } catch (InterruptedException exception) {
                  Thread.currentThread().interrupt();
                }
                return OidcTestSupport.publicJwks(keyA);
              }
              if (uri.equals(providerB.jwksUri())) {
                return OidcTestSupport.publicJwks(keyB);
              }
              throw new IllegalArgumentException("Unexpected URI: " + uri);
            });

    try {
      // Warm provider B first
      assertThat(resolver.resolve(providerB, OidcTestSupport.KEY_ID, "RS256", OidcTestSupport.NOW))
          .isEqualTo(keyB.getPublic());

      ExecutorService executor = Executors.newSingleThreadExecutor();
      try {
        Future<RSAPublicKey> futureA =
            executor.submit(
                () ->
                    resolver.resolve(
                        providerA, OidcTestSupport.KEY_ID, "RS256", OidcTestSupport.NOW));

        assertThat(fetchAStarted.await(5, TimeUnit.SECONDS)).isTrue();

        // While Provider A is blocked in source.fetch, warm Provider B resolves immediately
        long start = System.nanoTime();
        RSAPublicKey resolvedB =
            resolver.resolve(
                providerB, OidcTestSupport.KEY_ID, "RS256", OidcTestSupport.NOW.plusSeconds(1));
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(resolvedB).isEqualTo(keyB.getPublic());
        assertThat(durationMs).isLessThan(500);

        releaseFetchA.countDown();
        assertThat(futureA.get(5, TimeUnit.SECONDS)).isEqualTo(keyA.getPublic());
      } finally {
        releaseFetchA.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      }
    } finally {
      resolver.close();
    }
  }

  @Test
  void independentProviderRefreshProgress() throws Exception {
    KeyPair keyA = OidcTestSupport.rsaKeyPair();
    KeyPair keyB = OidcTestSupport.rsaKeyPair();
    OidcProviderConfiguration providerA = OidcTestSupport.provider();
    OidcProviderConfiguration providerB = providerWithIssuer("https://provider-b.example.invalid");

    CountDownLatch fetchAStarted = new CountDownLatch(1);
    CountDownLatch releaseFetchA = new CountDownLatch(1);

    CachingOidcJwkResolver resolver =
        new CachingOidcJwkResolver(
            uri -> {
              if (uri.equals(providerA.jwksUri())) {
                fetchAStarted.countDown();
                try {
                  if (!releaseFetchA.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("latch timed out");
                  }
                } catch (InterruptedException exception) {
                  Thread.currentThread().interrupt();
                }
                return OidcTestSupport.publicJwks(keyA);
              }
              if (uri.equals(providerB.jwksUri())) {
                return OidcTestSupport.publicJwks(keyB);
              }
              throw new IllegalArgumentException("Unexpected URI: " + uri);
            });

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<RSAPublicKey> futureA =
          executor.submit(
              () ->
                  resolver.resolve(
                      providerA, OidcTestSupport.KEY_ID, "RS256", OidcTestSupport.NOW));

      assertThat(fetchAStarted.await(5, TimeUnit.SECONDS)).isTrue();

      // Provider B needs cold refresh and must make progress independently of stuck Provider A
      RSAPublicKey resolvedB =
          resolver.resolve(providerB, OidcTestSupport.KEY_ID, "RS256", OidcTestSupport.NOW);
      assertThat(resolvedB).isEqualTo(keyB.getPublic());

      releaseFetchA.countDown();
      assertThat(futureA.get(5, TimeUnit.SECONDS)).isEqualTo(keyA.getPublic());
    } finally {
      releaseFetchA.countDown();
      executor.shutdown();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      resolver.close();
    }
  }

  @Test
  void refreshFailureStormCooldownPreventsRepeatedFetches() {
    OidcProviderConfiguration provider = OidcTestSupport.provider();
    AtomicInteger fetchCount = new AtomicInteger();
    CachingOidcJwkResolver resolver =
        new CachingOidcJwkResolver(
            ignored -> {
              fetchCount.incrementAndGet();
              throw new IllegalStateException("simulated upstream failure");
            });

    try {
      assertCode(
          () -> resolver.resolve(provider, OidcTestSupport.KEY_ID, "RS256", OidcTestSupport.NOW),
          "OIDC_KEY_SET_UNAVAILABLE");
      assertThat(fetchCount).hasValue(1);

      for (int i = 1; i <= 5; i++) {
        int offset = i;
        assertCode(
            () ->
                resolver.resolve(
                    provider,
                    OidcTestSupport.KEY_ID,
                    "RS256",
                    OidcTestSupport.NOW.plusSeconds(offset)),
            "OIDC_KEY_SET_UNAVAILABLE");
      }
      assertThat(fetchCount).hasValue(1);

      assertCode(
          () ->
              resolver.resolve(
                  provider,
                  OidcTestSupport.KEY_ID,
                  "RS256",
                  OidcTestSupport.NOW.plusSeconds(35)),
          "OIDC_KEY_SET_UNAVAILABLE");
      assertThat(fetchCount).hasValue(2);
    } finally {
      resolver.close();
    }
  }

  @Test
  void waiterTimeoutAndInterruptCleanUpInFlightState() throws Exception {
    OidcProviderConfiguration provider = OidcTestSupport.provider();
    CountDownLatch fetchStarted = new CountDownLatch(1);
    CountDownLatch releaseFetch = new CountDownLatch(1);

    JwksRefreshCoordinator coordinator =
        new JwksRefreshCoordinator(
            2, 4, Duration.ofMillis(100), Duration.ofSeconds(10));
    CachingOidcJwkResolver resolver =
        new CachingOidcJwkResolver(
            ignored -> {
              fetchStarted.countDown();
              try {
                if (!releaseFetch.await(5, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("latch timed out");
                }
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
              }
              return OidcTestSupport.publicJwks(OidcTestSupport.rsaKeyPair());
            },
            coordinator);

    try {
      assertCode(
          () -> resolver.resolve(provider, OidcTestSupport.KEY_ID, "RS256", OidcTestSupport.NOW),
          "OIDC_KEY_SET_UNAVAILABLE");
      assertThat(fetchStarted.await(5, TimeUnit.SECONDS)).isTrue();
      releaseFetch.countDown();
      Thread.sleep(100);

      CountDownLatch interruptStarted = new CountDownLatch(1);
      CountDownLatch releaseInterrupt = new CountDownLatch(1);
      OidcProviderConfiguration providerB = providerWithIssuer("https://provider-b.example.invalid");

      CachingOidcJwkResolver interruptResolver =
          new CachingOidcJwkResolver(
              uri -> {
                interruptStarted.countDown();
                try {
                  if (!releaseInterrupt.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("latch timed out");
                  }
                } catch (InterruptedException exception) {
                  Thread.currentThread().interrupt();
                }
                return OidcTestSupport.publicJwks(OidcTestSupport.rsaKeyPair());
              },
              coordinator);

      try {
        Thread testThread = Thread.currentThread();
        ExecutorService interruptExecutor = Executors.newSingleThreadExecutor();
        try {
          Future<?> interruptFuture =
              interruptExecutor.submit(
                  () -> {
                    try {
                      assertThat(interruptStarted.await(5, TimeUnit.SECONDS)).isTrue();
                      testThread.interrupt();
                    } catch (InterruptedException ignored) {
                    }
                  });

          assertCode(
              () ->
                  interruptResolver.resolve(
                      providerB, OidcTestSupport.KEY_ID, "RS256", OidcTestSupport.NOW),
              "OIDC_KEY_SET_UNAVAILABLE");
          assertThat(Thread.interrupted()).isTrue();
          interruptFuture.get(5, TimeUnit.SECONDS);
        } finally {
          interruptExecutor.shutdown();
          assertThat(interruptExecutor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
      } finally {
        releaseInterrupt.countDown();
        interruptResolver.close();
      }
    } finally {
      releaseFetch.countDown();
      resolver.close();
    }
  }

  @Test
  void lateResultCannotPublishOrExtendFreshness() throws Exception {
    KeyPair key = OidcTestSupport.rsaKeyPair();
    OidcProviderConfiguration provider = OidcTestSupport.provider();
    CountDownLatch fetchStarted = new CountDownLatch(1);
    CountDownLatch completeLateFetch = new CountDownLatch(1);

    JwksRefreshCoordinator coordinator =
        new JwksRefreshCoordinator(
            2, 4, Duration.ofMillis(50), Duration.ofMillis(10));
    CachingOidcJwkResolver resolver =
        new CachingOidcJwkResolver(
            ignored -> {
              fetchStarted.countDown();
              try {
                if (!completeLateFetch.await(5, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("latch timed out");
                }
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
              }
              return OidcTestSupport.publicJwks(key);
            },
            coordinator);

    try {
      assertCode(
          () -> resolver.resolve(provider, OidcTestSupport.KEY_ID, "RS256", OidcTestSupport.NOW),
          "OIDC_KEY_SET_UNAVAILABLE");
      assertThat(fetchStarted.await(5, TimeUnit.SECONDS)).isTrue();

      completeLateFetch.countDown();
      Thread.sleep(100);

      // Verify that late result was discarded and did not populate cache.
      // Next call attempts resolution and fails since source is now unavailable.
      CachingOidcJwkResolver checkResolver =
          new CachingOidcJwkResolver(ignored -> null, coordinator);
      assertCode(
          () ->
              checkResolver.resolve(
                  provider,
                  OidcTestSupport.KEY_ID,
                  "RS256",
                  OidcTestSupport.NOW.plusSeconds(1)),
          "OIDC_KEY_SET_UNAVAILABLE");
    } finally {
      completeLateFetch.countDown();
      resolver.close();
    }
  }

  @Test
  void exhaustedBoundedRefreshWorkersFailClosedAndRecoverWhenReleased() throws Exception {
    KeyPair key = OidcTestSupport.rsaKeyPair();
    CountDownLatch workerRunning = new CountDownLatch(1);
    CountDownLatch releaseWorker = new CountDownLatch(1);

    JwksRefreshCoordinator coordinator =
        new JwksRefreshCoordinator(1, 0, Duration.ofSeconds(5), Duration.ofMillis(10));

    OidcProviderConfiguration providerA = OidcTestSupport.provider();
    OidcProviderConfiguration providerB = providerWithIssuer("https://provider-b.example.invalid");

    CachingOidcJwkResolver resolver =
        new CachingOidcJwkResolver(
            uri -> {
              if (uri.equals(providerA.jwksUri())) {
                workerRunning.countDown();
                try {
                  if (!releaseWorker.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("latch timed out");
                  }
                } catch (InterruptedException exception) {
                  Thread.currentThread().interrupt();
                }
                return OidcTestSupport.publicJwks(key);
              }
              return OidcTestSupport.publicJwks(key);
            },
            coordinator);

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<RSAPublicKey> futureA =
          executor.submit(
              () ->
                  resolver.resolve(
                      providerA, OidcTestSupport.KEY_ID, "RS256", OidcTestSupport.NOW));

      assertThat(workerRunning.await(5, TimeUnit.SECONDS)).isTrue();

      // Worker is fully occupied by Provider A. Provider B refresh must fail closed immediately!
      assertCode(
          () -> resolver.resolve(providerB, OidcTestSupport.KEY_ID, "RS256", OidcTestSupport.NOW),
          "OIDC_KEY_SET_UNAVAILABLE");

      // Release worker
      releaseWorker.countDown();
      assertThat(futureA.get(5, TimeUnit.SECONDS)).isEqualTo(key.getPublic());
      Thread.sleep(50);

      // Worker is released. Provider B can now refresh and succeed!
      assertThat(
              resolver.resolve(
                  providerB,
                  OidcTestSupport.KEY_ID,
                  "RS256",
                  OidcTestSupport.NOW.plusSeconds(1)))
          .isEqualTo(key.getPublic());
    } finally {
      releaseWorker.countDown();
      executor.shutdown();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      resolver.close();
    }
  }

  private static OidcProviderConfiguration providerWithIssuer(String issuer) {
    return new OidcProviderConfiguration(
        java.net.URI.create(issuer),
        OidcTestSupport.AUDIENCE,
        java.net.URI.create(issuer + "/.well-known/jwks.json"),
        java.net.URI.create(OidcTestSupport.AUDIENCE + "/.well-known/oauth-protected-resource"),
        List.of(java.net.URI.create(issuer)),
        "TracePermit MCP",
        Map.of("chatgpt-connector", "ChatGPT Web"),
        "org_id",
        Map.of("enterprise-acme", "tenant:acme"),
        Set.of(ConnectorScope.of("aws.logs.discover"), ConnectorScope.of("aws.logs.read")),
        ConnectorOrigin.CHATGPT_WEB,
        Duration.ofMinutes(5),
        Duration.ofMinutes(1),
        Duration.ofSeconds(30));
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
