package io.tracepermit.adapters.remote.github.credentials;

import static io.tracepermit.adapters.remote.github.credentials.GitHubInstallationCredentialProviderTest.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.CertificateException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import okhttp3.OkHttpClient;
import okhttp3.tls.HeldCertificate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Independent review controls. All exchange replies and DNS answers are synthetic. */
final class CredentialReviewRegressionTest {
  private static KeyPair runtimeKeys;
  @BeforeAll static void keys() throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048); runtimeKeys = generator.generateKeyPair();
  }

  static Stream<Arguments> assuranceMutations() {
    return Stream.of("installation", "token").flatMap(stage -> {
      var paths = stage.equals("installation")
          ? List.of("id", "app_id", "target_id", "target_type", "account", "account.id", "account.login",
              "account.type", "permissions", "permissions.contents", "permissions.metadata", "permissions.pull_requests", "repository_selection")
          : List.of("repositories", "repositories.0", "repositories.0.id", "repositories.0.name", "repositories.0.full_name",
              "repositories.0.owner", "repositories.0.owner.id", "repositories.0.owner.login", "repositories.0.private",
              "repositories.0.fork", "repositories.0.archived", "repositories.0.disabled", "permissions",
              "permissions.contents", "permissions.metadata", "permissions.pull_requests", "repository_selection");
      return paths.stream().flatMap(path -> Stream.of("missing", "null", "wrong_type")
          .map(kind -> Arguments.of(stage, path, kind)));
    });
  }

  @ParameterizedTest @MethodSource("assuranceMutations")
  void eachRequiredAssuranceMustBePresentAndTyped(String stage, String path, String kind) {
    var value = stage.equals("installation") ? installation() : token();
    String[] parts = path.split("\\."); JsonNode parent = value;
    for (int i = 0; i < parts.length - 1; i++) parent = parent.isArray() ? parent.get(Integer.parseInt(parts[i])) : parent.get(parts[i]);
    String leaf = parts[parts.length - 1];
    if (parent.isArray()) {
      var array = (com.fasterxml.jackson.databind.node.ArrayNode) parent;
      if (kind.equals("missing")) array.remove(Integer.parseInt(leaf));
      else array.set(Integer.parseInt(leaf), kind.equals("null") ? JSON.nullNode() : JSON.getNodeFactory().textNode("invalid"));
    } else {
      var object = (ObjectNode) parent; JsonNode previous = object.get(leaf);
      if (kind.equals("missing")) object.remove(leaf);
      else if (kind.equals("null")) object.putNull(leaf);
      else if (previous.isTextual()) object.put(leaf, true);
      else object.put(leaf, "invalid");
    }
    var calls = new ArrayList<String>();
    var provider = provider(Clock.fixed(NOW, ZoneOffset.UTC), () -> exchange(calls,
        stage.equals("installation") ? value : installation(), stage.equals("token") ? value : token(), () -> {}));
    reject(provider, stage.equals("installation") ? "INSTALLATION_REJECTED" : "TOKEN_ASSURANCE_REJECTED");
    assertThat(calls).containsExactlyElementsOf(stage.equals("installation") ? List.of("GET", "CLOSE") : List.of("GET", "POST", "CLOSE"));
  }

  @ParameterizedTest @ValueSource(strings = {"wrong_installation", "wrong_app", "wrong_owner", "decimal_id", "overflow_id"})
  void installationIdentityCannotBorrowAValidPermissionProfile(String kind) {
    var value = installation();
    switch (kind) {
      case "wrong_installation" -> value.put("id", 72);
      case "wrong_app" -> value.put("app_id", 32);
      case "wrong_owner" -> ((ObjectNode) value.get("account")).put("id", 202);
      case "decimal_id" -> value.put("app_id", 31.0);
      case "overflow_id" -> value.put("id", new java.math.BigInteger("18446744073709551687"));
      default -> throw new AssertionError();
    }
    var calls = new ArrayList<String>();
    reject(provider(Clock.fixed(NOW, ZoneOffset.UTC), () -> exchange(calls, value, token(), () -> {})), "INSTALLATION_REJECTED");
    assertThat(calls).containsExactly("GET", "CLOSE");
  }

  @ParameterizedTest @ValueSource(strings = {"status", "pagination", "json", "success"})
  void responseByteOwnershipIncludesRejectedStatusAndPagination(String kind) {
    byte[] bytes = (kind.equals("json") ? "{synthetic-private-canary" : token().toString()).getBytes(StandardCharsets.UTF_8);
    var closed = new AtomicInteger();
    var provider = provider(Clock.fixed(NOW, ZoneOffset.UTC), () -> new CredentialExchange() {
      @Override public Reply installation(String jwt) { return reply(200, GitHubInstallationCredentialProviderTest.installation()); }
      @Override public Reply token(String jwt, JsonNode restrictions) { return new Reply(kind.equals("status") ? 403 : 201, kind.equals("pagination"), bytes); }
      @Override public void close() { closed.incrementAndGet(); }
    });
    if (kind.equals("success")) assertThat(provider.getInstallationCredentials(REPOSITORY).token().equals(TOKEN)).isTrue();
    else reject(provider, "RESPONSE_INVALID");
    assertThat(bytes).containsOnly((byte) 0); assertThat(closed).hasValue(1);
  }

  @ParameterizedTest @ValueSource(strings = {"open_backwards", "open_jwt_expired", "token_expired", "cleanup_backwards", "cleanup_valid"})
  void timeIsRecheckedAfterWaitsAndCleanup(String kind) {
    var instant = new AtomicReference<>(NOW); var calls = new ArrayList<String>();
    Clock clock = new Clock() {
      @Override public ZoneId getZone() { return ZoneOffset.UTC; }
      @Override public Clock withZone(ZoneId zone) { return this; }
      @Override public Instant instant() { return instant.get(); }
    };
    var provider = provider(clock, () -> {
      if (kind.equals("open_backwards")) instant.set(NOW.minusSeconds(1));
      if (kind.equals("open_jwt_expired")) instant.set(NOW.plusSeconds(540));
      return new CredentialExchange() {
        @Override public Reply installation(String jwt) { calls.add("GET"); return reply(200, GitHubInstallationCredentialProviderTest.installation()); }
        @Override public Reply token(String jwt, JsonNode restrictions) {
          calls.add("POST");
          if (kind.equals("token_expired")) instant.set(NOW.plusSeconds(3600));
          return reply(201, GitHubInstallationCredentialProviderTest.token());
        }
        @Override public void close() {
          calls.add("CLOSE");
          if (kind.equals("cleanup_backwards")) instant.set(NOW.minusSeconds(1));
          if (kind.equals("cleanup_valid")) instant.set(NOW.plusSeconds(3599));
        }
      };
    });
    if (kind.equals("cleanup_valid")) assertThat(provider.getInstallationCredentials(REPOSITORY).expiresAt()).isEqualTo(NOW.plusSeconds(3600));
    else reject(provider, kind.equals("token_expired") ? "TOKEN_EXPIRY_REJECTED" : "CLOCK_REJECTED");
    assertThat(calls).containsExactlyElementsOf(kind.startsWith("open") ? List.of("CLOSE") : List.of("GET", "POST", "CLOSE"));
  }

  @Test void productionClientPinsOnlyTheApprovedSnapshotAndRetainsPlatformTls() throws Exception {
    // No DNS lookup or production call: assemble the same production policy with a literal public answer.
    var addresses = new ArrayList<>(List.of(InetAddress.getByAddress(new byte[] {93, (byte) 184, (byte) 216, 34})));
    OkHttpClient client = GitHubCredentialTransport.production(71).clientForPinnedAddresses(addresses);
    try {
      var expected = List.copyOf(addresses); addresses.clear();
      assertThat(client.dns().lookup("api.github.com")).isEqualTo(expected);
      assertThat(catchThrowable(() -> client.dns().lookup("other.invalid"))).isInstanceOf(UnknownHostException.class);
      assertThat(client.proxy()).isEqualTo(Proxy.NO_PROXY);
      assertThat(client.followRedirects() || client.followSslRedirects() || client.retryOnConnectionFailure()).isFalse();
      assertThat(client.callTimeoutMillis()).isEqualTo(10_000);
      assertThat(client.connectTimeoutMillis()).isEqualTo(10_000);
      assertThat(client.readTimeoutMillis()).isEqualTo(10_000);
      assertThat(client.writeTimeoutMillis()).isEqualTo(10_000);
      assertThat(client.hostnameVerifier()).isSameAs(new OkHttpClient().hostnameVerifier());
      var certificate = new HeldCertificate.Builder().commonName("synthetic-untrusted").addSubjectAlternativeName("api.github.com").build();
      assertThat(catchThrowable(() -> client.x509TrustManager().checkServerTrusted(
          new java.security.cert.X509Certificate[] {certificate.certificate()}, "RSA"))).isInstanceOf(CertificateException.class);
    } finally { client.connectionPool().evictAll(); client.dispatcher().executorService().shutdownNow(); }
  }

  private static GitHubInstallationCredentialProvider provider(Clock clock, CredentialExchange.Factory exchanges) {
    return new GitHubInstallationCredentialProvider(CONFIG, () -> runtimeKeys.getPrivate(), exchanges, clock);
  }
  private static CredentialExchange exchange(List<String> calls, JsonNode installation, JsonNode token, Runnable cleanup) {
    return new CredentialExchange() {
      @Override public Reply installation(String jwt) { calls.add("GET"); return reply(200, installation); }
      @Override public Reply token(String jwt, JsonNode restrictions) { calls.add("POST"); return reply(201, token); }
      @Override public void close() { calls.add("CLOSE"); cleanup.run(); }
    };
  }
  private static CredentialExchange.Reply reply(int status, JsonNode node) {
    return new CredentialExchange.Reply(status, false, node.toString().getBytes(StandardCharsets.UTF_8));
  }
  private static void reject(GitHubInstallationCredentialProvider provider, String code) {
    Throwable failure = catchThrowable(() -> provider.getInstallationCredentials(REPOSITORY));
    assertThat(failure).isInstanceOf(GitHubCredentialException.class);
    assertThat(((GitHubCredentialException) failure).errorCode()).isEqualTo(code);
    assertThat(failure.getCause()).isNull(); assertThat(failure.getSuppressed()).isEmpty();
    assertThat(failure.toString().contains(TOKEN)).isFalse();
  }
}
