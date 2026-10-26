package io.tracepermit.adapters.remote.github;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.adapters.remote.github.credentials.PilotCredentialFixture;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PilotPreflightTest {
  @TempDir Path root;
  private PilotTestFiles files;
  private PilotConfiguration config;
  private PilotCredentialFixture credential;
  private final Clock clock = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);
  @BeforeEach void setup() throws Exception {
    files = new PilotTestFiles(root); config = PilotConfiguration.read(files.config);
    credential = new PilotCredentialFixture(config.installation(), clock);
  }

  @Test void realConcreteProviderAndExactPreflightPerformOnlyFourTargetGets() throws Exception {
    var requests = new ArrayList<String>();
    try (var server = server("none", requests)) {
      assertThat(run(server)).isEqualTo(PilotOperatorMain.Result.PREFLIGHT_OBSERVED);
      var query = okhttp3.HttpUrl.get("http://127.0.0.1" + requests.getFirst().substring(4));
      assertThat(query.queryParameterNames()).containsExactlyInAnyOrder("per_page", "page");
      assertThat(query.queryParameter("per_page")).isEqualTo("100");
      assertThat(query.queryParameter("page")).isEqualTo("1");
      assertThat(query.querySize()).isEqualTo(2);
      assertThat(requests.stream().map(value -> value.split("\\?")[0]).toList()).containsExactly("GET /installation/repositories",
          "GET /repos/tracepermit/agentcore-pr-pilot", "GET /repos/tracepermit/agentcore-pr-pilot/git/ref/heads/synthetic/topic",
          "GET /repos/tracepermit/agentcore-pr-pilot/git/ref/heads/main");
      assertThat(credential.calls).containsExactly("KEY", "OPEN", "GET_INSTALLATION", "POST_TOKEN", "CLOSE");
      assertThat(server.getRequestCount()).isEqualTo(4);
    }
  }

  @ParameterizedTest @ValueSource(strings = {"head-sha", "base-sha", "repository-id", "owner-id", "repository-name",
      "private", "archived", "fork", "disabled", "selection", "pagination", "redirect", "denied", "malformed", "oversized"})
  void exactPreflightRejectsMismatchWithoutRetryOrPost(String fault) throws Exception {
    var requests = new ArrayList<String>();
    try (var server = server(fault, requests)) {
      var result = run(server);
      assertThat(result).isEqualTo(PilotOperatorMain.Result.PREFLIGHT_INDETERMINATE);
      assertThat(result.diagnostic()).isEqualTo("PREFLIGHT_INDETERMINATE_NON_AUTHORITATIVE");
      assertThat(credential.leaks(result.diagnostic())).isFalse();
      assertThat(requests).allMatch(value -> value.startsWith("GET ") && !value.contains("pulls"));
      assertThat(requests.size()).isBetween(1, 4);
      assertThat(credential.calls).containsExactly("KEY", "OPEN", "GET_INSTALLATION", "POST_TOKEN", "CLOSE");
    }
  }

  @ParameterizedTest @ValueSource(strings = {"app", "installation", "permissions", "token-repository", "expiry", "cleanup", "lost-token-response"})
  void credentialAssuranceAndCleanupFailureNeverOpenTarget(String fault) throws Exception {
    switch (fault) {
      case "app" -> credential.changeInstallation = node -> node.put("app_id", 999);
      case "installation" -> credential.changeInstallation = node -> node.put("id", 999);
      case "permissions" -> credential.changeToken = node -> {
        node.withObject("/permissions").put("contents", "write"); return node;
      };
      case "token-repository" -> credential.changeToken = node -> {
        ((ObjectNode) node.path("repositories").get(0)).put("id", 999); return node;
      };
      case "expiry" -> credential.changeToken = node -> node.put("expires_at", clock.instant().toString());
      case "lost-token-response" -> credential.changeToken = node -> { throw sensitiveFailure(); };
      case "cleanup" -> credential.cleanup = () -> { throw sensitiveFailure(); };
      default -> throw new AssertionError("UNKNOWN_FIXTURE");
    }
    var opens = new AtomicInteger();
    var result = PilotOperatorMain.bounded(new String[]{"--preflight"}, files.config.toString(), ignored ->
        PilotReadOnlyPreflight.localFixture(credential.provider, () -> {
          opens.incrementAndGet(); throw new AssertionError("TARGET_OPENED_AFTER_CREDENTIAL_REFUSAL");
        }, clock), Duration.ofSeconds(5));
    assertThat(result).isEqualTo(PilotOperatorMain.Result.PREFLIGHT_INDETERMINATE);
    assertThat(opens).hasValue(0); assertThat(credential.calls.getLast()).isEqualTo("CLOSE");
    assertThat(credential.calls.stream().filter("OPEN"::equals).count()).isEqualTo(1);
    assertThat(credential.calls.stream().filter("POST_TOKEN"::equals).count()).isLessThanOrEqualTo(1);
    assertThat(result.diagnostic()).doesNotContain(PilotTestFiles.CANARY);
  }

  @Test void targetCloseFailureDiscardsObservationAndAllCauses() {
    var verified = new AtomicInteger(); var closed = new AtomicInteger();
    var result = PilotOperatorMain.bounded(new String[]{"--preflight"}, files.config.toString(), ignored ->
        PilotReadOnlyPreflight.localFixture(credential.provider, () -> new PilotReadOnlyPreflight.Session() {
          @Override public void verify(GitHubPilotTargetBinding binding, GitHubAppCredentials credentials) { verified.incrementAndGet(); }
          @Override public void close() { closed.incrementAndGet(); throw sensitiveFailure(); }
        }, clock), Duration.ofSeconds(5));
    assertThat(result).isEqualTo(PilotOperatorMain.Result.PREFLIGHT_INDETERMINATE);
    assertThat(verified).hasValue(1); assertThat(closed).hasValue(1);
    assertThat(result.diagnostic()).doesNotContain(PilotTestFiles.CANARY, "Exception", "suppressed");
  }

  @Test void credentialsExpiringDuringCleanupDoNotProduceObservation() {
    var now = new java.util.concurrent.atomic.AtomicReference<>(clock.instant());
    Clock mutable = new Clock() {
      @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
      @Override public Clock withZone(java.time.ZoneId zone) { return this; }
      @Override public Instant instant() { return now.get(); }
    };
    var result = PilotOperatorMain.bounded(new String[]{"--preflight"}, files.config.toString(), ignored ->
        PilotReadOnlyPreflight.localFixture(credential.provider, () -> new PilotReadOnlyPreflight.Session() {
          @Override public void verify(GitHubPilotTargetBinding binding, GitHubAppCredentials credentials) { /* synthetic observation */ }
          @Override public void close() { now.set(clock.instant().plusSeconds(60)); }
        }, mutable), Duration.ofSeconds(5));
    assertThat(result).isEqualTo(PilotOperatorMain.Result.PREFLIGHT_INDETERMINATE);
  }

  @Test void deadlineIsBoundedAndCancelsTheSingleAttempt() throws Exception {
    var entered = new CountDownLatch(1); var interrupted = new CountDownLatch(1);
    long started = System.nanoTime();
    var result = PilotOperatorMain.bounded(new String[]{"--preflight"}, files.config.toString(), ignored -> {
      entered.countDown();
      try { new CountDownLatch(1).await(); }
      catch (InterruptedException expected) { interrupted.countDown(); Thread.currentThread().interrupt(); }
      throw sensitiveFailure();
    }, Duration.ofMillis(500));
    assertThat(result).isEqualTo(PilotOperatorMain.Result.DEADLINE_EXCEEDED);
    assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue(); assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
  }

  @Test void interruptedCallerGetsCancellationAndPreservesInterrupt() throws Exception {
    var result = new java.util.concurrent.atomic.AtomicReference<PilotOperatorMain.Result>();
    var retained = new java.util.concurrent.atomic.AtomicBoolean();
    var opened = new AtomicInteger();
    var caller = new Thread(() -> {
      Thread.currentThread().interrupt();
      result.set(PilotOperatorMain.bounded(new String[]{"--preflight"}, files.config.toString(), ignored -> {
        opened.incrementAndGet();
        throw sensitiveFailure();
      }, Duration.ofSeconds(5)));
      retained.set(Thread.currentThread().isInterrupted());
    });
    caller.start(); caller.join(3000); assertThat(caller.isAlive()).isFalse();
    assertThat(result.get()).isEqualTo(PilotOperatorMain.Result.CANCELLED); assertThat(retained).isTrue();
    assertThat(opened).hasValue(0);
  }

  @Test void interruptionDuringPreflightCancelsWorkerAndPreservesCallerInterrupt() throws Exception {
    var entered = new CountDownLatch(1);
    var stopped = new CountDownLatch(1);
    var result = new java.util.concurrent.atomic.AtomicReference<PilotOperatorMain.Result>();
    var retained = new java.util.concurrent.atomic.AtomicBoolean();
    var caller = new Thread(() -> {
      result.set(PilotOperatorMain.bounded(new String[]{"--preflight"}, files.config.toString(), ignored -> {
        entered.countDown();
        try { new CountDownLatch(1).await(); }
        catch (InterruptedException expected) { stopped.countDown(); Thread.currentThread().interrupt(); }
        throw sensitiveFailure();
      }, Duration.ofSeconds(5)));
      retained.set(Thread.currentThread().isInterrupted());
    });
    caller.start();
    try {
      assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
      caller.interrupt(); caller.join(3000);
      assertThat(caller.isAlive()).isFalse();
      assertThat(result.get()).isEqualTo(PilotOperatorMain.Result.CANCELLED);
      assertThat(retained).isTrue();
      assertThat(stopped.await(3, TimeUnit.SECONDS)).isTrue();
    } finally { caller.interrupt(); caller.join(3000); }
  }

  private PilotOperatorMain.Result run(MockWebServer server) {
    return PilotOperatorMain.bounded(new String[]{"--preflight"}, files.config.toString(), ignored -> {
      var transport = GitHubApiTransport.synthetic(server.url("/").uri(), Duration.ofSeconds(1), Duration.ofSeconds(3), 65_536);
      return PilotReadOnlyPreflight.compose(credential.provider, transport::open, clock);
    }, Duration.ofSeconds(5));
  }

  private MockWebServer server(String fault, List<String> requests) throws Exception {
    var server = new MockWebServer();
    server.setDispatcher(new Dispatcher() {
      @Override public MockResponse dispatch(RecordedRequest request) {
        String path = request.getUrl().encodedPath();
        requests.add(request.getMethod() + " " + request.getTarget());
        if (!"GET".equals(request.getMethod()) || !credential.authorizes(request.getHeaders().get("Authorization"))) {
          return new MockResponse.Builder().code(500).body("SYNTHETIC_UNEXPECTED_REQUEST").build();
        }
        var repo = credential.repository();
        switch (fault) {
          case "repository-id" -> repo.put("id", 999);
          case "owner-id" -> repo.withObject("/owner").put("id", 999);
          case "repository-name" -> repo.put("full_name", "synthetic/other");
          case "private" -> repo.put("private", false);
          case "archived" -> repo.put("archived", true);
          case "fork" -> repo.put("fork", true);
          case "disabled" -> repo.put("disabled", true);
          default -> { /* normal repository */ }
        }
        ObjectNode response;
        if (path.equals("/installation/repositories")) {
          response = PilotTestFiles.JSON.createObjectNode().put("total_count", fault.equals("selection") ? 2 : 1);
          response.putArray("repositories").add(repo);
        } else if (path.equals("/repos/tracepermit/agentcore-pr-pilot")) response = repo;
        else {
          boolean head = path.endsWith("synthetic/topic");
          response = PilotTestFiles.JSON.createObjectNode().put("ref", "refs/heads/" + (head ? "synthetic/topic" : "main"));
          response.putObject("object").put("type", "commit").put("sha",
              fault.equals(head ? "head-sha" : "base-sha") ? "c".repeat(40) : (head ? "a" : "b").repeat(40));
        }
        var builder = new MockResponse.Builder().code(200).body(response.toString());
        switch (fault) {
          case "pagination" -> builder.addHeader("Link", "SYNTHETIC_NEXT_PAGE");
          case "redirect" -> builder.code(302).addHeader("Location", "https://synthetic.invalid/");
          case "denied" -> builder.code(403).body(PilotTestFiles.CANARY);
          case "malformed" -> builder.body("{" + PilotTestFiles.CANARY);
          case "oversized" -> builder.body("x".repeat(65_537));
          default -> { /* normal response */ }
        }
        return builder.build();
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0); return server;
  }

  private static RuntimeException sensitiveFailure() {
    var failure = new IllegalStateException(PilotTestFiles.CANARY, new RuntimeException(PilotTestFiles.CANARY));
    failure.addSuppressed(new RuntimeException(PilotTestFiles.CANARY)); return failure;
  }
}
