package io.tracepermit.adapters.remote;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import okio.Buffer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class SsrfSafeHttpsJwkSetSourceTest {
  @Test void publicTokenPostOmitsAuthorizationAndRefusesRedirects() throws Exception {
    var tls = RemoteTestSupport.tlsMaterial();
    try (MockWebServer server = tlsServer(tls)) {
      URI uri = server.url("/token").uri();
      var source = source(uri, tls);
      server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{}"));
      assertThat(source.postForm(uri, java.util.Map.of("client_id", "synthetic-client",
          "code", "synthetic+code", "code_verifier", "synthetic-verifier"), null)).isEqualTo("{}");
      var request = server.takeRequest(2, TimeUnit.SECONDS);
      assertThat(request).isNotNull();
      assertThat(request.getMethod()).isEqualTo("POST");
      assertThat(request.getHeader("Authorization") == null).isTrue();
      assertThat(request.getBody().readUtf8()).contains("client_id=synthetic-client", "code=synthetic%2Bcode")
          .doesNotContain("client_secret");
      server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "https://attacker.invalid/token"));
      assertSafeFailure(() -> source.postForm(uri, java.util.Map.of("code", "synthetic"), null));
      assertThat(server.getRequestCount()).isEqualTo(2);
    }
  }
  @Test void tokenPostUsesSamePinnedTlsAndNeverFollowsRedirectWithCredentials() throws Exception {
    var tls = RemoteTestSupport.tlsMaterial();
    try (MockWebServer server = tlsServer(tls)) {
      URI uri = server.url("/token").uri();
      var source = source(uri, tls);
      server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{}"));
      assertThat(source.postForm(uri, java.util.Map.of("code", "synthetic+code", "code_verifier", "verifier"), "Basic synthetic")).isEqualTo("{}");
      var request = server.takeRequest();
      assertThat(request.getMethod()).isEqualTo("POST");
      assertThat(request.getHeader("Authorization")).isEqualTo("Basic synthetic");
      assertThat(request.getBody().readUtf8()).contains("code=synthetic%2Bcode", "code_verifier=verifier");
      server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "https://attacker.invalid/token"));
      assertSafeFailure(() -> source.postForm(uri, java.util.Map.of("code", "sensitive"), "Basic sensitive"));
      assertThat(server.getRequestCount()).isEqualTo(2);
      assertSafeFailure(() -> source.postForm(server.url("/elsewhere").uri(), java.util.Map.of(), "Basic sensitive"));
      assertThat(server.getRequestCount()).isEqualTo(2);
    }
  }
  @Test
  void fetchesAllowlistedJsonOverPinnedTlsWithoutCredentials() throws Exception {
    RemoteTestSupport.TlsMaterial tls = RemoteTestSupport.tlsMaterial();
    try (MockWebServer server = tlsServer(tls)) {
      server.enqueue(
          new MockResponse()
              .setResponseCode(200)
              .setHeader("Content-Type", "application/jwk-set+json; charset=utf-8")
              .setBody("{\"keys\":[]}"));
      URI uri = server.url("/jwks").uri();
      SsrfSafeHttpsJwkSetSource source = source(uri, tls);

      assertThat(source.fetch(uri)).isEqualTo("{\"keys\":[]}");
      var request = server.takeRequest();
      assertThat(request.getHeader("Authorization")).isNull();
      assertThat(request.getHeader("Accept"))
          .isEqualTo("application/jwk-set+json, application/json");
      assertThat(request.getPath()).isEqualTo("/jwks");
    }
  }

  @Test
  void exactUriAllowlistAndRedirectPolicyPreventDestinationChanges() throws Exception {
    RemoteTestSupport.TlsMaterial tls = RemoteTestSupport.tlsMaterial();
    try (MockWebServer server = tlsServer(tls)) {
      URI uri = server.url("/jwks").uri();
      SsrfSafeHttpsJwkSetSource source = source(uri, tls);
      assertThatThrownBy(() -> source.fetch(server.url("/other").uri()))
          .isInstanceOf(JwksFetchException.class);
      assertThat(server.getRequestCount()).isZero();

      server.enqueue(
          new MockResponse()
              .setResponseCode(302)
              .setHeader("Location", "https://attacker.example.invalid/jwks"));
      assertThatThrownBy(() -> source.fetch(uri)).isInstanceOf(JwksFetchException.class);
      assertThat(server.getRequestCount()).isOne();
    }
  }

  @Test
  void statusMediaTypeSizeUtf8AndTimeoutFailuresAreBounded() throws Exception {
    RemoteTestSupport.TlsMaterial tls = RemoteTestSupport.tlsMaterial();
    try (MockWebServer server = tlsServer(tls)) {
      URI uri = server.url("/jwks").uri();
      SsrfSafeHttpsJwkSetSource source = source(uri, tls);
      server.enqueue(new MockResponse().setResponseCode(500));
      assertSafeFailure(() -> source.fetch(uri));
      server.enqueue(
          new MockResponse()
              .setResponseCode(200)
              .setHeader("Content-Type", "text/plain")
              .setBody("{}"));
      assertSafeFailure(() -> source.fetch(uri));
      server.enqueue(
          new MockResponse()
              .setResponseCode(200)
              .setHeader("Content-Type", "application/json")
              .setBody("x".repeat(SsrfSafeHttpsJwkSetSource.MAXIMUM_RESPONSE_BYTES + 1)));
      assertSafeFailure(() -> source.fetch(uri));
      server.enqueue(
          new MockResponse()
              .setResponseCode(200)
              .setHeader("Content-Type", "application/json")
              .setBody(new Buffer().write(new byte[] {(byte) 0xc3, (byte) 0x28})));
      assertSafeFailure(() -> source.fetch(uri));
      server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
      SsrfSafeHttpsJwkSetSource shortTimeout =
          source(uri, tls, Duration.ofMillis(150));
      assertSafeFailure(() -> shortTimeout.fetch(uri));
    }
  }

  @Test
  void unsafeUrisAndConfigurationAreRejectedBeforeNetworkAccess() {
    for (String value :
        List.of(
            "http://keys.example.invalid/jwks",
            "https://user@keys.example.invalid/jwks",
            "https://keys.example.invalid/jwks?next=x",
            "https://keys.example.invalid/jwks#fragment",
            "https://127.0.0.1/jwks",
            "https://[::1]/jwks",
            "https://keys.example.invalid.:443/jwks",
            "https://keys.example.invalid:65536/jwks",
            "https://keys.example.invalid")) {
      assertThatThrownBy(
              () ->
                  new SsrfSafeHttpsJwkSetSource(
                      Set.of(URI.create(value)), Duration.ofSeconds(1), Duration.ofSeconds(2)))
          .as(value)
          .isInstanceOfAny(IllegalArgumentException.class, NullPointerException.class);
    }
    assertThatThrownBy(
            () ->
                new SsrfSafeHttpsJwkSetSource(
                    Set.of(), Duration.ofSeconds(1), Duration.ofSeconds(2)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new SsrfSafeHttpsJwkSetSource(
                    Set.of(URI.create("https://keys.example.invalid/jwks")),
                    Duration.ofSeconds(11),
                    Duration.ofSeconds(2)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void productionAddressPolicyRejectsLocalPrivateReservedAndMixedDnsAnswers() throws Exception {
    for (String address :
        List.of(
            "0.0.0.0",
            "10.0.0.1",
            "100.64.0.1",
            "127.0.0.1",
            "169.254.169.254",
            "172.16.0.1",
            "192.168.0.1",
            "192.0.2.1",
            "198.18.0.1",
            "198.51.100.1",
            "203.0.113.1",
            "224.0.0.1",
            "::1",
            "fc00::1",
            "fe80::1",
            "2001:db8::1",
            "64:ff9b::a9fe:a9fe",
            "2001::1",
            "2001:2::1",
            "2001:10::1",
            "2002::1")) {
      assertThat(SsrfSafeHttpsJwkSetSource.isPublicAddress(InetAddress.getByName(address)))
          .as(address)
          .isFalse();
    }
    assertThat(SsrfSafeHttpsJwkSetSource.isPublicAddress(InetAddress.getByName("8.8.8.8")))
        .isTrue();
    assertThat(
            SsrfSafeHttpsJwkSetSource.isPublicAddress(
                InetAddress.getByName("2606:4700:4700::1111")))
        .isTrue();

    URI uri = URI.create("https://keys.example.invalid/jwks");
    SsrfSafeHttpsJwkSetSource mixed =
        new SsrfSafeHttpsJwkSetSource(
            Set.of(uri),
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            ignored ->
                List.of(
                    InetAddress.getByName("8.8.8.8"), InetAddress.getByName("127.0.0.1")),
            SsrfSafeHttpsJwkSetSource::isPublicAddress,
            null,
            null);
    assertSafeFailure(() -> mixed.fetch(uri));
  }

  @Test
  void productionConstructorRejectsLoopbackResolutionAndExcessiveDnsAnswers() throws Exception {
    URI localhost = URI.create("https://localhost/jwks");
    SsrfSafeHttpsJwkSetSource production =
        new SsrfSafeHttpsJwkSetSource(
            Set.of(localhost), Duration.ofSeconds(1), Duration.ofSeconds(2));
    assertSafeFailure(() -> production.fetch(localhost));

    URI uri = URI.create("https://keys.example.invalid/jwks");
    SsrfSafeHttpsJwkSetSource excessive =
        new SsrfSafeHttpsJwkSetSource(
            Set.of(uri),
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            ignored -> java.util.Collections.nCopies(9, InetAddress.getByName("8.8.8.8")),
            ignored -> true,
            null,
            null);
    assertSafeFailure(() -> excessive.fetch(uri));

    SsrfSafeHttpsJwkSetSource unresolved =
        new SsrfSafeHttpsJwkSetSource(
            Set.of(uri),
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            ignored -> {
              throw new java.net.UnknownHostException("resolver-secret");
            },
            ignored -> true,
            null,
            null);
    assertSafeFailure(() -> unresolved.fetch(uri));

    RemoteTestSupport.TlsMaterial tls = RemoteTestSupport.tlsMaterial();
    assertThatThrownBy(
            () ->
                new SsrfSafeHttpsJwkSetSource(
                    Set.of(uri),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(2),
                    ignored -> List.of(InetAddress.getByName("8.8.8.8")),
                    ignored -> true,
                    tls.client().sslSocketFactory(),
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("together");
  }

  @Test
  void dnsResolutionStageIsBoundedByTotalCallTimeout() throws Exception {
    RemoteTestSupport.TlsMaterial tls = RemoteTestSupport.tlsMaterial();
    try (MockWebServer server = tlsServer(tls)) {
      URI uri = server.url("/jwks").uri();
      CountDownLatch release = new CountDownLatch(1);
      SsrfSafeHttpsJwkSetSource slowDnsSource =
          new SsrfSafeHttpsJwkSetSource(
              Set.of(uri),
              Duration.ofMillis(50),
              Duration.ofMillis(100),
              hostname -> {
                try {
                  if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("latch timed out");
                  }
                } catch (InterruptedException exception) {
                  Thread.currentThread().interrupt();
                }
                return List.of(InetAddress.getByName("127.0.0.1"));
              },
              ignored -> true,
              tls.client().sslSocketFactory(),
              tls.client().trustManager());

      try {
        long start = System.nanoTime();
        assertSafeFailure(() -> slowDnsSource.fetch(uri));
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertThat(durationMs).isLessThan(1000);
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void exhaustedBoundedDnsWorkersFailClosedAndRecoverWhenReleased() throws Exception {
    RemoteTestSupport.TlsMaterial tls = RemoteTestSupport.tlsMaterial();
    try (MockWebServer server = tlsServer(tls)) {
      URI uri = server.url("/jwks").uri();
      server.enqueue(
          new MockResponse()
              .setResponseCode(200)
              .setHeader("Content-Type", "application/json")
              .setBody("{\"keys\":[]}"));
      server.enqueue(
          new MockResponse()
              .setResponseCode(200)
              .setHeader("Content-Type", "application/json")
              .setBody("{\"keys\":[]}"));

      CountDownLatch dnsRunning = new CountDownLatch(1);
      CountDownLatch releaseDns = new CountDownLatch(1);

      try (BoundedHostResolver boundedResolver = new BoundedHostResolver(1, 0)) {
        SsrfSafeHttpsJwkSetSource.HostResolver blockingResolver =
            hostname -> {
              dnsRunning.countDown();
              try {
                if (!releaseDns.await(5, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("latch timed out");
                }
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
              }
              return List.of(InetAddress.getByName("127.0.0.1"));
            };

        SsrfSafeHttpsJwkSetSource source =
            new SsrfSafeHttpsJwkSetSource(
                Set.of(uri),
                Duration.ofSeconds(1),
                Duration.ofSeconds(5),
                blockingResolver,
                ignored -> true,
                tls.client().sslSocketFactory(),
                tls.client().trustManager(),
                boundedResolver);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
          Future<String> future1 = executor.submit(() -> source.fetch(uri));

          assertThat(dnsRunning.await(5, TimeUnit.SECONDS)).isTrue();

          // Saturated DNS worker capacity fails closed immediately
          assertSafeFailure(() -> source.fetch(uri));

          // Release worker
          releaseDns.countDown();
          assertThat(future1.get(5, TimeUnit.SECONDS)).isEqualTo("{\"keys\":[]}");
          Thread.sleep(50);

          // Succeeded once worker capacity was released
          assertThat(source.fetch(uri)).isEqualTo("{\"keys\":[]}");
        } finally {
          releaseDns.countDown();
          executor.shutdown();
          assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
      }
    }
  }

  private static MockWebServer tlsServer(RemoteTestSupport.TlsMaterial tls) throws Exception {
    MockWebServer server = new MockWebServer();
    server.useHttps(tls.server().sslSocketFactory(), false);
    server.start();
    return server;
  }

  private static SsrfSafeHttpsJwkSetSource source(
      URI uri, RemoteTestSupport.TlsMaterial tls) throws Exception {
    return source(uri, tls, Duration.ofSeconds(2));
  }

  private static SsrfSafeHttpsJwkSetSource source(
      URI uri, RemoteTestSupport.TlsMaterial tls, Duration callTimeout) throws Exception {
    return new SsrfSafeHttpsJwkSetSource(
        Set.of(uri),
        Duration.ofSeconds(1),
        callTimeout,
        ignored -> List.of(InetAddress.getByName("127.0.0.1")),
        ignored -> true,
        tls.client().sslSocketFactory(),
        tls.client().trustManager());
  }

  private static void assertSafeFailure(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
    assertThatThrownBy(operation)
        .isInstanceOf(JwksFetchException.class)
        .hasMessage("The configured OIDC verification keys could not be fetched safely.")
        .hasNoCause();
  }

  @Test
  void defaultDnsCapacityMustNotQueueHealthyHostBehindTwoStalledLookupsWithSixWorkersUnused() throws Exception {
    CountDownLatch blocked = new CountDownLatch(2), release = new CountDownLatch(1), healthy = new CountDownLatch(1);
    var callers = Executors.newFixedThreadPool(3, runnable -> { var t = new Thread(runnable); t.setDaemon(true); return t; });
    try (var resolver = new BoundedHostResolver(BoundedHostResolver.DEFAULT_MAX_WORKERS, BoundedHostResolver.DEFAULT_MAX_PENDING)) {
      try {
        for (String host : new String[] {"a.invalid", "b.invalid"}) callers.submit(() -> resolver.resolve(name -> {
          blocked.countDown();
          try { if (!release.await(4, TimeUnit.SECONDS)) throw new IllegalStateException("diagnostic timeout"); }
          catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
          return List.of(InetAddress.getLoopbackAddress());
        }, host, Duration.ofSeconds(5)));
        assertThat(blocked.await(2, TimeUnit.SECONDS)).isTrue();
        callers.submit(() -> resolver.resolve(name -> {
          healthy.countDown(); return List.of(InetAddress.getLoopbackAddress());
        }, "healthy.invalid", Duration.ofSeconds(5)));
        boolean progressed = healthy.await(2, TimeUnit.SECONDS);
        var field = BoundedHostResolver.class.getDeclaredField("executor"); field.setAccessible(true);
        var pool = (java.util.concurrent.ThreadPoolExecutor) field.get(resolver);
        assertThat(progressed).as("healthy DNS stranded: active=%s, max=%s, queued=%s", pool.getActiveCount(),
            pool.getMaximumPoolSize(), pool.getQueue().size()).isTrue();
      } finally { release.countDown(); }
    } finally {
      callers.shutdownNow(); assertThat(callers.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void combinedDnsDelayPlusHttpResponseDelayEnforcesOverallCallTimeout() throws Exception {
    RemoteTestSupport.TlsMaterial tls = RemoteTestSupport.tlsMaterial();
    try (MockWebServer server = tlsServer(tls)) {
      server.enqueue(
          new MockResponse()
              .setResponseCode(200)
              .setHeader("Content-Type", "application/jwk-set+json; charset=utf-8")
              .setBodyDelay(250, TimeUnit.MILLISECONDS)
              .setBody("{\"keys\":[]}"));
      URI uri = server.url("/jwks").uri();

      Duration totalCallTimeout = Duration.ofMillis(300);
      Duration dnsDelay = Duration.ofMillis(150);

      SsrfSafeHttpsJwkSetSource.HostResolver delayedResolver =
          hostname -> {
            try {
              Thread.sleep(dnsDelay.toMillis());
            } catch (InterruptedException exception) {
              Thread.currentThread().interrupt();
            }
            return List.of(InetAddress.getByName("127.0.0.1"));
          };

      try (BoundedHostResolver boundedResolver =
          new BoundedHostResolver(
              BoundedHostResolver.DEFAULT_MAX_WORKERS,
              BoundedHostResolver.DEFAULT_MAX_PENDING)) {
        SsrfSafeHttpsJwkSetSource source =
            new SsrfSafeHttpsJwkSetSource(
                Set.of(uri),
                Duration.ofMillis(100),
                totalCallTimeout,
                delayedResolver,
                ignored -> true,
                tls.client().sslSocketFactory(),
                tls.client().trustManager(),
                boundedResolver);

        long startNanos = System.nanoTime();
        assertSafeFailure(() -> source.fetch(uri));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertThat(elapsedMillis).isGreaterThanOrEqualTo(200).isLessThan(2000);
      }
    }
  }
}
