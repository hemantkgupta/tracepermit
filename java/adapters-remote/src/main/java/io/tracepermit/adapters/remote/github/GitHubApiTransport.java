package io.tracepermit.adapters.remote.github;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tracepermit.adapters.remote.BoundedHostResolver;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Fixed origin, pinned public DNS, platform TLS. Loopback injection is package-private and explicit. */
final class GitHubApiTransport {
  private static final ObjectMapper JSON = new ObjectMapper()
      .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private final URI origin;
  private final Duration connectTimeout;
  private final Duration timeout;
  private final int limit;
  private final boolean synthetic;

  GitHubApiTransport(URI origin, Duration connectTimeout, Duration timeout, int limit,
      SSLSocketFactory tls, X509TrustManager trust) {
    this(origin, connectTimeout, timeout, limit, false);
    if (tls != null || trust != null) throw rejected("TARGET_DESTINATION_REJECTED");
  }

  private GitHubApiTransport(URI origin, Duration connectTimeout, Duration timeout, int limit, boolean synthetic) {
    this.origin = Objects.requireNonNull(origin);
    this.synthetic = synthetic;
    if (synthetic) {
      if (!"http".equals(origin.getScheme()) || !"127.0.0.1".equals(origin.getHost())
          || origin.getPort() < 1 || origin.getPort() > 65535
          || !plainOrigin(origin)) throw rejected("TARGET_DESTINATION_REJECTED");
    } else if (!("https://api.github.com".equals(origin.toString())
        || "https://api.github.com/".equals(origin.toString()))) {
      throw rejected("TARGET_DESTINATION_REJECTED");
    }
    this.connectTimeout = bounded(connectTimeout);
    this.timeout = bounded(timeout);
    if (limit < 1 || limit > 1_048_576) throw new IllegalArgumentException("Invalid response bound.");
    this.limit = limit;
  }

  static GitHubApiTransport synthetic(URI origin, Duration connect, Duration call, int limit) {
    return new GitHubApiTransport(origin, connect, call, limit, true);
  }

  private static boolean plainOrigin(URI uri) {
    return uri.getUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
        && (uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()));
  }

  static Duration bounded(Duration duration) {
    if (duration == null || duration.isZero() || duration.isNegative()
        || duration.compareTo(Duration.ofSeconds(10)) > 0) throw new IllegalArgumentException("Invalid timeout bounds.");
    return duration;
  }

  Session open() {
    long deadline = System.nanoTime() + timeout.toNanos();
    List<InetAddress> pinned = synthetic ? List.of() : BoundedHostResolver.defaultResolver()
        .resolvePublic("api.github.com", timeout);
    var builder = new OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(connectTimeout).readTimeout(timeout).writeTimeout(timeout).callTimeout(timeout);
    builder.addNetworkInterceptor(chain -> {
      var once = chain.request().tag(java.util.concurrent.atomic.AtomicBoolean.class);
      if (once == null || !once.compareAndSet(false, true)) throw new IOException("TARGET_REPEAT_EXCHANGE_REJECTED");
      return chain.proceed(chain.request());
    });
    if (!synthetic) builder.dns(host -> {
      if (!"api.github.com".equals(host)) throw new UnknownHostException("Destination rejected.");
      return pinned;
    });
    return new Session(builder.build(), deadline);
  }

  record Reply(int status, boolean pagination, byte[] bytes) {
    JsonNode json() {
      try {
        JsonNode node = JSON.readTree(bytes);
        if (node == null) throw new IOException();
        return node;
      } catch (IOException failure) { throw rejected("INVALID_TARGET_PAYLOAD"); }
    }
  }

  final class Session implements AutoCloseable {
    private final OkHttpClient client;
    private final long deadline;
    Session(OkHttpClient client, long deadline) { this.client = client; this.deadline = deadline; }

    Reply request(String[] path, java.util.Map<String, String> query, JsonNode payload,
        GitHubAppCredentials credentials, long installationId, Clock clock) throws IOException {
      requireCredentials(credentials, installationId, clock);
      if (deadline - System.nanoTime() <= 0) throw new java.io.InterruptedIOException();
      var url = HttpUrl.get(origin).newBuilder();
      for (String segment : path) url.addPathSegment(segment);
      query.forEach(url::addQueryParameter);
      var request = new Request.Builder().url(url.build())
          .tag(java.util.concurrent.atomic.AtomicBoolean.class, new java.util.concurrent.atomic.AtomicBoolean())
          .header("Authorization", "Bearer " + credentials.token())
          .header("Accept", "application/vnd.github+json")
          .header("X-GitHub-Api-Version", "2022-11-28")
          .header("User-Agent", "TracePermit-AgentCore-Pilot");
      if (payload == null) request.get();
      else request.post(RequestBody.create(payload.toString(), MediaType.get("application/json; charset=utf-8")));
      var call = client.newCall(request.build());
      call.timeout().deadlineNanoTime(deadline);
      try (Response response = call.execute()) {
        byte[] bytes = response.body() == null ? new byte[0]
            : BoundedGitHubResponse.read(response.body().byteStream(), limit);
        if (bytes.length > limit) throw rejected("RESPONSE_TOO_LARGE");
        return new Reply(response.code(), !response.headers("Link").isEmpty(), bytes);
      }
    }

    @Override public void close() {
      client.dispatcher().cancelAll();
      client.connectionPool().evictAll();
      client.dispatcher().executorService().shutdownNow();
    }
  }

  static void requireCredentials(GitHubAppCredentials credential, long installationId, Clock clock) {
    if (credential == null || credential.installationId() != installationId
        || !credential.expiresAt().isAfter(clock.instant()) || credential.token().length() > 8192
        || !credential.token().matches("[A-Za-z0-9_.-]+")) throw rejected("TARGET_CREDENTIAL_REJECTED");
  }

  static GitHubPullRequestException rejected(String code) {
    return new GitHubPullRequestException(code, code, "GitHub target boundary rejected the operation.");
  }
}
