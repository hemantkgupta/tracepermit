package io.tracepermit.adapters.remote.github;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.tracepermit.adapters.remote.github.credentials.GitHubCredentialException;
import io.tracepermit.adapters.remote.github.credentials.GitHubInstallationConfiguration;
import io.tracepermit.adapters.remote.github.credentials.PilotHttpCredentialFixture;
import io.tracepermit.adapters.remote.github.credentials.PilotHttpCredentialFixture.Fault;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Test-only transport composition, with no config/key files or production CLI/network override. */
@Timeout(value = 15, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class PilotHttpCredentialCompositionTest {
  private static final GitHubInstallationConfiguration CONFIG = new GitHubInstallationConfiguration(11, 22, 33, 44,
      GitHubDraftPullRequestAction.ALLOWED_REPOSITORY, GitHubInstallationConfiguration.requiredPermissions());
  private static final GitHubPilotTargetBinding BINDING = new GitHubPilotTargetBinding(22, 33, 44,
      CONFIG.repository(), "synthetic/topic", "main", "a".repeat(40), "b".repeat(40));
  private enum TargetFault { NONE, HEAD_SHA, REDIRECT }

  @Test void signedRestrictedHttpExchangeFeedsExactlyFourGetPreflightAndAcknowledgedCleanup() throws Exception {
    var events = new CopyOnWriteArrayList<String>();
    try (var credential = new PilotHttpCredentialFixture(CONFIG, Fault.NONE, events::add);
        var target = new TargetFixture(credential, TargetFault.NONE, events)) {
      var outcome = observe(credential, target, events);
      assertThat(outcome.result()).isEqualTo(PilotOperatorMain.Result.PREFLIGHT_OBSERVED);
      assertThat(outcome.result().diagnostic()).isEqualTo("PREFLIGHT_OBSERVED_NON_AUTHORITATIVE");
      assertThat(outcome.code()).isEmpty(); assertThat(outcome.privateDiagnosticsDiscarded()).isTrue();
      assertThat(events).containsExactly("KEY_READ", "CREDENTIAL_OPEN", "CREDENTIAL_GET", "CREDENTIAL_POST",
          "CREDENTIAL_CLOSE_ACK", "TARGET_OPEN", "TARGET_INSTALLATION_GET", "TARGET_REPOSITORY_GET",
          "TARGET_HEAD_GET", "TARGET_BASE_GET", "TARGET_CLOSE_ACK", "RESULT");
      assertCredential(credential, 2); assertThat(credential.verifiedJwts()).isEqualTo(2);
      assertThat(credential.verifiedRestrictions()).isEqualTo(1);
      assertThat(target.requests.get()).isEqualTo(4); assertThat(target.authorized.get()).isEqualTo(4);
      assertThat(target.opens.get()).isEqualTo(1); assertThat(target.closes.get()).isEqualTo(1);
      assertThat(target.violations).isEmpty();
    }
  }

  @ParameterizedTest @EnumSource(value = Fault.class, names = "NONE", mode = EnumSource.Mode.EXCLUDE)
  void credentialTransportRefusalsNeverRetryMintOrContactTarget(Fault fault) throws Exception {
    var events = new CopyOnWriteArrayList<String>();
    try (var credential = new PilotHttpCredentialFixture(CONFIG, fault, events::add);
        var target = new TargetFixture(credential, TargetFault.NONE, events)) {
      var outcome = observe(credential, target, events);
      assertThat(outcome.result()).isEqualTo(PilotOperatorMain.Result.PREFLIGHT_INDETERMINATE);
      assertThat(outcome.result().diagnostic()).isEqualTo("PREFLIGHT_INDETERMINATE_NON_AUTHORITATIVE");
      assertThat(outcome.code()).isEqualTo(expectedCode(fault));
      assertThat(outcome.privateDiagnosticsDiscarded()).isTrue();
      assertThat(credential.leaks(outcome.result().diagnostic() + outcome.code())).isFalse();
      int expected = fault == Fault.INSTALLATION_ID ? 1 : 2;
      assertCredential(credential, expected); assertThat(credential.verifiedJwts()).isEqualTo(expected);
      assertThat(credential.verifiedRestrictions()).isEqualTo(expected - 1);
      assertThat(events.stream().filter("CREDENTIAL_POST"::equals).count()).isEqualTo(expected - 1);
      assertThat(events.getLast()).isEqualTo("RESULT");
      assertThat(target.requests.get()).isZero(); assertThat(target.opens.get()).isZero();
      assertThat(target.closes.get()).isZero(); assertThat(target.violations).isEmpty();
    }
  }

  @ParameterizedTest @EnumSource(value = TargetFault.class, names = "NONE", mode = EnumSource.Mode.EXCLUDE)
  void targetMismatchOrRedirectCannotTurnMintedCredentialIntoExecution(TargetFault fault) throws Exception {
    var events = new CopyOnWriteArrayList<String>();
    try (var credential = new PilotHttpCredentialFixture(CONFIG, Fault.NONE, events::add);
        var target = new TargetFixture(credential, fault, events)) {
      var outcome = observe(credential, target, events);
      assertThat(outcome.result()).isEqualTo(PilotOperatorMain.Result.PREFLIGHT_INDETERMINATE);
      assertThat(outcome.code()).isEqualTo("TARGET_PREFLIGHT_OR_IDENTITY_REJECTED");
      assertThat(outcome.privateDiagnosticsDiscarded()).isTrue(); assertCredential(credential, 2);
      assertThat(credential.verifiedRestrictions()).isEqualTo(1);
      assertThat(target.requests.get()).isEqualTo(fault == TargetFault.HEAD_SHA ? 3 : 1);
      assertThat(target.authorized.get()).isEqualTo(target.requests.get());
      assertThat(events.indexOf("CREDENTIAL_CLOSE_ACK")).isLessThan(events.indexOf("TARGET_OPEN"));
      assertThat(target.opens.get()).isEqualTo(1); assertThat(target.closes.get()).isEqualTo(1);
      assertThat(events.getLast()).isEqualTo("RESULT"); assertThat(target.violations).isEmpty();
    }
  }

  private static void assertCredential(PilotHttpCredentialFixture credential, int requests) {
    assertThat(credential.requests()).isEqualTo(requests); assertThat(credential.keyReads()).isEqualTo(1);
    assertThat(credential.opens()).isEqualTo(1); assertThat(credential.closeAcknowledgements()).isEqualTo(1);
    assertThat(credential.violations()).isEmpty();
  }
  private static String expectedCode(Fault fault) {
    return switch (fault) {
      case INSTALLATION_ID -> "INSTALLATION_REJECTED";
      case TOKEN_PERMISSIONS, TOKEN_REPOSITORIES -> "TOKEN_ASSURANCE_REJECTED";
      case TOKEN_REDIRECT, TOKEN_MALFORMED -> "RESPONSE_INVALID";
      // Retry-After:0 prompts an internal follow-up, refused by the real single-exchange guard.
      case TOKEN_SERVER_ERROR -> "TRANSPORT_UNAVAILABLE";
      case TOKEN_LOST_RESPONSE -> "TRANSPORT_UNAVAILABLE";
      case TOKEN_OVERSIZED -> "RESPONSE_TOO_LARGE";
      case CLOSE_FAILURE -> "CLEANUP_FAILED";
      case EXPIRES_AT_CLOSE -> "TOKEN_EXPIRY_REJECTED";
      case NONE -> throw new AssertionError("NEGATIVE_FAULT_REQUIRED");
    };
  }

  private record Outcome(PilotOperatorMain.Result result, String code, boolean privateDiagnosticsDiscarded) {}
  private static Outcome observe(PilotHttpCredentialFixture fixture, TargetFixture target, List<String> events) {
    // The shipped configuration/CLI path is separate coverage: this assembly never writes a private key.
    Outcome outcome;
    try {
      var credential = fixture.provider().getInstallationCredentials(CONFIG.repository());
      var transport = GitHubApiTransport.synthetic(target.origin(), Duration.ofSeconds(1), Duration.ofSeconds(3), 65_536);
      var session = transport.open(); target.opens.incrementAndGet(); events.add("TARGET_OPEN");
      try { GitHubTargetPreflight.verify(session, BINDING, credential, fixture.clock()); }
      finally { session.close(); target.closes.incrementAndGet(); events.add("TARGET_CLOSE_ACK"); }
      outcome = new Outcome(PilotOperatorMain.Result.PREFLIGHT_OBSERVED, "", true);
    } catch (GitHubCredentialException fixed) {
      outcome = new Outcome(PilotOperatorMain.Result.PREFLIGHT_INDETERMINATE, fixed.errorCode(),
          fixed.getCause() == null && fixed.getSuppressed().length == 0);
    } catch (GitHubPullRequestException fixed) {
      outcome = new Outcome(PilotOperatorMain.Result.PREFLIGHT_INDETERMINATE, fixed.errorCode(),
          fixed.getCause() == null && fixed.getSuppressed().length == 0);
    } catch (IOException unavailable) {
      outcome = new Outcome(PilotOperatorMain.Result.PREFLIGHT_INDETERMINATE, "TARGET_IO_UNAVAILABLE", true);
    }
    events.add("RESULT"); return outcome;
  }

  private static final class TargetFixture implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    final AtomicInteger requests = new AtomicInteger(), authorized = new AtomicInteger();
    final AtomicInteger opens = new AtomicInteger(), closes = new AtomicInteger();
    final List<String> violations = new CopyOnWriteArrayList<>();
    final HttpServer server;
    final ExecutorService executor;
    final PilotHttpCredentialFixture credential;
    final TargetFault fault;
    final List<String> events;
    TargetFixture(PilotHttpCredentialFixture credential, TargetFault fault, List<String> events) throws IOException {
      this.credential = credential; this.fault = fault; this.events = events;
      server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 0);
      executor = Executors.newSingleThreadExecutor(task -> { var thread = new Thread(task, "pilot-http-target-fixture"); thread.setDaemon(true); return thread; });
      server.setExecutor(executor); server.createContext("/", this::handle); server.start();
    }
    URI origin() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }
    private void handle(HttpExchange exchange) throws IOException {
      try (exchange) {
        requests.incrementAndGet(); String path = exchange.getRequestURI().getRawPath();
        String prefix = "/repos/tracepermit/agentcore-pr-pilot";
        boolean installation = path.equals("/installation/repositories");
        boolean repository = path.equals(prefix);
        boolean head = path.equals(prefix + "/git/ref/heads/synthetic/topic");
        boolean base = path.equals(prefix + "/git/ref/heads/main");
        if (!exchange.getRequestMethod().equals("GET") || !(installation || repository || head || base)) {
          violations.add("TARGET_ROUTE_REJECTED"); respond(exchange, 400, "TARGET_ROUTE_REJECTED"); return;
        }
        if (!credential.authorizes(exchange.getRequestHeaders().getFirst("Authorization"))) {
          violations.add("TARGET_TOKEN_NOT_FROM_EXCHANGE"); respond(exchange, 401, "TARGET_TOKEN_REJECTED"); return;
        }
        authorized.incrementAndGet();
        if (installation) {
          var url = okhttp3.HttpUrl.get(origin().resolve(exchange.getRequestURI()).toString());
          if (url.querySize() != 2 || !"100".equals(url.queryParameter("per_page")) || !"1".equals(url.queryParameter("page"))) {
            violations.add("TARGET_QUERY_REJECTED"); respond(exchange, 400, "TARGET_QUERY_REJECTED"); return;
          }
        } else if (exchange.getRequestURI().getRawQuery() != null) {
          violations.add("TARGET_QUERY_REJECTED"); respond(exchange, 400, "TARGET_QUERY_REJECTED"); return;
        }
        events.add(installation ? "TARGET_INSTALLATION_GET" : repository ? "TARGET_REPOSITORY_GET" : head ? "TARGET_HEAD_GET" : "TARGET_BASE_GET");
        if (fault == TargetFault.REDIRECT) {
          exchange.getResponseHeaders().set("Location", origin() + "/tripwire"); respond(exchange, 307, "SYNTHETIC_TARGET_REDIRECT"); return;
        }
        ObjectNode response;
        if (installation) { response = JSON.createObjectNode().put("total_count", 1); response.putArray("repositories").add(credential.repository()); }
        else if (repository) response = credential.repository();
        else {
          response = JSON.createObjectNode().put("ref", "refs/heads/" + (head ? BINDING.head() : BINDING.base()));
          response.putObject("object").put("type", "commit")
              .put("sha", head && fault == TargetFault.HEAD_SHA ? "c".repeat(40) : head ? BINDING.headSha() : BINDING.baseSha());
        }
        respond(exchange, 200, response.toString());
      } catch (IOException disconnected) { /* Owned fixture I/O only; never render credentials/headers/bodies. */ }
    }
    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
    }
    @Override public void close() {
      server.stop(0); executor.shutdownNow();
      try { if (!executor.awaitTermination(3, TimeUnit.SECONDS)) throw new AssertionError("TARGET_FIXTURE_CLEANUP_UNCONFIRMED"); }
      catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError("TARGET_FIXTURE_CLEANUP_INTERRUPTED"); }
    }
  }
}
