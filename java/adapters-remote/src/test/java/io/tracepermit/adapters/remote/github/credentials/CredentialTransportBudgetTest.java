package io.tracepermit.adapters.remote.github.credentials;

import static io.tracepermit.adapters.remote.github.credentials.GitHubInstallationCredentialProviderTest.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.net.InetAddress;
import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.Buffer;
import okio.GzipSink;
import okio.Okio;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Concrete loopback HTTP policy tests, with no production endpoint, credentials or target. */
final class CredentialTransportBudgetTest {
  private static KeyPair runtimeKeys;
  @BeforeAll static void keys() throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); runtimeKeys = generator.generateKeyPair();
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void decodedBodyLimitAcceptsExactBoundaryAndRejectsCompressedOverflow(boolean overflow) throws Exception {
    try (var server = new MockWebServer()) {
      server.start(InetAddress.getByName("127.0.0.1"), 0);
      server.enqueue(reply(200, installation()));
      String json = token().toString();
      String body = json + " ".repeat(65_536 - json.length() + (overflow ? 1 : 0));
      var compressed = new Buffer();
      try (var gzip = Okio.buffer(new GzipSink(compressed))) { gzip.writeUtf8(body); }
      server.enqueue(new MockResponse().setResponseCode(201).setHeader("Content-Encoding", "gzip").setBody(compressed));
      var provider = provider(server, Duration.ofSeconds(3));
      if (overflow) failure(provider);
      else assertThat(provider.getInstallationCredentials(REPOSITORY).token().equals(TOKEN)).isTrue();
      assertThat(server.getRequestCount()).isEqualTo(2);
    }
  }

  @Test void oneMonotonicBudgetCoversBothCompleteResponseBodies() throws Exception {
    try (var server = new MockWebServer()) {
      server.start(InetAddress.getByName("127.0.0.1"), 0);
      server.enqueue(reply(200, installation()).setBodyDelay(400, TimeUnit.MILLISECONDS));
      server.enqueue(reply(201, token()).setBodyDelay(400, TimeUnit.MILLISECONDS));
      var provider = provider(server, Duration.ofMillis(650));
      long start = System.nanoTime();
      var rejected = failure(provider);
      assertThat(rejected.errorCode()).isEqualTo("TRANSPORT_UNAVAILABLE");
      assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
      assertThat(server.getRequestCount()).isEqualTo(2);
    }
  }

  @ParameterizedTest @ValueSource(ints = {307, 308, 408, 421, 503})
  void tokenPostCannotFollowRedirectsOrRetryStatuses(int status) throws Exception {
    try (var server = new MockWebServer()) {
      server.start(InetAddress.getByName("127.0.0.1"), 0);
      server.enqueue(reply(200, installation()));
      server.enqueue(new MockResponse().setResponseCode(status).setHeader("Retry-After", "0")
          .setHeader("Location", "http://127.0.0.1:" + server.getPort() + "/unrelated").setBody("synthetic-canary"));
      // A possible follow-up must fail the count even if it would otherwise return valid credentials.
      server.enqueue(reply(201, token()));
      failure(provider(server, Duration.ofSeconds(3)));
      assertThat(server.getRequestCount()).isEqualTo(2);
      assertThat(server.takeRequest(1, TimeUnit.SECONDS).getMethod()).isEqualTo("GET");
      assertThat(server.takeRequest(1, TimeUnit.SECONDS).getMethod()).isEqualTo("POST");
    }
  }

  private static GitHubInstallationCredentialProvider provider(MockWebServer server, Duration budget) {
    return new GitHubInstallationCredentialProvider(CONFIG, () -> runtimeKeys.getPrivate(),
        GitHubCredentialTransport.synthetic(URI.create("http://127.0.0.1:" + server.getPort()), 71, budget, 65_536),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }
  private static GitHubCredentialException failure(GitHubInstallationCredentialProvider provider) {
    Throwable problem = catchThrowable(() -> provider.getInstallationCredentials(REPOSITORY));
    assertThat(problem).isInstanceOf(GitHubCredentialException.class);
    assertThat(problem.getCause()).isNull(); assertThat(problem.getSuppressed()).isEmpty();
    assertThat(problem.toString().contains(TOKEN)).isFalse();
    return (GitHubCredentialException) problem;
  }
}
