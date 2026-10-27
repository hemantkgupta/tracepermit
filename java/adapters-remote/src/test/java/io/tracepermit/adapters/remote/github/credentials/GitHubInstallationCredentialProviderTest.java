package io.tracepermit.adapters.remote.github.credentials;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.adapters.remote.github.GitHubAppCredentials;
import io.tracepermit.adapters.remote.github.GitHubDraftPullRequestAction;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Real local HTTP exchange, runtime keys, no provider or target access. */
final class GitHubInstallationCredentialProviderTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final String REPOSITORY = GitHubDraftPullRequestAction.ALLOWED_REPOSITORY;
  static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
  static final GitHubInstallationConfiguration CONFIG = new GitHubInstallationConfiguration(
      31, 71, 101, 201, REPOSITORY, GitHubInstallationConfiguration.requiredPermissions());
  static final String TOKEN = "synthetic-runtime-" + UUID.randomUUID();
  static KeyPair keys;
  private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
  private final Clock clock = new Clock() {
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now.get(); }
  };
  private MockWebServer server;
  private URI origin;
  private GitHubInstallationCredentialProvider provider;
  private final List<String> methods = new CopyOnWriteArrayList<>();
  private final AtomicBoolean unrelated = new AtomicBoolean();
  private volatile Function<RecordedRequest, MockResponse> override = request -> null;

  @BeforeAll static void generateKeys() throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); keys = generator.generateKeyPair();
  }
  @BeforeEach void start() throws Exception {
    server = new MockWebServer();
    server.setDispatcher(new Dispatcher() {
      @Override public MockResponse dispatch(RecordedRequest request) {
        methods.add(request.getMethod());
        String expected = "GET".equals(request.getMethod()) ? "/app/installations/71" : "/app/installations/71/access_tokens";
        if (!expected.equals(request.getPath()) || !("GET".equals(request.getMethod()) || "POST".equals(request.getMethod()))) unrelated.set(true);
        MockResponse response = override.apply(request);
        if (response != null) return response;
        return "GET".equals(request.getMethod()) ? reply(200, installation()) : reply(201, token());
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    origin = URI.create("http://127.0.0.1:" + server.getPort()); configure(Duration.ofSeconds(3), 65_536);
  }
  private void configure(Duration timeout, int limit) {
    provider = new GitHubInstallationCredentialProvider(CONFIG, () -> keys.getPrivate(),
        GitHubCredentialTransport.synthetic(origin, CONFIG.installationId(), timeout, limit), clock);
  }
  @AfterEach void stop() throws Exception {
    if (server != null) server.shutdown();
    assertThat(unrelated).as("no PR creation or unrelated exchange").isFalse();
  }

  @Test void exactRestrictedExchangeSignsBoundedJwtAndReturnsOnlyAfterCleanup() throws Exception {
    GitHubAppCredentials credential = provider.getInstallationCredentials(REPOSITORY);
    assertThat(credential.token().equals(TOKEN)).isTrue();
    assertThat(credential.installationId()).isEqualTo(CONFIG.installationId());
    assertThat(credential.expiresAt()).isEqualTo(NOW.plusSeconds(3600));
    assertThat(credential.credentialFingerprint()).matches("sha256:[a-f0-9]{64}");
    assertThat(credential.toString().contains(TOKEN)).isFalse();
    assertThat(provider.toString().contains(TOKEN)).isFalse();
    assertThat(methods).containsExactly("GET", "POST");
    var get = server.takeRequest(1, TimeUnit.SECONDS); var post = server.takeRequest(1, TimeUnit.SECONDS);
    assertThat(get != null && post != null).isTrue();
    assertThat(get.getPath().equals("/app/installations/71")).isTrue();
    assertThat(post.getPath().equals("/app/installations/71/access_tokens")).isTrue();
    String authorization = get.getHeader("Authorization");
    assertThat(authorization != null && authorization.startsWith("Bearer ")).isTrue();
    assertThat(authorization.equals(post.getHeader("Authorization"))).isTrue();
    String jwt = authorization.substring(7); String[] pieces = jwt.split("\\."); assertThat(pieces.length).isEqualTo(3);
    var decoder = Base64.getUrlDecoder();
    JsonNode header = JSON.readTree(decoder.decode(pieces[0])); JsonNode claims = JSON.readTree(decoder.decode(pieces[1]));
    assertThat(header.size()).isEqualTo(2); assertThat(header.path("alg").textValue()).isEqualTo("RS256");
    assertThat(header.path("typ").textValue()).isEqualTo("JWT"); assertThat(claims.size()).isEqualTo(3);
    assertThat(claims.path("iss").textValue()).isEqualTo("31");
    assertThat(claims.path("iat").longValue()).isEqualTo(NOW.getEpochSecond() - 60);
    assertThat(claims.path("exp").longValue()).isEqualTo(NOW.getEpochSecond() + 540);
    var signature = Signature.getInstance("SHA256withRSA"); signature.initVerify(keys.getPublic());
    signature.update((pieces[0] + "." + pieces[1]).getBytes(StandardCharsets.US_ASCII));
    assertThat(signature.verify(decoder.decode(pieces[2]))).isTrue();
    JsonNode restriction = JSON.readTree(post.getBody().readByteArray());
    assertThat(restriction.size()).isEqualTo(2);
    assertThat(restriction.path("repository_ids").isArray() && restriction.path("repository_ids").size() == 1).isTrue();
    assertThat(restriction.path("repository_ids").get(0).isIntegralNumber()).isTrue();
    assertThat(restriction.path("repository_ids").get(0).longValue()).isEqualTo(101);
    assertThat(restriction.path("permissions").equals(permissions())).isTrue();
    // No cache: another explicit invocation exchanges again, with no background work.
    provider.getInstallationCredentials(REPOSITORY);
    assertThat(methods).containsExactly("GET", "POST", "GET", "POST");
  }

  @ParameterizedTest @ValueSource(strings = {"app", "installation", "owner", "owner_login", "target", "type",
      "permissions", "extra_permission", "missing_permission", "permission_type", "suspended", "missing_suspended", "selection"})
  void installationMismatchCannotMintAToken(String change) {
    var value = installation();
    switch (change) {
      case "app" -> value.put("app_id", 32);
      case "installation" -> value.put("id", "71");
      case "owner" -> ((ObjectNode) value.get("account")).put("id", 202);
      case "owner_login" -> ((ObjectNode) value.get("account")).put("login", "other");
      case "target" -> value.put("target_id", 202);
      case "type" -> value.put("target_type", "Enterprise");
      case "permissions" -> ((ObjectNode) value.get("permissions")).put("contents", "write");
      case "extra_permission" -> ((ObjectNode) value.get("permissions")).put("administration", "write");
      case "missing_permission" -> ((ObjectNode) value.get("permissions")).remove("contents");
      case "permission_type" -> ((ObjectNode) value.get("permissions")).put("metadata", true);
      case "suspended" -> value.put("suspended_at", NOW.toString());
      case "missing_suspended" -> value.remove("suspended_at");
      case "selection" -> value.put("repository_selection", "unknown");
      default -> throw new AssertionError();
    }
    override = request -> reply(200, value);
    error("INSTALLATION_REJECTED"); assertThat(methods).containsExactly("GET");
  }

  @ParameterizedTest @ValueSource(strings = {"repo_id", "repo_id_type", "owner", "owner_login", "name", "full_name",
      "private", "archived", "disabled", "fork", "multiple", "missing_repositories", "repositories_null", "repositories_object",
      "extra_permission", "missing_permission", "permission_type", "permissions_null", "selection", "missing_selection",
      "missing_token", "token_type", "token_empty", "token_control", "token_large", "missing_expiry", "expiry_type",
      "expiry_invalid", "expiry_now", "expiry_past", "expiry_long"})
  void tokenMustConfirmExactRepositoryPermissionsAndBoundedExpiry(String change) {
    var value = token(); var repo = (ObjectNode) value.withArray("repositories").get(0);
    switch (change) {
      case "repo_id" -> repo.put("id", 102);
      case "repo_id_type" -> repo.put("id", "101");
      case "owner" -> ((ObjectNode) repo.get("owner")).put("id", 202);
      case "owner_login" -> ((ObjectNode) repo.get("owner")).put("login", "other");
      case "name" -> repo.put("name", "other");
      case "full_name" -> repo.put("full_name", "other/repository");
      case "private" -> repo.put("private", false);
      case "archived", "disabled", "fork" -> repo.put(change, true);
      case "multiple" -> value.withArray("repositories").add(repo.deepCopy());
      case "missing_repositories" -> value.remove("repositories");
      case "repositories_null" -> value.putNull("repositories");
      case "repositories_object" -> value.putObject("repositories");
      case "extra_permission" -> ((ObjectNode) value.get("permissions")).put("administration", "write");
      case "missing_permission" -> ((ObjectNode) value.get("permissions")).remove("metadata");
      case "permission_type" -> ((ObjectNode) value.get("permissions")).put("contents", 1);
      case "permissions_null" -> value.putNull("permissions");
      case "selection" -> value.put("repository_selection", "all");
      case "missing_selection" -> value.remove("repository_selection");
      case "missing_token" -> value.remove("token");
      case "token_type" -> value.put("token", true);
      case "token_empty" -> value.put("token", "");
      case "token_control" -> value.put("token", "synthetic\ncanary");
      case "token_large" -> value.put("token", "s".repeat(8193));
      case "missing_expiry" -> value.remove("expires_at");
      case "expiry_type" -> value.put("expires_at", 1);
      case "expiry_invalid" -> value.put("expires_at", "not-a-time");
      case "expiry_now" -> value.put("expires_at", NOW.toString());
      case "expiry_past" -> value.put("expires_at", NOW.minusSeconds(1).toString());
      case "expiry_long" -> value.put("expires_at", NOW.plusSeconds(3601).toString());
      default -> throw new AssertionError();
    }
    override = request -> "POST".equals(request.getMethod()) ? reply(201, value) : null;
    error(change.startsWith("expiry_") && !change.equals("expiry_type") ? "TOKEN_EXPIRY_REJECTED" : "TOKEN_ASSURANCE_REJECTED");
    assertThat(methods).containsExactly("GET", "POST");
  }

  static Stream<Arguments> invalidJson() {
    return Stream.of("metadata", "token").flatMap(stage -> Stream.of("empty", "malformed", "duplicate", "trailing",
        "null", "array", "number", "boolean", "string", "oversize", "pagination").map(kind -> Arguments.of(stage, kind)));
  }
  @ParameterizedTest @MethodSource("invalidJson")
  void malformedAmbiguousOrOversizedResponsesNeverReturnCredentials(String stage, String kind) {
    override = request -> {
      if (!(stage.equals("metadata") == "GET".equals(request.getMethod()))) return null;
      String valid = stage.equals("metadata") ? installation().toString() : token().toString();
      String body = switch (kind) {
        case "empty" -> "";
        case "malformed" -> "{";
        case "duplicate" -> "{\"id\":1,\"id\":2}";
        case "trailing" -> valid + " {}";
        case "null" -> "null";
        case "array" -> "[]";
        case "number" -> "1";
        case "boolean" -> "true";
        case "string" -> "\"synthetic\"";
        default -> valid;
      };
      var response = new MockResponse().setResponseCode(stage.equals("metadata") ? 200 : 201).setBody(body);
      if (kind.equals("oversize")) response.setChunkedBody("x".repeat(65_537), 127);
      if (kind.equals("pagination")) response.setHeader("Link", "synthetic-incomplete");
      return response;
    };
    error(kind.equals("oversize") ? "RESPONSE_TOO_LARGE" : "RESPONSE_INVALID");
    assertThat(methods.size()).isEqualTo(stage.equals("metadata") ? 1 : 2);
  }

  static Stream<Arguments> statuses() {
    return Stream.of("metadata", "token").flatMap(stage -> Stream.of(301, 307, 401, 403, 404, 408, 429, 500, 503)
        .map(status -> Arguments.of(stage, status)));
  }
  @ParameterizedTest @MethodSource("statuses")
  void unexpectedStatusesAndRedirectsCannotCauseAnotherExchange(String stage, int status) {
    override = request -> stage.equals("metadata") == "GET".equals(request.getMethod())
        ? new MockResponse().setResponseCode(status).setBody("synthetic-private-canary")
            .setHeader("Retry-After", "0").setHeader("Location", origin + "/unrelated") : null;
    GitHubCredentialException failure = failure();
    assertThat(List.of("RESPONSE_INVALID", "TRANSPORT_UNAVAILABLE").contains(failure.errorCode())).isTrue();
    assertThat(methods.size()).isEqualTo(stage.equals("metadata") ? 1 : 2);
  }

  @ParameterizedTest @ValueSource(strings = {"backwards", "expired_jwt"})
  void clockChangeBeforeTokenPostFailsClosed(String kind) {
    override = request -> {
      if ("GET".equals(request.getMethod())) now.set(kind.equals("backwards") ? NOW.minusSeconds(1) : NOW.plusSeconds(540));
      return null;
    };
    error("CLOCK_REJECTED"); assertThat(methods).containsExactly("GET");
  }

  @ParameterizedTest @ValueSource(strings = {"disconnect", "stalled_body"})
  void transportFailureIsBoundedAndNeverRetried(String kind) {
    configure(Duration.ofMillis(250), 65_536);
    override = request -> kind.equals("disconnect") ? new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
        : new MockResponse().setBody(installation().toString()).setBodyDelay(1, TimeUnit.SECONDS);
    error("TRANSPORT_UNAVAILABLE"); assertThat(methods).containsExactly("GET");
  }

  @Test void concurrentInvocationsHaveIndependentRestrictedExchangesAndResults() throws Exception {
    var minted = new AtomicInteger();
    override = request -> "POST".equals(request.getMethod()) ? reply(201, token().put("token", "synthetic_" + minted.incrementAndGet())) : null;
    var workers = Executors.newFixedThreadPool(4);
    try {
      var futures = new ArrayList<java.util.concurrent.Future<GitHubAppCredentials>>();
      for (int i = 0; i < 8; i++) futures.add(workers.submit(() -> provider.getInstallationCredentials(REPOSITORY)));
      var fingerprints = new java.util.HashSet<String>();
      for (var future : futures) fingerprints.add(future.get(5, TimeUnit.SECONDS).credentialFingerprint());
      assertThat(fingerprints.size()).isEqualTo(8); assertThat(minted).hasValue(8);
      assertThat(methods.stream().filter("GET"::equals).count()).isEqualTo(8);
      assertThat(methods.stream().filter("POST"::equals).count()).isEqualTo(8);
    } finally { workers.shutdownNow(); assertThat(workers.awaitTermination(2, TimeUnit.SECONDS)).isTrue(); }
  }

  @Test void callerRepositoryCannotChooseCredentialScope() {
    var reads = new AtomicInteger();
    provider = new GitHubInstallationCredentialProvider(CONFIG, () -> { reads.incrementAndGet(); return keys.getPrivate(); },
        GitHubCredentialTransport.synthetic(origin, 71, Duration.ofSeconds(3), 65_536), clock);
    var problem = catchThrowable(() -> provider.getInstallationCredentials("other/repository"));
    assertThat(problem instanceof GitHubCredentialException).isTrue();
    assertThat(((GitHubCredentialException) problem).errorCode()).isEqualTo("REPOSITORY_REJECTED");
    assertThat(reads).hasValue(0); assertThat(methods).isEmpty();
  }

  @Test void lastPositiveExpirySecondIsNotConfusedWithExpiredOrMissing() {
    override = request -> "POST".equals(request.getMethod()) ? reply(201, token().put("expires_at", NOW.plusSeconds(1).toString())) : null;
    assertThat(provider.getInstallationCredentials(REPOSITORY).expiresAt()).isEqualTo(NOW.plusSeconds(1));
    assertThat(methods).containsExactly("GET", "POST");
  }

  @Test void backwardsClockCannotHideBehindTheInitialTimestamp() {
    provider = new GitHubInstallationCredentialProvider(CONFIG, () -> { now.set(NOW.plusSeconds(2)); return keys.getPrivate(); },
        GitHubCredentialTransport.synthetic(origin, 71, Duration.ofSeconds(3), 65_536), clock);
    override = request -> { if ("GET".equals(request.getMethod())) now.set(NOW.plusSeconds(1)); return null; };
    error("CLOCK_REJECTED"); assertThat(methods).containsExactly("GET");
  }

  private GitHubCredentialException failure() {
    Throwable problem = catchThrowable(() -> provider.getInstallationCredentials(REPOSITORY));
    assertThat(problem instanceof GitHubCredentialException).isTrue();
    var fixed = (GitHubCredentialException) problem;
    assertThat(fixed.getCause() == null && fixed.getSuppressed().length == 0).isTrue();
    assertThat(fixed.toString().contains(TOKEN)).isFalse();
    return fixed;
  }
  private void error(String code) { assertThat(failure().errorCode()).isEqualTo(code); }
  static MockResponse reply(int status, JsonNode body) { return new MockResponse().setResponseCode(status).setBody(body.toString()); }
  static ObjectNode permissions() { var value = JSON.createObjectNode(); CONFIG.permissions().forEach(value::put); return value; }
  static ObjectNode installation() {
    var value = JSON.createObjectNode().put("id", 71).put("app_id", 31).put("target_id", 201).put("target_type", "Organization")
        .put("repository_selection", "selected").putNull("suspended_at");
    value.putObject("account").put("id", 201).put("login", REPOSITORY.split("/")[0]).put("type", "Organization");
    value.set("permissions", permissions()); return value;
  }
  static ObjectNode token() {
    var value = JSON.createObjectNode().put("token", TOKEN).put("expires_at", NOW.plusSeconds(3600).toString())
        .put("repository_selection", "selected");
    value.set("permissions", permissions());
    value.putArray("repositories").addObject().put("id", 101).put("full_name", REPOSITORY).put("name", REPOSITORY.split("/")[1])
        .put("private", true).put("archived", false).put("disabled", false).put("fork", false)
        .putObject("owner").put("id", 201).put("login", REPOSITORY.split("/")[0]);
    return value;
  }
}
