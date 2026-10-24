package io.tracepermit.adapters.remote.github.credentials;

import static io.tracepermit.adapters.remote.github.credentials.GitHubCredentialException.Code.*;
import static io.tracepermit.adapters.remote.github.credentials.GitHubCredentialException.reject;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.adapters.remote.BoundedHostResolver;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Mirrors the existing target transport bounds without exposing its package-private API. */
final class GitHubCredentialTransport implements CredentialExchange.Factory {
  private static final URI PRODUCTION = URI.create("https://api.github.com");
  private final URI origin;
  private final boolean synthetic;
  private final long installation;
  private final Duration timeout;
  private final int limit;

  static GitHubCredentialTransport production(long installation) {
    return new GitHubCredentialTransport(PRODUCTION, false, installation, Duration.ofSeconds(10), 65_536);
  }
  static GitHubCredentialTransport synthetic(URI origin, long installation, Duration timeout, int limit) {
    return new GitHubCredentialTransport(origin, true, installation, timeout, limit);
  }
  private GitHubCredentialTransport(URI origin, boolean synthetic, long installation, Duration timeout, int limit) {
    if (origin == null || (synthetic ? !plainLoopback(origin) : !PRODUCTION.equals(origin))) throw reject(DESTINATION_REJECTED);
    if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(10)) > 0
        || limit < 1 || limit > 65_536 || installation < 1) throw reject(CONFIGURATION_REJECTED);
    this.origin = origin; this.synthetic = synthetic; this.installation = installation;
    this.timeout = timeout; this.limit = limit;
  }
  private static boolean plainLoopback(URI uri) {
    return "http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost()) && uri.getPort() > 0 && uri.getPort() <= 65535
        && uri.getUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
        && (uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()));
  }
  @Override public CredentialExchange open() {
    long deadline = System.nanoTime() + timeout.toNanos();
    List<InetAddress> addresses = synthetic ? List.of()
        : BoundedHostResolver.defaultResolver().resolvePublic("api.github.com", timeout);
    if (deadline - System.nanoTime() <= 0) throw reject(TRANSPORT_UNAVAILABLE);
    return new Session(clientForPinnedAddresses(addresses), deadline);
  }
  /** Internal policy assembly; production supplies only resolvePublic's approved snapshot. */
  OkHttpClient clientForPinnedAddresses(List<InetAddress> addresses) {
    List<InetAddress> pinned = List.copyOf(addresses);
    var builder = new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(timeout).readTimeout(timeout).writeTimeout(timeout).callTimeout(timeout);
    if (!synthetic) builder.dns(host -> {
      if (!"api.github.com".equals(host)) throw new java.net.UnknownHostException("DESTINATION_REJECTED");
      return pinned;
    });
    builder.addNetworkInterceptor(chain -> {
      var once = chain.request().tag(AtomicBoolean.class);
      if (once == null || !once.compareAndSet(false, true)) throw new IOException("REPEATED_EXCHANGE_REJECTED");
      return chain.proceed(chain.request());
    });
    return builder.build();
  }
  private final class Session implements CredentialExchange {
    private final OkHttpClient client;
    private final long deadline;
    private boolean closed;
    Session(OkHttpClient client, long deadline) { this.client = client; this.deadline = deadline; }
    @Override public Reply installation(String jwt) throws Exception { return request(jwt, null); }
    @Override public Reply token(String jwt, JsonNode restrictions) throws Exception { return request(jwt, restrictions); }
    private Reply request(String jwt, JsonNode payload) throws Exception {
      if (closed || deadline - System.nanoTime() <= 0) throw reject(TRANSPORT_UNAVAILABLE);
      var url = HttpUrl.get(origin).newBuilder().addPathSegment("app").addPathSegment("installations")
          .addPathSegment(Long.toString(installation));
      if (payload != null) url.addPathSegment("access_tokens");
      var builder = new Request.Builder().url(url.build()).tag(AtomicBoolean.class, new AtomicBoolean())
          .header("Authorization", "Bearer " + jwt).header("Accept", "application/vnd.github+json")
          .header("X-GitHub-Api-Version", "2022-11-28").header("User-Agent", "TracePermit-Credential-Foundation");
      if (payload == null) builder.get();
      else builder.post(RequestBody.create(payload.toString(), MediaType.get("application/json; charset=utf-8")));
      var call = client.newCall(builder.build()); call.timeout().deadlineNanoTime(deadline);
      try (Response response = call.execute()) {
        byte[] bytes = response.body() == null ? new byte[0] : response.body().byteStream().readNBytes(limit + 1);
        if (bytes.length > limit) { Arrays.fill(bytes, (byte) 0); throw reject(RESPONSE_TOO_LARGE); }
        return new Reply(response.code(), !response.headers("Link").isEmpty(), bytes);
      }
    }
    @Override public void close() {
      closed = true;
      client.dispatcher().cancelAll(); client.connectionPool().evictAll();
      var executor = client.dispatcher().executorService(); executor.shutdownNow();
      try {
        if (!executor.awaitTermination(1, TimeUnit.SECONDS)) throw reject(CLEANUP_FAILED);
      } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw reject(CLEANUP_FAILED); }
    }
    @Override public String toString() { return "CredentialSession[redacted]"; }
  }
  @Override public String toString() { return "GitHubCredentialTransport[redacted]"; }
}
