package io.tracepermit.adapters.remote.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.adapters.remote.github.credentials.GitHubInstallationConfiguration;
import io.tracepermit.adapters.remote.github.credentials.SyntheticInstallationCredentialFixture;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.InMemoryExecutionLedger;
import io.tracepermit.security.ExecutionPermitAuthority;
import io.tracepermit.security.InMemoryPermitConsumptionStore;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Concrete provider + existing permit/adapter/preflight, fake credential exchange + loopback target. */
final class GitHubCredentialCompositionTest {
  private static final String REPO = GitHubDraftPullRequestAction.ALLOWED_REPOSITORY;
  private static final String HEAD = "feature/composition";
  private static final String REPO_PATH = "/repos/" + REPO;
  private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
  private static final GitHubPilotTargetBinding BINDING = new GitHubPilotTargetBinding(
      71, 101, 201, REPO, HEAD, "main", "a".repeat(40), "b".repeat(40));
  private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
  private final Clock clock = new Clock() {
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now.get(); }
  };
  private final List<String> requests = new CopyOnWriteArrayList<>();
  private volatile Function<RecordedRequest, MockResponse> changed = request -> null;
  private MockWebServer server;
  private SyntheticInstallationCredentialFixture credentials;
  private InMemoryExecutionLedger ledger;
  private ExecutionPermitAuthority permits;
  private GitHubDraftPullRequestAdapter adapter;

  @BeforeEach void start() throws Exception {
    credentials = fixture(71);
    ledger = new InMemoryExecutionLedger(() -> "attempt-" + UUID.randomUUID());
    byte[] key = new byte[32]; new java.security.SecureRandom().nextBytes(key);
    permits = new ExecutionPermitAuthority(ExecutionPermitAuthority.DEFAULT_ISSUER, "synthetic", key,
        60, clock, () -> "permit-" + UUID.randomUUID(), new InMemoryPermitConsumptionStore());
    server = new MockWebServer();
    server.setDispatcher(new Dispatcher() {
      @Override public MockResponse dispatch(RecordedRequest request) {
        requests.add(request.getMethod() + " " + request.getRequestUrl().encodedPath());
        // Only opaque installation credentials reach the target; App JWTs stay in the fake exchange.
        if (!credentials.authorizes(request.getHeader("Authorization"))) return response(401, "{}");
        MockResponse override = changed.apply(request); if (override != null) return override;
        String path = request.getRequestUrl().encodedPath();
        if (path.equals("/installation/repositories")) {
          var installed = JsonSupport.MAPPER.createObjectNode().put("total_count", 1);
          installed.putArray("repositories").add(repository()); return response(200, installed.toString());
        }
        if (path.equals(REPO_PATH)) return response(200, repository().toString());
        if (path.equals(REPO_PATH + "/git/ref/heads/" + HEAD)) return response(200, ref(HEAD, BINDING.headSha()).toString());
        if (path.equals(REPO_PATH + "/git/ref/heads/main")) return response(200, ref("main", BINDING.baseSha()).toString());
        if (path.equals(REPO_PATH + "/pulls") && request.getMethod().equals("POST")) return response(201, confirmation().toString());
        return response(404, "{}");
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0); compose();
  }
  private SyntheticInstallationCredentialFixture fixture(long installation) throws Exception {
    return new SyntheticInstallationCredentialFixture(new GitHubInstallationConfiguration(31, installation, 101, 201,
        REPO, GitHubInstallationConfiguration.requiredPermissions()), clock);
  }
  private void compose() {
    var transport = GitHubApiTransport.synthetic(URI.create("http://127.0.0.1:" + server.getPort()),
        Duration.ofSeconds(1), Duration.ofSeconds(3), 65_536);
    var resolver = GitHubPullRequestOutcomeResolver.synthetic(credentials.provider(), BINDING, transport, clock);
    adapter = GitHubDraftPullRequestAdapter.synthetic(credentials.provider(), ledger, resolver, permits, BINDING, transport, clock);
  }
  @AfterEach void stop() throws Exception { if (server != null) server.shutdown(); }

  @Test void concreteProviderServesAdapterAndReplayUsesOnlyTheProcessLocalResult() throws Exception {
    var action = action(); var result = adapter.execute(action, permit(action));
    assertThat(result.number()).isEqualTo(42);
    assertThat(result.credentialFingerprint()).matches("sha256:[a-f0-9]{64}");
    assertThat(credentials.containsSecret(result.toJsonNode().toString())).isFalse();
    assertThat(credentials.calls()).containsExactly("KEY", "OPEN", "GET_INSTALLATION", "POST_TOKEN", "CLOSE");
    assertThat(requests).containsExactly("GET /installation/repositories", "GET " + REPO_PATH,
        "GET " + REPO_PATH + "/git/ref/heads/" + HEAD, "GET " + REPO_PATH + "/git/ref/heads/main", "POST " + REPO_PATH + "/pulls");
    var installationRequest = server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS);
    assertThat(installationRequest != null).isTrue();
    assertThat(installationRequest.getRequestUrl().queryParameterNames()).containsExactlyInAnyOrder("page", "per_page");
    assertThat(installationRequest.getRequestUrl().queryParameter("page")).isEqualTo("1");
    assertThat(installationRequest.getRequestUrl().queryParameter("per_page")).isEqualTo("100");
    assertThat(adapter.execute(action, permit(action)).number()).isEqualTo(42);
    assertThat(requests).hasSize(5);
    assertThat(credentials.calls().stream().filter("POST_TOKEN"::equals).count()).isEqualTo(2);
    assertThat(ledger.findByIdempotencyKey(operationKey()).status()).isEqualTo("succeeded");
  }

  @ParameterizedTest @ValueSource(strings = {"missing", "invalid", "changed_action", "consumed", "wrong_audience", "allowlist"})
  void providerCannotBypassPermitOrActionChecks(String kind) throws Exception {
    var action = action(); String permit = permit(action);
    switch (kind) {
      case "missing" -> permit = null;
      case "invalid" -> permit = "synthetic-invalid";
      case "changed_action" -> ((ObjectNode) action.path("action").path("arguments")).put("title", "changed");
      case "consumed" -> permits.verifyAndConsume(permit, action, GitHubDraftPullRequestAction.OPERATION);
      case "wrong_audience" -> permit = permits.issue(action, evaluation(action), "other-audience");
      case "allowlist" -> ((ObjectNode) action.path("action").path("arguments")).put("repository", "other/repository");
      default -> throw new AssertionError();
    }
    String supplied = permit;
    assertThat(catchThrowable(() -> adapter.execute(action, supplied))).isInstanceOf(RuntimeException.class);
    assertThat(credentials.calls()).isEmpty(); assertThat(requests).isEmpty();
  }

  @ParameterizedTest @ValueSource(strings = {"app", "installation", "cleanup", "expired_cleanup"})
  void failedProviderOrWrongInstallationCannotReachTarget(String kind) throws Exception {
    if (kind.equals("app")) credentials.changeInstallationResponse(value -> value.put("app_id", 32));
    if (kind.equals("installation")) { credentials = fixture(72); compose(); }
    if (kind.equals("cleanup")) credentials.onCleanup(() -> { throw new IllegalStateException("synthetic-private-canary"); });
    if (kind.equals("expired_cleanup")) credentials.onCleanup(() -> now.set(NOW.plusSeconds(120)));
    var action = action(); String permit = permit(action);
    Throwable failure = catchThrowable(() -> adapter.execute(action, permit));
    assertThat(failure).isInstanceOf(GitHubPullRequestException.class);
    assertThat(((GitHubPullRequestException) failure).errorCode()).isEqualTo(kind.equals("installation") ? "TARGET_CREDENTIAL_REJECTED" : "TARGET_AUTH_UNAVAILABLE");
    assertThat(requests).isEmpty(); assertThat(failure.getCause()).isNull();
    assertThat(credentials.containsSecret(failure.toString())).isFalse();
    assertThat(catchThrowable(() -> permits.verifyAndConsume(permit, action, GitHubDraftPullRequestAction.OPERATION))).isInstanceOf(RuntimeException.class);
  }

  @ParameterizedTest @ValueSource(strings = {"repository", "head", "expired_during_preflight"})
  void successfulMintNeverReplacesTargetPreflight(String kind) throws Exception {
    changed = request -> {
      if (request.getPath().equals(REPO_PATH)) {
        if (kind.equals("repository")) return response(200, repository().put("id", 102).toString());
        if (kind.equals("expired_during_preflight")) now.set(NOW.plusSeconds(120));
      }
      if (kind.equals("head") && request.getPath().equals(REPO_PATH + "/git/ref/heads/" + HEAD))
        return response(200, ref(HEAD, "c".repeat(40)).toString());
      return null;
    };
    var action = action(); String permit = permit(action);
    assertThat(catchThrowable(() -> adapter.execute(action, permit))).isInstanceOf(GitHubPullRequestException.class);
    assertThat(requests).isNotEmpty().allMatch(value -> value.startsWith("GET "));
    assertThat(ledger.findByIdempotencyKey(operationKey()).status()).isEqualTo("failed");
    assertThat(credentials.calls()).contains("POST_TOKEN", "CLOSE");
  }

  @Test void ambiguousWriteStaysUnknownAndFreshMintCannotTriggerAnotherDispatch() throws Exception {
    changed = request -> request.getMethod().equals("POST") ? response(201, "{}") : null;
    var action = action(); String first = permit(action);
    assertThat(catchThrowable(() -> adapter.execute(action, first))).isInstanceOf(GitHubPullRequestException.class);
    assertThat(ledger.findByIdempotencyKey(operationKey()).status()).isEqualTo("unknown");
    String second = permit(action);
    Throwable failure = catchThrowable(() -> adapter.execute(action, second));
    assertThat(((GitHubPullRequestException) failure).errorCode()).isEqualTo("EXECUTION_OUTCOME_UNKNOWN");
    assertThat(requests.stream().filter(value -> value.startsWith("POST ")).count()).isEqualTo(1);
    assertThat(requests).hasSize(5);
    assertThat(credentials.calls().stream().filter("POST_TOKEN"::equals).count()).isEqualTo(2);
  }

  private static ObjectNode repository() {
    var value = JsonSupport.MAPPER.createObjectNode().put("id", 101).put("name", REPO.split("/")[1]).put("full_name", REPO)
        .put("private", true).put("fork", false).put("archived", false).put("disabled", false);
    value.putObject("owner").put("id", 201).put("login", REPO.split("/")[0]); return value;
  }
  private static ObjectNode ref(String ref, String sha) {
    var value = JsonSupport.MAPPER.createObjectNode().put("ref", "refs/heads/" + ref);
    value.putObject("object").put("type", "commit").put("sha", sha); return value;
  }
  private static ObjectNode confirmation() {
    var value = JsonSupport.MAPPER.createObjectNode().put("id", 42001).put("number", 42).put("draft", true).put("state", "open")
        .put("html_url", "https://github.com/" + REPO + "/pull/42").put("title", "Synthetic draft").put("body", "Synthetic body");
    value.putObject("head").put("ref", HEAD).put("sha", BINDING.headSha()).set("repo", repository());
    value.putObject("base").put("ref", "main").put("sha", BINDING.baseSha()).set("repo", repository()); return value;
  }
  private static ObjectNode action() throws Exception {
    var action = (ObjectNode) JsonSupport.MAPPER.readTree(Files.readString(Path.of(System.getProperty("tracepermit.repositoryRoot"),
        "contracts/vectors/business-operation-v1.json"))).path("operation").path("canonicalAction").deepCopy();
    action.put("eventId", UUID.randomUUID().toString());
    var args = (ObjectNode) action.path("action").path("arguments"); args.removeAll();
    args.put("repository", REPO).put("head", HEAD).put("base", "main").put("draft", true).put("title", "Synthetic draft").put("body", "Synthetic body");
    ((ObjectNode) action.path("action").path("target")).put("accountId", REPO).put("resourceType", "pull_request"); return action;
  }
  private static ObjectNode evaluation(JsonNode action) {
    var value = JsonSupport.MAPPER.createObjectNode().put("schemaVersion", "1.0").put("evaluationId", "synthetic")
        .put("eventId", action.path("eventId").asText()).put("evaluatedAt", NOW.toString());
    value.putObject("decision").put("outcome", "allow").put("reasonCode", "SYNTHETIC_ALLOWED").put("policyId", "synthetic").put("policyVersion", 1); return value;
  }
  private String permit(JsonNode action) { return permits.issue(action, evaluation(action), GitHubDraftPullRequestAction.OPERATION); }
  private static String operationKey() { return GitHubDraftPullRequestAdapter.computeOperationKey(71, REPO, HEAD, "main"); }
  private static MockResponse response(int status, String body) { return new MockResponse().setResponseCode(status).setBody(body); }
}
