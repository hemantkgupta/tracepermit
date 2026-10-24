package io.tracepermit.adapters.remote.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.InMemoryExecutionLedger;
import io.tracepermit.execution.ReconciliationCandidate;
import io.tracepermit.execution.TenantPartition;
import io.tracepermit.security.ExecutionPermitAuthority;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real adapter/HTTP with synthetic target observations; no live target or credential. */
final class GitHubDraftPullRequestAdapterTest {
  private static final Instant NOW = Instant.parse("2026-10-24T00:00:00Z");
  private static final String REPO = GitHubDraftPullRequestAction.ALLOWED_REPOSITORY;
  private static final String HEAD = "feature/synthetic";
  private static final String TOKEN = "synthetic-only-" + UUID.randomUUID();
  private static final String FP = "sha256:" + "a".repeat(64);
  private static final GitHubPilotTargetBinding BINDING = new GitHubPilotTargetBinding(
      71, 101, 201, REPO, HEAD, "main", "a".repeat(40), "b".repeat(40));
  private static final String REPO_PATH = "/repos/" + REPO;
  private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
  private final Clock clock = new Clock() {
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now.get(); }
  };
  private MockWebServer server;
  private URI origin;
  private InMemoryExecutionLedger ledger;
  private GitHubAppCredentialProvider provider;
  private GitHubPullRequestOutcomeResolver resolver;
  private ExecutionPermitAuthority permits;
  private GitHubDraftPullRequestAdapter adapter;
  private GitHubApiTransport transport;
  private volatile Function<RecordedRequest, MockResponse> override = request -> null;
  private final List<String> requests = new CopyOnWriteArrayList<>();

  @BeforeEach void start() throws Exception {
    server = new MockWebServer();
    server.setDispatcher(new Dispatcher() {
      @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
        requests.add(request.getMethod() + " " + request.getPath());
        MockResponse changed = override.apply(request);
        if (changed != null) return changed;
        String path = request.getRequestUrl().encodedPath();
        if (path.equals("/installation/repositories")) return response(200, installation().toString());
        if (path.equals(REPO_PATH)) return response(200, repository().toString());
        if (path.equals(REPO_PATH + "/git/ref/heads/" + HEAD)) return response(200, ref(HEAD, BINDING.headSha()).toString());
        if (path.equals(REPO_PATH + "/git/ref/heads/main")) return response(200, ref("main", BINDING.baseSha()).toString());
        if (path.equals(REPO_PATH + "/pulls")) {
          if ("POST".equals(request.getMethod())) return response(201, confirmation().toString());
          return response(200, "[" + confirmation() + "]");
        }
        return response(404, "{}");
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    origin = URI.create("http://127.0.0.1:" + server.getPort());
    ledger = new InMemoryExecutionLedger(() -> "attempt-" + UUID.randomUUID());
    provider = repo -> new GitHubAppCredentials(71, FP, TOKEN, NOW.plusSeconds(120));
    byte[] key = new byte[32]; new java.security.SecureRandom().nextBytes(key);
    permits = new ExecutionPermitAuthority(ExecutionPermitAuthority.DEFAULT_ISSUER, "synthetic", key,
        60, clock, () -> "permit-" + UUID.randomUUID(), new io.tracepermit.security.InMemoryPermitConsumptionStore());
    configure(65_536, Duration.ofSeconds(3));
  }
  private void configure(int limit, Duration timeout) {
    transport = GitHubApiTransport.synthetic(origin, Duration.ofSeconds(1), timeout, limit);
    resolver = GitHubPullRequestOutcomeResolver.synthetic(provider, BINDING, transport, clock);
    adapter = GitHubDraftPullRequestAdapter.synthetic(provider, ledger, resolver, permits, BINDING, transport, clock);
  }
  @AfterEach void stop() throws Exception { if (server != null) server.shutdown(); }

  @Test void boundSuccessHasFourGetsThenOnePostAndReplayDoesNotDispatch() throws Exception {
    ObjectNode action = action();
    var result = adapter.execute(action, permit(action));
    assertThat(result.number()).isEqualTo(42); assertThat(result.id()).isEqualTo(42001);
    assertThat(result.toJsonNode().toString().contains(TOKEN)).isFalse();
    assertThat(result.repository()).isEqualTo(REPO);
    assertThat(result.installationId()).isEqualTo(71);
    assertThat(requests).hasSize(5);
    assertThat(requests.subList(0, 4)).allMatch(s -> s.startsWith("GET "));
    assertThat(requests.get(4)).isEqualTo("POST " + REPO_PATH + "/pulls");
    for (int i = 0; i < 5; i++) {
      var sent = server.takeRequest(1, TimeUnit.SECONDS);
      assertThat(sent).isNotNull();
      assertThat(("Bearer " + TOKEN).equals(sent.getHeader("Authorization"))).isTrue();
      if (i == 4) {
        var payload = JsonSupport.MAPPER.readTree(sent.getBody().readUtf8());
        assertThat(payload.path("draft").booleanValue()).isTrue();
        assertThat(payload.path("head").asText()).isEqualTo(HEAD);
        assertThat(payload.path("base").asText()).isEqualTo("main");
      }
    }
    assertThat(adapter.execute(action, permit(action)).number()).isEqualTo(42);
    assertThat(requests).hasSize(5);
    args(action).put("body", "changed");
    assertThatThrownBy(() -> adapter.execute(action, permit(action)))
        .hasFieldOrPropertyWithValue("errorCode", "OPERATION_BINDING_CONFLICT");
    assertThat(requests).hasSize(5);
  }

  @ParameterizedTest @ValueSource(strings = {"repo_id", "repo_string_id", "owner_id", "owner_login", "renamed",
      "name", "archived", "disabled", "public", "fork", "missing_flag", "missing_owner", "installation_id",
      "installation_count", "installation_duplicate", "installation_pagination", "installation_absent", "installation_denied",
      "head_sha", "base_sha", "head_ref", "base_ref", "ref_type", "ref_missing", "repo_missing", "malformed"})
  void preflightMismatchHasZeroPostAndFixedDiagnostics(String change) {
    override = request -> {
      String path = request.getRequestUrl().encodedPath();
      if (path.equals("/installation/repositories") && change.startsWith("installation_")) {
        var node = installation();
        switch (change) {
          case "installation_id" -> ((ObjectNode) node.path("repositories").get(0)).put("id", 999);
          case "installation_count" -> node.put("total_count", 2);
          case "installation_duplicate" -> node.withArray("repositories").add(repository());
          case "installation_pagination" -> { return response(200, node.toString()).setHeader("Link", "<https://other.invalid/next>; rel=next"); }
          case "installation_absent" -> { node.put("total_count", 0); node.putArray("repositories"); }
          case "installation_denied" -> { return response(403, TOKEN); }
          default -> throw new AssertionError();
        }
        return response(200, node.toString());
      }
      if (path.equals(REPO_PATH)) {
        var node = repository();
        switch (change) {
          case "repo_id" -> node.put("id", 999);
          case "repo_string_id" -> node.put("id", "101");
          case "owner_id" -> ((ObjectNode) node.path("owner")).put("id", 999);
          case "owner_login" -> ((ObjectNode) node.path("owner")).put("login", "transferred");
          case "renamed" -> node.put("full_name", "tracepermit/renamed");
          case "name" -> node.put("name", "renamed");
          case "archived" -> node.put("archived", true);
          case "disabled" -> node.put("disabled", true);
          case "public" -> node.put("private", false);
          case "fork" -> node.put("fork", true);
          case "missing_flag" -> node.remove("archived");
          case "missing_owner" -> node.remove("owner");
          case "repo_missing" -> { return response(404, TOKEN); }
          case "malformed" -> { return response(200, "not-json " + TOKEN); }
          default -> { return null; }
        }
        return response(200, node.toString());
      }
      if (path.contains("/git/ref/heads/")) {
        boolean head = path.endsWith(HEAD);
        var node = ref(head ? HEAD : "main", head ? BINDING.headSha() : BINDING.baseSha());
        if ((change.equals("head_sha") && head) || (change.equals("base_sha") && !head)) ((ObjectNode) node.path("object")).put("sha", "c".repeat(40));
        else if ((change.equals("head_ref") && head) || (change.equals("base_ref") && !head)) node.put("ref", "refs/tags/wrong");
        else if (change.equals("ref_type")) ((ObjectNode) node.path("object")).put("type", "tag");
        else if (change.equals("ref_missing")) return response(404, "{}");
        else return null;
        return response(200, node.toString());
      }
      return null;
    };
    ObjectNode action = action();
    assertThatThrownBy(() -> adapter.execute(action, permit(action)))
        .isInstanceOf(GitHubPullRequestException.class).hasNoCause()
        .satisfies(e -> assertThat(e.getMessage().contains(TOKEN)).isFalse());
    assertThat(posts()).isZero(); assertThat(status()).isEqualTo("failed");
    int count = requests.size();
    assertThatThrownBy(() -> adapter.execute(action, permit(action))).isInstanceOf(GitHubPullRequestException.class);
    assertThat(requests).hasSize(count);
    assertThat(resolver.lookup(candidate()).outcome()).isEqualTo("unknown");
    assertThat(resolver.getConfirmedResult(key())).isNull(); assertThat(posts()).isZero();
  }

  @Test void credentialInstallationMismatchStopsBeforeAnyRequest() {
    provider = repo -> new GitHubAppCredentials(72, FP, TOKEN, NOW.plusSeconds(120));
    configure(65_536, Duration.ofSeconds(3));
    ObjectNode action = action();
    assertThatThrownBy(() -> adapter.execute(action, permit(action)))
        .hasFieldOrPropertyWithValue("errorCode", "TARGET_CREDENTIAL_REJECTED");
    assertThat(requests).isEmpty();
    register(); assertThat(resolver.lookup(candidate()).outcome()).isEqualTo("unknown");
    assertThat(requests).isEmpty();
  }
  @ParameterizedTest @ValueSource(strings = {"expired", "newline", "too_large", "provider_error", "missing"})
  void unsafeCredentialsNeverReachTarget(String change) {
    provider = repo -> {
      if (change.equals("provider_error")) throw new IllegalStateException(TOKEN);
      if (change.equals("missing")) return null;
      return new GitHubAppCredentials(71, FP, change.equals("newline") ? "unsafe\r\n" :
          change.equals("too_large") ? "x".repeat(8193) : TOKEN,
          change.equals("expired") ? NOW : NOW.plusSeconds(120));
    };
    configure(65_536, Duration.ofSeconds(3)); ObjectNode action = action();
    assertThatThrownBy(() -> adapter.execute(action, permit(action))).hasNoCause();
    register(); assertThat(resolver.lookup(candidate()).outcome()).isEqualTo("unknown");
    assertThat(requests).isEmpty();
  }
  @Test void expiryDuringPreflightIsRecheckedBeforeWrite() {
    override = request -> {
      if (request.getPath().endsWith("/heads/main")) now.set(NOW.plusSeconds(121));
      return null;
    };
    ObjectNode action = action();
    assertThatThrownBy(() -> adapter.execute(action, permit(action))).hasNoCause();
    assertThat(posts()).isZero(); assertThat(status()).isEqualTo("failed");
  }

  @ParameterizedTest @ValueSource(strings = {"id_zero", "id_negative", "id_string", "id_decimal", "id_overflow", "missing_id",
      "number_zero", "number_string", "number_overflow", "draft_false", "missing_draft", "draft_string", "closed", "missing_state",
      "merged", "merged_at", "url", "head_ref", "base_ref", "head_sha", "base_sha", "head_repo", "base_repo", "head_owner",
      "missing_repo", "title", "body", "null", "empty", "invalid", "duplicate", "trailing", "pagination", "oversized"})
  void malformedOrContradictory201IsUnknownAndCannotBeReexecuted(String change) {
    override = request -> "POST".equals(request.getMethod()) ? changedConfirmation(change, 201) : null;
    ObjectNode action = action();
    assertThatThrownBy(() -> adapter.execute(action, permit(action))).hasNoCause();
    assertThat(status()).isEqualTo("unknown"); assertThat(posts()).isEqualTo(1);
    int count = requests.size();
    assertThatThrownBy(() -> adapter.execute(action, permit(action)))
        .hasFieldOrPropertyWithValue("errorCode", "EXECUTION_OUTCOME_UNKNOWN");
    assertThat(requests).hasSize(count);
  }

  @ParameterizedTest @ValueSource(strings = {"empty", "duplicate", "pagination", "malformed", "object", "null", "mismatch",
      "missing_id", "wrong_owner", "wrong_sha", "not_draft", "old_closed", "wrong_title", "wrong_body", "unauthorized", "denied", "missing",
      "server_error", "unexpected_status", "oversized", "redirect"})
  void incompleteOrUnavailableLookupAlwaysStaysUnknown(String change) {
    register();
    override = request -> {
      if (!request.getRequestUrl().encodedPath().equals(REPO_PATH + "/pulls")) return null;
      var pr = confirmation();
      switch (change) {
        case "empty" -> { return response(200, "[]"); }
        case "duplicate" -> { return response(200, "[" + pr + "," + pr + "]"); }
        case "pagination" -> { return response(200, "[" + pr + "]").setHeader("Link", "<https://other.invalid/next>; rel=next"); }
        case "malformed" -> { return response(200, "[" + TOKEN); }
        case "object" -> { return response(200, "{}"); }
        case "null" -> { return response(200, "null"); }
        case "missing_id" -> pr.remove("id");
        case "mismatch" -> ((ObjectNode) pr.path("head")).put("ref", "other");
        case "wrong_owner" -> ((ObjectNode) pr.path("head").path("repo").path("owner")).put("id", 999);
        case "wrong_sha" -> ((ObjectNode) pr.path("base")).put("sha", "c".repeat(40));
        case "not_draft" -> pr.put("draft", false);
        case "old_closed" -> pr.put("state", "closed");
        case "wrong_title" -> pr.put("title", "other");
        case "wrong_body" -> pr.put("body", "other");
        case "unauthorized" -> { return response(401, TOKEN); }
        case "denied" -> { return response(403, TOKEN); }
        case "missing" -> { return response(404, TOKEN); }
        case "server_error" -> { return response(500, TOKEN); }
        case "unexpected_status" -> { return response(201, "[" + pr + "]"); }
        case "oversized" -> { return response(200, "[" + "x".repeat(70_000)).setChunkedBody("x".repeat(70_000), 127); }
        case "redirect" -> { return response(302, "{}").setHeader("Location", "https://other.invalid/steal"); }
        default -> throw new AssertionError();
      }
      return response(200, "[" + pr + "]");
    };
    var observation = resolver.lookup(candidate());
    assertThat(observation.outcome()).isEqualTo("unknown"); assertThat(observation.confirmed()).isFalse();
    assertThat(resolver.getConfirmedResult(key())).isNull();
    assertThat(posts()).isZero(); assertThat(requests).hasSize(5);
    assertThat(requests).allMatch(s -> s.startsWith("GET "));
  }

  @Test void exactLookupConfirmsDesiredEffectWithoutChangingUnknownLedgerOrPosting() throws Exception {
    override = request -> "POST".equals(request.getMethod()) ? response(500, "uncertain") : null;
    ObjectNode action = action();
    assertThatThrownBy(() -> adapter.execute(action, permit(action))).hasNoCause();
    assertThat(status()).isEqualTo("unknown");
    override = request -> null;
    assertThat(resolver.lookup(candidate()).outcome()).isEqualTo("succeeded");
    assertThat(resolver.getConfirmedResult(key()).id()).isEqualTo(42001);
    assertThat(status()).isEqualTo("unknown"); assertThat(posts()).isEqualTo(1);
    assertThat(requests.getLast()).contains("head=tracepermit%3Afeature%2Fsynthetic", "base=main", "state=all", "per_page=100");
    override = request -> request.getRequestUrl().encodedPath().equals(REPO_PATH + "/pulls") ? response(200, "[]") : null;
    assertThat(resolver.lookup(candidate()).outcome()).isEqualTo("unknown");
    assertThat(resolver.getConfirmedResult(key())).isNull();
    assertThatThrownBy(() -> adapter.execute(action, permit(action)))
        .hasFieldOrPropertyWithValue("errorCode", "EXECUTION_OUTCOME_UNKNOWN");
    assertThat(posts()).isEqualTo(1);
  }

  @Test void unboundMetadataWrongAdapterAndOverwrittenRegistrationNeverCertifySuccess() {
    assertThat(resolver.lookup(candidate()).outcome()).isEqualTo("unknown");
    resolver.registerOperation(key(), REPO, HEAD, "main", 71);
    assertThat(resolver.lookup(candidate()).outcome()).isEqualTo("unknown");
    assertThatThrownBy(this::register).hasFieldOrPropertyWithValue("errorCode", "OPERATION_BINDING_CONFLICT");
    configure(65_536, Duration.ofSeconds(3)); register();
    var wrong = new ReconciliationCandidate(new TenantPartition("synthetic"), "attempt", "wrong-adapter", key(), NOW, 1, "worker", NOW.plusSeconds(30));
    assertThat(resolver.lookup(wrong).outcome()).isEqualTo("unknown"); assertThat(requests).isEmpty();
  }

  @ParameterizedTest @ValueSource(strings = {"http://api.github.com", "https://other.invalid", "https://api.github.com.evil.invalid",
      "https://api.github.com@other.invalid", "https://user@api.github.com", "https://api.github.com/path", "https://api.github.com/?x=1",
      "https://api.github.com/#x", "https://api.github.com:444", "https://api.github.com.", "http://127.0.0.1:1", "http://localhost:1",
      "https://127.0.0.1", "https://api.github.com/%2e", "https://api.github.com//"})
  void publicConstructorsCannotSelectArbitraryOriginOrTestMode(String uri) {
    assertThatThrownBy(() -> new GitHubPullRequestOutcomeResolver(provider, URI.create(uri), Duration.ofSeconds(1)))
        .hasFieldOrPropertyWithValue("errorCode", "TARGET_DESTINATION_REJECTED");
    assertThatThrownBy(() -> new GitHubDraftPullRequestAdapter(provider, ledger, resolver, permits, "audience",
        URI.create(uri), Duration.ofSeconds(1), Duration.ofSeconds(1), 65536, clock, null, null))
        .hasFieldOrPropertyWithValue("errorCode", "TARGET_DESTINATION_REJECTED");
    assertThat(requests).isEmpty();
  }
  @Test void platformTlsAndExplicitNumericSyntheticSeamCannotBeConfused() {
    var cert = new HeldCertificate.Builder().addSubjectAlternativeName("api.github.com").build();
    var tls = new HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate()).build();
    assertThatThrownBy(() -> new GitHubPullRequestOutcomeResolver(provider, URI.create("https://api.github.com"),
        Duration.ofSeconds(1), 65536, tls.sslSocketFactory(), tls.trustManager()))
        .hasFieldOrPropertyWithValue("errorCode", "TARGET_DESTINATION_REJECTED");
    assertThatThrownBy(() -> GitHubApiTransport.synthetic(URI.create("http://localhost:1234"), Duration.ofSeconds(1), Duration.ofSeconds(1), 10))
        .isInstanceOf(GitHubPullRequestException.class);
    var legacy = new GitHubDraftPullRequestAdapter(provider, ledger, resolver, null, null);
    ObjectNode action = action();
    assertThatThrownBy(() -> legacy.execute(action, permit(action)))
        .hasFieldOrPropertyWithValue("errorCode", "TARGET_PREFLIGHT_BINDING_REQUIRED");
    var noAuthority = GitHubDraftPullRequestAdapter.synthetic(provider, ledger, resolver, null, BINDING, transport, clock);
    assertThatThrownBy(() -> noAuthority.execute(action, null)).hasFieldOrPropertyWithValue("errorCode", "PERMIT_AUTHORITY_REQUIRED");
    assertThat(requests).isEmpty();
  }

  @ParameterizedTest @ValueSource(strings = {"missing_permit", "bad_permit", "hash_mismatch", "wrong_audience", "replay"})
  void permitsRemainMandatoryAndRequestBound(String change) {
    var action = action(); String token = permit(action);
    if (change.equals("hash_mismatch")) args(action).put("title", "changed");
    if (change.equals("replay")) permits.verifyAndConsume(token, action, GitHubDraftPullRequestAction.OPERATION);
    if (change.equals("wrong_audience")) token = permits.issue(action, evaluation(action), "different-audience");
    String presented = change.equals("missing_permit") ? null : change.equals("bad_permit") ? "invalid" : token;
    assertThatThrownBy(() -> adapter.execute(action, presented)).isInstanceOf(RuntimeException.class);
    assertThat(requests).isEmpty();
  }

  @ParameterizedTest @ValueSource(strings = {"repository", "base", "head", "draft", "target", "target_resource", "target_account", "unknown_arg",
      "unknown_target", "self_asserted", "split_conflict", "split_missing", "repo_type", "title_missing", "title_blank", "title_large",
      "body_type", "body_large", "head_bad", "head_missing", "base_missing", "schema", "tool"})
  void actionAndOperatorMismatchCannotReachTarget(String change) {
    var action = action(); var args = args(action); var target = (ObjectNode) action.path("action").path("target");
    switch (change) {
      case "repository" -> { args.put("repository", "other/repo"); target.put("accountId", "other/repo"); }
      case "base" -> args.put("base", "other");
      case "head" -> args.put("head", "feature/other");
      case "draft" -> args.put("draft", false);
      case "target" -> target.put("system", "other");
      case "target_resource" -> target.put("resourceType", "repository");
      case "target_account" -> target.put("accountId", "other/repo");
      case "unknown_arg" -> args.put("evil", TOKEN);
      case "unknown_target" -> target.put("url", TOKEN);
      case "self_asserted" -> args.put("verified", true);
      case "split_conflict" -> { args.put("owner", "other"); args.put("repo", "repo"); }
      case "split_missing" -> args.put("owner", "tracepermit");
      case "repo_type" -> args.put("repository", 1);
      case "title_missing" -> args.remove("title");
      case "title_blank" -> args.put("title", " ");
      case "title_large" -> args.put("title", "x".repeat(257));
      case "body_type" -> args.put("body", 1);
      case "body_large" -> args.put("body", "x".repeat(65537));
      case "head_bad" -> args.put("head", "-bad..ref");
      case "head_missing" -> args.remove("head");
      case "base_missing" -> args.remove("base");
      case "schema" -> action.put("schemaVersion", "99");
      case "tool" -> ((ObjectNode) action.path("action")).put("toolName", TOKEN);
      default -> throw new AssertionError();
    }
    assertThatThrownBy(() -> adapter.execute(action, "unused")).hasNoCause()
        .satisfies(e -> assertThat(e.getMessage().contains(TOKEN)).isFalse());
    assertThat(requests).isEmpty();
  }

  @Test void concurrentIdenticalCallsDispatchExactlyOnePost() throws Exception {
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    override = request -> {
      if (!"POST".equals(request.getMethod())) return null;
      entered.countDown();
      try { if (!release.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("TEST_TIMEOUT"); }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("TEST_INTERRUPTED"); }
      return null;
    };
    var pool = Executors.newFixedThreadPool(2); var action = action();
    try {
      var first = pool.submit(() -> adapter.execute(action, permit(action)));
      assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(() -> adapter.execute(action, permit(action)))
          .hasFieldOrPropertyWithValue("errorCode", "CONCURRENT_EXECUTION_IN_PROGRESS");
      release.countDown(); assertThat(first.get(3, TimeUnit.SECONDS).number()).isEqualTo(42);
      assertThat(posts()).isEqualTo(1);
    } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(3, TimeUnit.SECONDS)).isTrue(); }
  }

  @ParameterizedTest @ValueSource(strings = {"201_timeout", "disconnect", "redirect", "500", "401", "403", "404", "422", "503_retry", "408"})
  void postFailureNeverRetriesOrForwardsCredentials(String mode) throws Exception {
    try (var other = new MockWebServer()) {
      other.start(InetAddress.getByName("127.0.0.1"), 0);
      override = request -> {
        if (!"POST".equals(request.getMethod())) return null;
        return switch (mode) {
          case "201_timeout" -> response(201, "{}").setBodyDelay(2, TimeUnit.SECONDS);
          case "disconnect" -> new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST);
          case "redirect" -> response(307, "{}").setHeader("Location", other.url("/steal"));
          case "503_retry" -> response(503, "{}").setHeader("Retry-After", "0");
          default -> response(Integer.parseInt(mode), TOKEN);
        };
      };
      configure(65536, Duration.ofMillis(750));
      var action = action();
      assertThatThrownBy(() -> adapter.execute(action, permit(action))).hasNoCause();
      assertThat(status()).isEqualTo(List.of("401", "403", "404", "422").contains(mode) ? "failed" : "unknown");
      assertThatThrownBy(() -> adapter.execute(action, permit(action))).hasNoCause();
      assertThat(posts()).isEqualTo(1); assertThat(requests).hasSize(5);
      assertThat(other.getRequestCount()).isZero();
    }
  }

  @Test void preflightRedirectNeverForwardsAuthorizationAndLookupIsUnknown() throws Exception {
    try (var other = new MockWebServer()) {
      other.start(InetAddress.getByName("127.0.0.1"), 0);
      override = request -> response(302, "{}").setHeader("Location", other.url("/steal"));
      var action = action(); assertThatThrownBy(() -> adapter.execute(action, permit(action))).hasNoCause();
      assertThat(posts()).isZero(); assertThat(requests).hasSize(1);
      assertThat(resolver.lookup(candidate()).outcome()).isEqualTo("unknown");
      assertThat(requests).hasSize(2); assertThat(other.getRequestCount()).isZero();
    }
  }

  @Test void chunkedExactBoundAcceptsAndOneOverflowByteRejectsForPostAndLookup() {
    int limit = 4096; configure(limit, Duration.ofSeconds(3));
    String pr = confirmation().toString();
    override = request -> {
      if (!request.getRequestUrl().encodedPath().equals(REPO_PATH + "/pulls")) return null;
      String body = "POST".equals(request.getMethod()) ? pr : "[" + pr + "]";
      return response("POST".equals(request.getMethod()) ? 201 : 200, "")
          .setChunkedBody(body + " ".repeat(limit - body.getBytes(StandardCharsets.UTF_8).length), 127);
    };
    var action = action(); assertThat(adapter.execute(action, permit(action)).id()).isEqualTo(42001);
    assertThat(resolver.lookup(candidate()).outcome()).isEqualTo("succeeded");
    override = request -> request.getRequestUrl().encodedPath().equals(REPO_PATH + "/pulls")
        ? response(200, "").setChunkedBody("x".repeat(limit + 1), 127) : null;
    assertThat(resolver.lookup(candidate()).reasonCode()).isEqualTo("RESPONSE_TOO_LARGE");
    assertThat(resolver.getConfirmedResult(key())).isNull();
  }

  @Test void wholePreflightBudgetIsNotResetAndSessionClosesOwnedClientResources() throws Exception {
    configure(65536, Duration.ofMillis(450));
    override = request -> {
      String path = request.getRequestUrl().encodedPath();
      JsonNode body = path.equals("/installation/repositories") ? installation()
          : path.equals(REPO_PATH) ? repository()
          : path.endsWith(HEAD) ? ref(HEAD, BINDING.headSha()) : ref("main", BINDING.baseSha());
      return response(200, body.toString()).setBodyDelay(175, TimeUnit.MILLISECONDS);
    };
    long start = System.nanoTime(); var action = action();
    assertThatThrownBy(() -> adapter.execute(action, permit(action))).hasNoCause();
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    assertThat(posts()).isZero();
    var session = transport.open();
    var field = session.getClass().getDeclaredField("client"); field.setAccessible(true);
    var client = (OkHttpClient) field.get(session); session.close();
    assertThat(client.connectionPool().connectionCount()).isZero();
    assertThat(client.dispatcher().executorService().isShutdown()).isTrue();
  }

  @ParameterizedTest @ValueSource(strings = {"number", "id", "installation", "head", "base", "draft", "state", "url", "missing"})
  void malformedCachedSuccessCannotBorrowTargetIdentity(String change) {
    var action = action();
    var stored = new GitHubDraftPullRequestResult(42, 42001, "https://github.com/" + REPO + "/pull/42",
        true, "open", REPO, HEAD, "main", key(), FP, 71).toJsonNode()
        .put("targetBindingHash", BINDING.fingerprint())
        .put("payloadHash", io.tracepermit.contracts.CanonicalJson.sha256(action.path("action")));
    switch (change) {
      case "number" -> stored.put("number", "42");
      case "id" -> stored.put("id", 0);
      case "installation" -> stored.put("installationId", 72);
      case "head" -> stored.put("head", "different");
      case "base" -> stored.put("base", "different");
      case "draft" -> stored.put("draft", false);
      case "state" -> stored.put("state", "closed");
      case "url" -> stored.put("htmlUrl", "https://other.invalid");
      case "missing" -> stored.removeAll();
      default -> throw new AssertionError();
    }
    var begin = ledger.begin("event", "permit-id", key(), NOW);
    ledger.succeed(begin.attempt().attemptId(), NOW, stored);
    assertThatThrownBy(() -> adapter.execute(action, permit(action)))
        .hasFieldOrPropertyWithValue("errorCode", "INVALID_CACHED_RESULT");
    assertThat(requests).isEmpty();
  }

  @Test void reconstructedResolverCannotBorrowSuccessForChangedTargetOrPayload() {
    var action = action(); adapter.execute(action, permit(action));
    configure(65536, Duration.ofSeconds(3));
    args(action).put("body", "changed after resolver restart");
    assertThatThrownBy(() -> adapter.execute(action, permit(action)))
        .hasFieldOrPropertyWithValue("errorCode", "INVALID_CACHED_RESULT");
    var changed = new GitHubPilotTargetBinding(71, 101, 201, REPO, HEAD, "main", "c".repeat(40), BINDING.baseSha());
    var newResolver = GitHubPullRequestOutcomeResolver.synthetic(provider, changed, transport, clock);
    var newAdapter = GitHubDraftPullRequestAdapter.synthetic(provider, ledger, newResolver, permits, changed, transport, clock);
    var original = action();
    assertThatThrownBy(() -> newAdapter.execute(original, permit(original)))
        .hasFieldOrPropertyWithValue("errorCode", "INVALID_CACHED_RESULT");
    assertThat(requests).hasSize(5); assertThat(posts()).isEqualTo(1);
  }

  @ParameterizedTest @ValueSource(ints = {0, -1, 1048577})
  void unsafeTransportBoundsAreRejected(int limit) {
    assertThatThrownBy(() -> GitHubApiTransport.synthetic(origin, Duration.ofSeconds(1), Duration.ofSeconds(1), limit))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> GitHubApiTransport.synthetic(origin, Duration.ZERO, Duration.ofSeconds(1), 10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> GitHubApiTransport.synthetic(origin, Duration.ofSeconds(1), Duration.ofSeconds(11), 10))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest @ValueSource(strings = {"installationId", "repositoryId", "ownerId"})
  void operatorIdsOutsideFrozenSafeIntegerRangeAreRejected(String field) {
    for (long unsafe : new long[] {9_007_199_254_740_992L, 9_007_199_254_740_993L, Long.MAX_VALUE}) {
      assertThatThrownBy(() -> new GitHubPilotTargetBinding(
          field.equals("installationId") ? unsafe : 71, field.equals("repositoryId") ? unsafe : 101,
          field.equals("ownerId") ? unsafe : 201, REPO, HEAD, "main", BINDING.headSha(), BINDING.baseSha()))
          .hasFieldOrPropertyWithValue("errorCode", "TARGET_BINDING_INVALID").hasNoCause();
    }
    assertThat(requests).isEmpty();
  }

  @Test void maximumSafeOperatorIdsRemainExactAndDistinct() {
    long maximum = 9_007_199_254_740_991L;
    var maximumBinding = new GitHubPilotTargetBinding(maximum, maximum, maximum, REPO, HEAD, "main",
        BINDING.headSha(), BINDING.baseSha());
    var hashes = new java.util.HashSet<String>(); hashes.add(maximumBinding.fingerprint());
    for (String field : List.of("installationId", "repositoryId", "ownerId")) {
      var adjacent = new GitHubPilotTargetBinding(field.equals("installationId") ? maximum - 1 : maximum,
          field.equals("repositoryId") ? maximum - 1 : maximum, field.equals("ownerId") ? maximum - 1 : maximum,
          REPO, HEAD, "main", BINDING.headSha(), BINDING.baseSha());
      assertThat(adjacent).isNotEqualTo(maximumBinding); hashes.add(adjacent.fingerprint());
    }
    assertThat(hashes).hasSize(4); assertThat(requests).isEmpty();
  }

  @ParameterizedTest @ValueSource(strings = {"repositoryId", "ownerId"})
  void adjacentSafeIdentityCannotBorrowAnActualReceiptAfterResolverReconstruction(String field) {
    long maximum = 9_007_199_254_740_991L;
    var before = new GitHubPilotTargetBinding(71, field.equals("repositoryId") ? maximum - 1 : 101,
        field.equals("ownerId") ? maximum - 1 : 201, REPO, HEAD, "main", BINDING.headSha(), BINDING.baseSha());
    var after = new GitHubPilotTargetBinding(71, field.equals("repositoryId") ? maximum : 101,
        field.equals("ownerId") ? maximum : 201, REPO, HEAD, "main", BINDING.headSha(), BINDING.baseSha());
    assertThat(before.fingerprint()).isNotEqualTo(after.fingerprint());
    var observedRepository = repository().put("id", before.repositoryId());
    ((ObjectNode) observedRepository.path("owner")).put("id", before.ownerId());
    override = request -> {
      String path = request.getRequestUrl().encodedPath();
      if (path.equals("/installation/repositories")) {
        var visible = JsonSupport.MAPPER.createObjectNode().put("total_count", 1);
        visible.putArray("repositories").add(observedRepository); return response(200, visible.toString());
      }
      if (path.equals(REPO_PATH)) return response(200, observedRepository.toString());
      if ("POST".equals(request.getMethod())) {
        var pr = confirmation();
        ((ObjectNode) pr.path("head")).set("repo", observedRepository);
        ((ObjectNode) pr.path("base")).set("repo", observedRepository);
        return response(201, pr.toString());
      }
      return null;
    };
    resolver = GitHubPullRequestOutcomeResolver.synthetic(provider, before, transport, clock);
    adapter = GitHubDraftPullRequestAdapter.synthetic(provider, ledger, resolver, permits, before, transport, clock);
    var action = action();
    assertThat(adapter.execute(action, permit(action)).id()).isEqualTo(42001);
    var reconstructed = GitHubPullRequestOutcomeResolver.synthetic(provider, after, transport, clock);
    var changed = GitHubDraftPullRequestAdapter.synthetic(provider, ledger, reconstructed, permits, after, transport, clock);
    assertThatThrownBy(() -> changed.execute(action, permit(action)))
        .hasFieldOrPropertyWithValue("errorCode", "INVALID_CACHED_RESULT");
    assertThat(requests).hasSize(5); assertThat(posts()).isEqualTo(1);
  }

  @ParameterizedTest @ValueSource(strings = {"installation", "repository_id", "owner", "repository", "base", "head_same",
      "head_dot", "head_lock", "head_slash", "head_space", "head_colon", "head_sha", "base_sha"})
  void operatorRecordRejectsUnboundOrUnsafePreconditions(String change) {
    assertThatThrownBy(() -> new GitHubPilotTargetBinding(
        change.equals("installation") ? 0 : 71, change.equals("repository_id") ? 0 : 101,
        change.equals("owner") ? 0 : 201, change.equals("repository") ? "other/repo" : REPO,
        switch (change) {
          case "head_same" -> "main";
          case "head_dot" -> "feature/../other";
          case "head_lock" -> "feature/other.lock";
          case "head_slash" -> "feature//other";
          case "head_space" -> "feature/other branch";
          case "head_colon" -> "other:branch";
          default -> HEAD;
        }, change.equals("base") ? "other" : "main", change.equals("head_sha") ? "bad" : BINDING.headSha(),
        change.equals("base_sha") ? "c".repeat(64) : BINDING.baseSha()))
        .hasFieldOrPropertyWithValue("errorCode", "TARGET_BINDING_INVALID");
    assertThat(requests).isEmpty();
  }

  @Test void verifiedPermitIdAndFixedDiagnosticsNeverRetainCredentialsOrProviderCauses() {
    var action = action(); String permit = permit(action);
    adapter.execute(action, permit);
    assertThat(ledger.findByIdempotencyKey(key()).permitId().startsWith("permit-")).isTrue();
    assertThat(ledger.findByIdempotencyKey(key()).toString().contains(permit)).isFalse();
    var error = new GitHubPullRequestException("FIXED", "FIXED", TOKEN, new IOException(TOKEN));
    assertThat(error.getMessage().contains(TOKEN)).isFalse(); assertThat(error.getCause()).isNull();
    assertThat(provider.getInstallationCredentials(REPO).toString().contains(TOKEN)).isFalse();
    assertThat(BINDING.toString().contains(REPO)).isFalse();
    assertThat(resolver.getOperation(key()).toString().contains("Synthetic body")).isFalse();
  }

  @Test void missingBindingAndInvalidCandidateCannotUsePublicProductionConstruction() {
    var prodResolver = new GitHubPullRequestOutcomeResolver(provider, BINDING);
    var prod = new GitHubDraftPullRequestAdapter(provider, ledger, prodResolver, permits,
        GitHubDraftPullRequestAction.OPERATION, BINDING);
    assertThat(prod.expectedAudience()).isEqualTo(GitHubDraftPullRequestAction.OPERATION);
    assertThatThrownBy(() -> new GitHubDraftPullRequestAdapter(provider, ledger, resolver, permits,
        GitHubDraftPullRequestAction.OPERATION, new GitHubPilotTargetBinding(71, 101, 201, REPO, "different", "main",
            BINDING.headSha(), BINDING.baseSha())))
        .hasFieldOrPropertyWithValue("errorCode", "TARGET_BINDING_INVALID");
    assertThatThrownBy(() -> resolver.registerOperation("different-key", BINDING, "title", "body"))
        .hasFieldOrPropertyWithValue("errorCode", "LOOKUP_BINDING_INVALID");
    assertThat(requests).isEmpty();
  }

  private static MockResponse changedConfirmation(String change, int status) {
    var pr = confirmation();
    switch (change) {
      case "id_zero" -> pr.put("id", 0);
      case "id_negative" -> pr.put("id", -1);
      case "id_string" -> pr.put("id", "42001");
      case "id_decimal" -> pr.put("id", 1.5);
      case "id_overflow" -> pr.set("id", JsonSupport.MAPPER.getNodeFactory().numberNode(new java.math.BigInteger("999999999999999999999999")));
      case "missing_id" -> pr.remove("id");
      case "number_zero" -> pr.put("number", 0);
      case "number_string" -> pr.put("number", "42");
      case "number_overflow" -> pr.put("number", 1L + Integer.MAX_VALUE);
      case "draft_false" -> pr.put("draft", false);
      case "missing_draft" -> pr.remove("draft");
      case "draft_string" -> pr.put("draft", "true");
      case "closed" -> pr.put("state", "closed");
      case "missing_state" -> pr.remove("state");
      case "merged" -> pr.put("merged", true);
      case "merged_at" -> pr.put("merged_at", NOW.toString());
      case "url" -> pr.put("html_url", "https://other.invalid/steal");
      case "head_ref" -> ((ObjectNode) pr.path("head")).put("ref", "other");
      case "base_ref" -> ((ObjectNode) pr.path("base")).put("ref", "other");
      case "head_sha" -> ((ObjectNode) pr.path("head")).put("sha", "c".repeat(40));
      case "base_sha" -> ((ObjectNode) pr.path("base")).put("sha", "c".repeat(40));
      case "head_repo" -> ((ObjectNode) pr.path("head").path("repo")).put("id", 999);
      case "base_repo" -> ((ObjectNode) pr.path("base").path("repo")).put("full_name", "other/repo");
      case "head_owner" -> ((ObjectNode) pr.path("head").path("repo").path("owner")).put("id", 999);
      case "missing_repo" -> ((ObjectNode) pr.path("head")).remove("repo");
      case "title" -> pr.put("title", "other");
      case "body" -> pr.put("body", "other");
      case "null" -> { return response(status, "null"); }
      case "empty" -> { return response(status, ""); }
      case "invalid" -> { return response(status, "invalid " + TOKEN); }
      case "duplicate" -> { return response(status, pr.toString().replace("\"id\":42001", "\"id\":0,\"id\":42001")); }
      case "trailing" -> { return response(status, pr + " {}"); }
      case "pagination" -> { return response(status, pr.toString()).setHeader("Link", "<https://other.invalid/next>; rel=next"); }
      case "oversized" -> { return response(status, "").setChunkedBody("x".repeat(70000), 127); }
      default -> throw new AssertionError();
    }
    return response(status, pr.toString());
  }
  private static ObjectNode repository() {
    var repo = JsonSupport.MAPPER.createObjectNode().put("id", 101).put("name", "agentcore-pr-pilot")
        .put("full_name", REPO).put("private", true).put("archived", false).put("disabled", false).put("fork", false);
    repo.putObject("owner").put("id", 201).put("login", "tracepermit"); return repo;
  }
  private static ObjectNode installation() {
    var node = JsonSupport.MAPPER.createObjectNode().put("total_count", 1); node.putArray("repositories").add(repository()); return node;
  }
  private static ObjectNode ref(String ref, String sha) {
    var node = JsonSupport.MAPPER.createObjectNode().put("ref", "refs/heads/" + ref);
    node.putObject("object").put("type", "commit").put("sha", sha); return node;
  }
  private static ObjectNode confirmation() {
    var pr = JsonSupport.MAPPER.createObjectNode().put("id", 42001).put("number", 42).put("draft", true)
        .put("state", "open").put("html_url", "https://github.com/" + REPO + "/pull/42")
        .put("title", "Synthetic draft").put("body", "Synthetic body");
    pr.putObject("head").put("ref", HEAD).put("sha", BINDING.headSha()).set("repo", repository());
    pr.putObject("base").put("ref", "main").put("sha", BINDING.baseSha()).set("repo", repository());
    return pr;
  }
  private static MockResponse response(int status, String body) { return new MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body); }
  private static String key() { return GitHubDraftPullRequestAdapter.computeOperationKey(71, REPO, HEAD, "main"); }
  private void register() { resolver.registerOperation(key(), BINDING, "Synthetic draft", "Synthetic body"); }
  private static ReconciliationCandidate candidate() {
    return new ReconciliationCandidate(new TenantPartition("synthetic"), "attempt", GitHubDraftPullRequestAction.OPERATION,
        key(), NOW, 1, "worker", NOW.plusSeconds(30));
  }
  private long posts() { return requests.stream().filter(s -> s.startsWith("POST ")).count(); }
  private String status() { return ledger.findByIdempotencyKey(key()).status(); }
  private static ObjectNode args(ObjectNode action) { return (ObjectNode) action.path("action").path("arguments"); }
  private static ObjectNode action() {
    try {
      var action = (ObjectNode) JsonSupport.MAPPER.readTree(Files.readString(Path.of(System.getProperty("tracepermit.repositoryRoot"),
          "contracts/vectors/business-operation-v1.json"))).path("operation").path("canonicalAction").deepCopy();
      action.put("eventId", UUID.randomUUID().toString());
      var args = args(action); args.removeAll(); args.put("repository", REPO).put("head", HEAD).put("base", "main")
          .put("draft", true).put("title", "Synthetic draft").put("body", "Synthetic body");
      ((ObjectNode) action.path("action").path("target")).put("accountId", REPO).put("resourceType", "pull_request");
      return action;
    } catch (IOException failure) { throw new IllegalStateException("TEST_FIXTURE_UNAVAILABLE"); }
  }
  private static ObjectNode evaluation(JsonNode action) {
    var evaluation = JsonSupport.MAPPER.createObjectNode().put("schemaVersion", "1.0").put("evaluationId", "synthetic")
        .put("eventId", action.path("eventId").asText()).put("evaluatedAt", NOW.toString());
    evaluation.putObject("decision").put("outcome", "allow").put("reasonCode", "SYNTHETIC_ALLOWED")
        .put("policyId", "synthetic").put("policyVersion", 1); return evaluation;
  }
  private String permit(JsonNode action) { return permits.issue(action, evaluation(action), GitHubDraftPullRequestAction.OPERATION); }
}
