package io.tracepermit.adapters.remote.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.application.ExecutionReconciliationWorker;
import io.tracepermit.application.ReconciliationBatchResult;
import io.tracepermit.application.ReconciliationObservation;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.ExecutionAttempt;
import io.tracepermit.execution.ExecutionTransition;
import io.tracepermit.execution.InMemoryExecutionLedger;
import io.tracepermit.execution.ReconciliationCandidate;
import io.tracepermit.execution.TenantPartition;
import io.tracepermit.policy.PolicyDecision;
import io.tracepermit.security.ExecutionPermitAuthority;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class GitHubDraftPullRequestAdapterTest {
  private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final TenantPartition TENANT = new TenantPartition("tracepermit");
  private static final String TEST_REPO = "tracepermit/agentcore-pr-pilot";
  private static final String SECRET_TOKEN = "ghs_SUPER_SECRET_TOKEN_SENTINEL_XYZ_999";
  private static final String CREDENTIAL_FINGERPRINT =
      "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
  private static final long INSTALLATION_ID = 987654321L;

  private MockWebServer server;
  private InMemoryExecutionLedger ledger;
  private GitHubPullRequestOutcomeResolver resolver;
  private ExecutionPermitAuthority permitAuthority;
  private GitHubAppCredentialProvider credentialProvider;
  private GitHubDraftPullRequestAdapter adapter;

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.start();

    AtomicInteger attemptCounter = new AtomicInteger();
    ledger = new InMemoryExecutionLedger(() -> "attempt-" + attemptCounter.incrementAndGet());

    credentialProvider =
        repo ->
            new GitHubAppCredentials(
                INSTALLATION_ID, CREDENTIAL_FINGERPRINT, SECRET_TOKEN, NOW.plusSeconds(3600));

    resolver =
        new GitHubPullRequestOutcomeResolver(
            credentialProvider,
            server.url("/").uri(),
            Duration.ofSeconds(2));

    permitAuthority =
        new ExecutionPermitAuthority(
            ExecutionPermitAuthority.DEFAULT_ISSUER,
            "k-01",
            "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8),
            60,
            CLOCK,
            () -> "permit-" + UUID.randomUUID(),
            new io.tracepermit.security.InMemoryPermitConsumptionStore());

    adapter =
        new GitHubDraftPullRequestAdapter(
            credentialProvider,
            ledger,
            resolver,
            permitAuthority,
            "github.pull_request.create",
            server.url("/").uri(),
            Duration.ofMillis(500),
            Duration.ofMillis(800),
            65_536,
            CLOCK,
            null,
            null);
  }

  @AfterEach
  void tearDown() throws IOException {
    server.shutdown();
  }

  @Test
  void deterministicSuccess_createsDraftPullRequestAndReturnsEvidenceWithFingerprint()
      throws Exception {
    String githubResponse =
        """
        {
          "id": 42001,
          "number": 42,
          "state": "open",
          "draft": true,
          "html_url": "https://github.com/tracepermit/agentcore-pr-pilot/pull/42",
          "title": "AgentCore Draft PR",
          "head": {"ref": "feature/agent-task-1"},
          "base": {"ref": "main"}
        }
        """;
    server.enqueue(
        new MockResponse()
            .setResponseCode(201)
            .setHeader("Content-Type", "application/json")
            .setBody(githubResponse));

    JsonNode action =
        validCanonicalAction(
            "Draft PR: Update core task", "feature/agent-task-1", "main", true, "PR description");
    String permit = issuePermit(action);

    GitHubDraftPullRequestResult result = adapter.execute(action, permit);

    assertThat(result.number()).isEqualTo(42);
    assertThat(result.id()).isEqualTo(42001L);
    assertThat(result.htmlUrl())
        .isEqualTo("https://github.com/tracepermit/agentcore-pr-pilot/pull/42");
    assertThat(result.draft()).isTrue();
    assertThat(result.repository()).isEqualTo(TEST_REPO);
    assertThat(result.head()).isEqualTo("feature/agent-task-1");
    assertThat(result.base()).isEqualTo("main");
    assertThat(result.credentialFingerprint()).isEqualTo(CREDENTIAL_FINGERPRINT);
    assertThat(result.installationId()).isEqualTo(INSTALLATION_ID);

    // Verify evidence node has no secrets
    ObjectNode evidence = result.toJsonNode();
    assertThat(evidence.path("credentialFingerprint").asText()).isEqualTo(CREDENTIAL_FINGERPRINT);
    assertThat(evidence.path("installationId").asLong()).isEqualTo(INSTALLATION_ID);
    assertThat(evidence.toString()).doesNotContain(SECRET_TOKEN);

    // Verify outbound request sent to mock server
    assertThat(server.getRequestCount()).isEqualTo(1);
    RecordedRequest recorded = server.takeRequest(1, TimeUnit.SECONDS);
    assertThat(recorded).isNotNull();
    assertThat(recorded.getMethod()).isEqualTo("POST");
    assertThat(recorded.getPath()).isEqualTo("/repos/tracepermit/agentcore-pr-pilot/pulls");
    assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer " + SECRET_TOKEN);
    assertThat(recorded.getHeader("Accept")).isEqualTo("application/vnd.github+json");
    assertThat(recorded.getHeader("X-GitHub-Api-Version")).isEqualTo("2022-11-28");

    JsonNode sentBody =
        JsonSupport.MAPPER.readTree(recorded.getBody().readString(StandardCharsets.UTF_8));
    assertThat(sentBody.path("draft").asBoolean()).isTrue();
    assertThat(sentBody.path("base").asText()).isEqualTo("main");
    assertThat(sentBody.path("head").asText()).isEqualTo("feature/agent-task-1");
    assertThat(sentBody.path("title").asText()).isEqualTo("Draft PR: Update core task");
  }

  @Test
  void deny_missingOrInvalidPermit_failsClosedWithoutNetworkCall() {
    JsonNode action =
        validCanonicalAction("Title", "feature/agent-task-1", "main", true, "body");

    // Null permit
    assertThatThrownBy(() -> adapter.execute(action, null))
        .isInstanceOf(GitHubPullRequestException.class)
        .hasFieldOrPropertyWithValue("errorCode", "MISSING_PERMIT");

    // Blank permit
    assertThatThrownBy(() -> adapter.execute(action, ""))
        .isInstanceOf(GitHubPullRequestException.class)
        .hasFieldOrPropertyWithValue("errorCode", "MISSING_PERMIT");

    // Invalid permit string
    assertThatThrownBy(() -> adapter.execute(action, "invalid.permit.token"))
        .isInstanceOf(RuntimeException.class);

    assertThat(server.getRequestCount()).isEqualTo(0);
  }

  @Test
  void deny_hashMismatchPermit_failsClosedWithoutNetworkCall() {
    JsonNode action1 =
        validCanonicalAction("Title 1", "feature/agent-task-1", "main", true, "body 1");
    JsonNode action2 =
        validCanonicalAction("Title 2", "feature/agent-task-2", "main", true, "body 2");

    String permitForAction1 = issuePermit(action1);

    assertThatThrownBy(() -> adapter.execute(action2, permitForAction1))
        .isInstanceOf(io.tracepermit.security.ExecutionPermitException.class);

    assertThat(server.getRequestCount()).isEqualTo(0);
  }

  @Test
  void malformedInput_missingRequiredFields_failsClosedWithoutNetworkCall() {
    // Missing title
    JsonNode missingTitle =
        buildAction(
            TEST_REPO, "github.pull_request.create", "main", "feature/1", true, null, "body");
    assertThatThrownBy(() -> adapter.execute(missingTitle, "fake-permit"))
        .isInstanceOf(GitHubPullRequestException.class)
        .hasFieldOrPropertyWithValue("errorCode", "MISSING_TITLE");

    // Missing head branch
    JsonNode missingHead =
        buildAction(
            TEST_REPO, "github.pull_request.create", "main", null, true, "Title", "body");
    assertThatThrownBy(() -> adapter.execute(missingHead, "fake-permit"))
        .isInstanceOf(GitHubPullRequestException.class)
        .hasFieldOrPropertyWithValue("errorCode", "MISSING_HEAD_BRANCH");

    // Head equals base
    JsonNode headEqualsBase =
        buildAction(
            TEST_REPO, "github.pull_request.create", "main", "main", true, "Title", "body");
    assertThatThrownBy(() -> adapter.execute(headEqualsBase, "fake-permit"))
        .isInstanceOf(GitHubPullRequestException.class)
        .hasFieldOrPropertyWithValue("errorCode", "HEAD_EQUALS_BASE");

    // Invalid head branch ref
    JsonNode invalidHead =
        buildAction(
            TEST_REPO, "github.pull_request.create", "main", "-bad-ref..branch", true, "Title", "body");
    assertThatThrownBy(() -> adapter.execute(invalidHead, "fake-permit"))
        .isInstanceOf(GitHubPullRequestException.class)
        .hasFieldOrPropertyWithValue("errorCode", "INVALID_HEAD_BRANCH");

    assertThat(server.getRequestCount()).isEqualTo(0);
  }

  @Test
  void wrongRepo_failsClosedWithoutNetworkCall() {
    JsonNode wrongRepo =
        buildAction(
            "tracepermit/other-private-repo",
            "github.pull_request.create",
            "main",
            "feature/1",
            true,
            "Title",
            "body");

    assertThatThrownBy(() -> adapter.execute(wrongRepo, "fake-permit"))
        .isInstanceOf(GitHubPullRequestException.class)
        .hasFieldOrPropertyWithValue("errorCode", "REPOSITORY_NOT_ALLOWLISTED");

    assertThat(server.getRequestCount()).isEqualTo(0);
  }

  @Test
  void wrongBaseBranch_failsClosedWithoutNetworkCall() {
    JsonNode wrongBase =
        buildAction(
            TEST_REPO,
            "github.pull_request.create",
            "develop",
            "feature/1",
            true,
            "Title",
            "body");

    assertThatThrownBy(() -> adapter.execute(wrongBase, "fake-permit"))
        .isInstanceOf(GitHubPullRequestException.class)
        .hasFieldOrPropertyWithValue("errorCode", "BASE_BRANCH_NOT_ALLOWLISTED");

    assertThat(server.getRequestCount()).isEqualTo(0);
  }

  @Test
  void draftFalse_failsClosedWithoutNetworkCall() {
    JsonNode draftFalse =
        buildAction(
            TEST_REPO,
            "github.pull_request.create",
            "main",
            "feature/1",
            false,
            "Title",
            "body");

    assertThatThrownBy(() -> adapter.execute(draftFalse, "fake-permit"))
        .isInstanceOf(GitHubPullRequestException.class)
        .hasFieldOrPropertyWithValue("errorCode", "DRAFT_REQUIRED_TRUE");

    assertThat(server.getRequestCount()).isEqualTo(0);
  }

  @Test
  void unknownFieldsInArgumentsOrTarget_failClosedWithoutNetworkCall() {
    // Unknown field in arguments
    JsonNode extraArg =
        validCanonicalAction("Title", "feature/1", "main", true, "body");
    ((ObjectNode) extraArg.path("action").path("arguments"))
        .put("maintainer_can_modify", true);

    assertThatThrownBy(() -> adapter.execute(extraArg, "fake-permit"))
        .isInstanceOf(GitHubPullRequestException.class)
        .hasFieldOrPropertyWithValue("errorCode", "UNSUPPORTED_ARGUMENT");

    // Unknown field in target
    JsonNode extraTarget =
        validCanonicalAction("Title", "feature/1", "main", true, "body");
    ((ObjectNode) extraTarget.path("action").path("target"))
        .put("extraTargetField", "malicious");

    assertThatThrownBy(() -> adapter.execute(extraTarget, "fake-permit"))
        .isInstanceOf(GitHubPullRequestException.class)
        .hasFieldOrPropertyWithValue("errorCode", "UNSUPPORTED_TARGET_FIELD");

    assertThat(server.getRequestCount()).isEqualTo(0);
  }

  @Test
  void selfAssertedSecurityState_failsClosedWithoutNetworkCall() {
    JsonNode selfAsserted =
        validCanonicalAction("Title", "feature/1", "main", true, "body");
    ((ObjectNode) selfAsserted.path("action").path("arguments"))
        .put("authenticated", true);

    assertThatThrownBy(() -> adapter.execute(selfAsserted, "fake-permit"))
        .isInstanceOf(GitHubPullRequestException.class)
        .hasFieldOrPropertyWithValue("errorCode", "SELF_ASSERTION_FORBIDDEN");

    assertThat(server.getRequestCount()).isEqualTo(0);
  }

  @Test
  void redirect_isRejectedAndNotFollowed() {
    server.enqueue(
        new MockResponse()
            .setResponseCode(302)
            .setHeader("Location", "https://redirect.example.invalid/new-location"));

    JsonNode action = validCanonicalAction("Title", "feature/1", "main", true, "body");
    String permit = issuePermit(action);

    assertThatThrownBy(() -> adapter.execute(action, permit))
        .isInstanceOf(GitHubPullRequestException.class)
        .hasFieldOrPropertyWithValue("errorCode", "UNEXPECTED_REDIRECT");

    assertThat(server.getRequestCount()).isEqualTo(1);
  }

  @Test
  void retry_disabledOnConnectionFailure() {
    // Socket policy: disconnect abruptly
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));

    JsonNode action = validCanonicalAction("Title", "feature/1", "main", true, "body");
    String permit = issuePermit(action);

    assertThatThrownBy(() -> adapter.execute(action, permit))
        .isInstanceOf(GitHubPullRequestException.class)
        .hasFieldOrPropertyWithValue("errorCode", "OUTCOME_UNCERTAIN_IO_ERROR");

    // Exactly 1 attempt made (no automatic retries)
    assertThat(server.getRequestCount()).isEqualTo(1);
  }

  @Test
  void timeout_marksAttemptUnknownAndDoesNotRetry() {
    // Delay response past call timeout (adapter call timeout is 800ms)
    server.enqueue(
        new MockResponse()
            .setResponseCode(201)
            .setBodyDelay(1200, TimeUnit.MILLISECONDS)
            .setBody("{}"));

    JsonNode action = validCanonicalAction("Title", "feature/1", "main", true, "body");
    String permit = issuePermit(action);

    assertThatThrownBy(() -> adapter.execute(action, permit))
        .isInstanceOf(GitHubPullRequestException.class)
        .hasFieldOrPropertyWithValue("errorCode", "OUTCOME_UNCERTAIN_TIMEOUT");

    assertThat(server.getRequestCount()).isEqualTo(1);

    // Verify attempt is marked as unknown in ledger
    String operationKey =
        GitHubDraftPullRequestAdapter.computeOperationKey(
            INSTALLATION_ID, TEST_REPO, "feature/1", "main");
    ExecutionAttempt attempt = ledger.findByIdempotencyKey(operationKey);
    assertThat(attempt).isNotNull();
    assertThat(attempt.status()).isEqualTo("unknown");
    assertThat(attempt.transitions().get(attempt.transitions().size() - 1).reasonCode())
        .isEqualTo("OUTCOME_UNCERTAIN_TIMEOUT");
  }

  @Test
  void responseBound_oversizedPayloadRejected() {
    // 70 KB payload exceeds 65,536 bytes limit
    String largePayload = "{\"id\":1," + "\"padding\":\"" + "x".repeat(70_000) + "\"}";
    server.enqueue(new MockResponse().setResponseCode(201).setBody(largePayload));

    JsonNode action = validCanonicalAction("Title", "feature/1", "main", true, "body");
    String permit = issuePermit(action);

    assertThatThrownBy(() -> adapter.execute(action, permit))
        .isInstanceOf(GitHubPullRequestException.class)
        .hasFieldOrPropertyWithValue("errorCode", "RESPONSE_TOO_LARGE");

    String operationKey =
        GitHubDraftPullRequestAdapter.computeOperationKey(
            INSTALLATION_ID, TEST_REPO, "feature/1", "main");
    ExecutionAttempt attempt = ledger.findByIdempotencyKey(operationKey);
    assertThat(attempt).isNotNull();
    assertThat(attempt.status()).isEqualTo("failed");
  }

  @Test
  void duplicateAndConcurrency_blocksConcurrentAttemptsAndIdempotentlyReplaysSuccess() {
    String githubResponse =
        """
        {
          "id": 555,
          "number": 55,
          "state": "open",
          "draft": true,
          "html_url": "https://github.com/tracepermit/agentcore-pr-pilot/pull/55",
          "title": "Title",
          "head": {"ref": "feature/55"},
          "base": {"ref": "main"}
        }
        """;
    server.enqueue(new MockResponse().setResponseCode(201).setBody(githubResponse));

    JsonNode action = validCanonicalAction("Title", "feature/55", "main", true, "body");
    String permit = issuePermit(action);

    // First attempt succeeds
    GitHubDraftPullRequestResult first = adapter.execute(action, permit);
    assertThat(first.number()).isEqualTo(55);
    assertThat(server.getRequestCount()).isEqualTo(1);

    // Second attempt with same action & idempotency key: returns cached result without invoking server
    String permitReplay = issuePermit(action);
    GitHubDraftPullRequestResult replay = adapter.execute(action, permitReplay);
    assertThat(replay.number()).isEqualTo(55);
    assertThat(server.getRequestCount()).isEqualTo(1); // Server still only received 1 request!

    // Verify concurrent duplicate attempt (simulated by ledger in "executing" state)
    String operationKeyConcurrent =
        GitHubDraftPullRequestAdapter.computeOperationKey(
            INSTALLATION_ID, TEST_REPO, "feature/concurrent", "main");
    ledger.begin("evt-concurrent", "permit-c", operationKeyConcurrent, NOW);

    JsonNode concurrentAction =
        validCanonicalAction("Title", "feature/concurrent", "main", true, "body");
    String permitConcurrent = issuePermit(concurrentAction);
    assertThatThrownBy(() -> adapter.execute(concurrentAction, permitConcurrent))
        .isInstanceOf(GitHubPullRequestException.class)
        .hasFieldOrPropertyWithValue("errorCode", "CONCURRENT_EXECUTION_IN_PROGRESS");
  }

  @Test
  void uncertainOutcomeAndReconciliation_confirmsSuccessViaReadOnlyLookup() throws Exception {
    // 1. Initial POST times out
    server.enqueue(
        new MockResponse()
            .setResponseCode(201)
            .setBodyDelay(1200, TimeUnit.MILLISECONDS)
            .setBody("{}"));

    JsonNode action = validCanonicalAction("Title", "feature/timeout-1", "main", true, "body");
    String permit = issuePermit(action);

    assertThatThrownBy(() -> adapter.execute(action, permit))
        .isInstanceOf(GitHubPullRequestException.class)
        .hasFieldOrPropertyWithValue("errorCode", "OUTCOME_UNCERTAIN_TIMEOUT");

    String operationKey =
        GitHubDraftPullRequestAdapter.computeOperationKey(
            INSTALLATION_ID, TEST_REPO, "feature/timeout-1", "main");
    ExecutionAttempt attempt = ledger.findByIdempotencyKey(operationKey);
    assertThat(attempt.status()).isEqualTo("unknown");

    // 2. MockWebServer enqueues response for GET /repos/tracepermit/agentcore-pr-pilot/pulls
    String pullsListJson =
        """
        [
          {
            "id": 9991,
            "number": 99,
            "state": "open",
            "draft": true,
            "html_url": "https://github.com/tracepermit/agentcore-pr-pilot/pull/99",
            "head": {"ref": "feature/timeout-1"},
            "base": {"ref": "main"}
          }
        ]
        """;
    server.enqueue(new MockResponse().setResponseCode(200).setBody(pullsListJson));

    ReconciliationCandidate candidate =
        new ReconciliationCandidate(
            TENANT,
            attempt.attemptId(),
            "github.pull_request.create",
            operationKey,
            NOW,
            1,
            "worker-1",
            NOW.plusSeconds(30));

    ReconciliationObservation observation = resolver.lookup(candidate);
    assertThat(observation.confirmed()).isTrue();
    assertThat(observation.outcome()).isEqualTo("succeeded");
    assertThat(observation.reasonCode()).isEqualTo("PR_CONFIRMED_SUCCESS");

    // Check confirmed result stored in resolver
    GitHubDraftPullRequestResult details = resolver.getConfirmedResult(operationKey);
    assertThat(details).isNotNull();
    assertThat(details.number()).isEqualTo(99);

    // Verify request made to server was a GET lookup (read-only, never POST)
    RecordedRequest lookupRequest = server.takeRequest(1, TimeUnit.SECONDS); // First POST
    RecordedRequest secondRequest = server.takeRequest(1, TimeUnit.SECONDS); // GET lookup
    assertThat(secondRequest.getMethod()).isEqualTo("GET");
    assertThat(secondRequest.getPath())
        .contains("/repos/tracepermit/agentcore-pr-pilot/pulls?head=tracepermit%3Afeature%2Ftimeout-1&base=main&state=all");
  }

  @Test
  void uncertainOutcomeAndReconciliation_confirmsAbsenceViaReadOnlyLookup() {
    // 1. Initial POST times out
    server.enqueue(
        new MockResponse()
            .setResponseCode(201)
            .setBodyDelay(1200, TimeUnit.MILLISECONDS)
            .setBody("{}"));

    JsonNode action = validCanonicalAction("Title", "feature/timeout-absent", "main", true, "body");
    String permit = issuePermit(action);

    assertThatThrownBy(() -> adapter.execute(action, permit))
        .isInstanceOf(GitHubPullRequestException.class)
        .hasFieldOrPropertyWithValue("errorCode", "OUTCOME_UNCERTAIN_TIMEOUT");

    String operationKey =
        GitHubDraftPullRequestAdapter.computeOperationKey(
            INSTALLATION_ID, TEST_REPO, "feature/timeout-absent", "main");

    // 2. MockWebServer enqueues empty array for GET (PR does not exist)
    server.enqueue(new MockResponse().setResponseCode(200).setBody("[]"));

    ReconciliationCandidate candidate =
        new ReconciliationCandidate(
            TENANT,
            "attempt-absent",
            "github.pull_request.create",
            operationKey,
            NOW,
            1,
            "worker-1",
            NOW.plusSeconds(30));

    ReconciliationObservation observation = resolver.lookup(candidate);
    assertThat(observation.confirmed()).isTrue();
    assertThat(observation.outcome()).isEqualTo("failed");
    assertThat(observation.reasonCode()).isEqualTo("PR_CONFIRMED_ABSENT");
  }

  @Test
  void uncertainOutcomeAndReconciliation_lookupOutageRemainsUnknown() {
    // Lookup returns 500 error
    server.enqueue(new MockResponse().setResponseCode(500).setBody("Server Error"));

    resolver.registerOperation(
        "sha256:" + "f".repeat(64), TEST_REPO, "feature/err", "main", INSTALLATION_ID);

    ReconciliationCandidate candidate =
        new ReconciliationCandidate(
            TENANT,
            "attempt-err",
            "github.pull_request.create",
            "sha256:" + "f".repeat(64),
            NOW,
            1,
            "worker-1",
            NOW.plusSeconds(30));

    ReconciliationObservation observation = resolver.lookup(candidate);
    assertThat(observation.confirmed()).isFalse();
    assertThat(observation.outcome()).isEqualTo("unknown");
    assertThat(observation.reasonCode()).isEqualTo("TARGET_LOOKUP_FAILED");
  }

  @Test
  void secretRedaction_neverLeaksTokensInExceptionsEvidenceOrLogs() {
    // Server returns 401 Unauthorized
    server.enqueue(
        new MockResponse()
            .setResponseCode(401)
            .setBody("Bad credentials with token " + SECRET_TOKEN));

    JsonNode action = validCanonicalAction("Title", "feature/redaction", "main", true, "body");
    String permit = issuePermit(action);

    assertThatThrownBy(() -> adapter.execute(action, permit))
        .isInstanceOf(GitHubPullRequestException.class)
        .satisfies(
            ex -> {
              String msg = ex.getMessage();
              assertThat(msg).doesNotContain(SECRET_TOKEN);
            });

    // Verify credentials toString() masks the token
    GitHubAppCredentials credentials = credentialProvider.getInstallationCredentials(TEST_REPO);
    assertThat(credentials.toString()).doesNotContain(SECRET_TOKEN);
    assertThat(credentials.toString()).contains(CREDENTIAL_FINGERPRINT);
  }

  @Test
  void testCredentialsValidation() {
    assertThatThrownBy(
            () ->
                new GitHubAppCredentials(
                    0, CREDENTIAL_FINGERPRINT, SECRET_TOKEN, NOW.plusSeconds(3600)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive");
    assertThatThrownBy(
            () ->
                new GitHubAppCredentials(
                    -1, CREDENTIAL_FINGERPRINT, SECRET_TOKEN, NOW.plusSeconds(3600)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new GitHubAppCredentials(
                    1, "bad-hash", SECRET_TOKEN, NOW.plusSeconds(3600)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("SHA-256");
    assertThatThrownBy(
            () ->
                new GitHubAppCredentials(
                    1, CREDENTIAL_FINGERPRINT, "   ", NOW.plusSeconds(3600)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("blank");
  }

  @Test
  void testExceptionSanitizationAndGetters() {
    GitHubPullRequestException ex =
        new GitHubPullRequestException("GITHUB_HTTP_ERROR", "HTTP_500", "error message", null);
    assertThat(ex.errorCode()).isEqualTo("GITHUB_HTTP_ERROR");
    assertThat(ex.reasonCode()).isEqualTo("HTTP_500");
    assertThat(GitHubPullRequestException.sanitize(null)).contains("An error occurred");
    Throwable sanitizedCause =
        GitHubPullRequestException.sanitizeCause(
            new RuntimeException("failed with token " + SECRET_TOKEN));
    assertThat(sanitizedCause).isNotNull();
    assertThat(sanitizedCause.getMessage()).doesNotContain(SECRET_TOKEN);
  }

  @Test
  void testActionValidationEdgeCases() {
    // Missing action node
    ObjectNode noAction =
        (ObjectNode) validCanonicalAction("Title", "tracepermit:feat", "main", true, "body");
    noAction.remove("action");
    assertThatThrownBy(() -> GitHubDraftPullRequestAction.fromCanonical(noAction))
        .isInstanceOf(RuntimeException.class);

    // Unsupported toolName
    ObjectNode unsupportedTool =
        (ObjectNode) validCanonicalAction("Title", "tracepermit:feat", "main", true, "body");
    ((ObjectNode) unsupportedTool.path("action")).put("toolName", "github.pull_request.update");
    assertThatThrownBy(() -> GitHubDraftPullRequestAction.fromCanonical(unsupportedTool))
        .isInstanceOf(GitHubPullRequestException.class)
        .hasMessageContaining("not supported");

    // Invalid target system
    ObjectNode invalidSystem =
        (ObjectNode) validCanonicalAction("Title", "tracepermit:feat", "main", true, "body");
    ((ObjectNode) invalidSystem.path("action").path("target")).put("system", "gitlab");
    assertThatThrownBy(() -> GitHubDraftPullRequestAction.fromCanonical(invalidSystem))
        .isInstanceOf(GitHubPullRequestException.class)
        .hasMessageContaining("target system must be 'github'");

    // Blank head
    ObjectNode blankHead =
        (ObjectNode) validCanonicalAction("Title", "   ", "main", true, "body");
    assertThatThrownBy(() -> GitHubDraftPullRequestAction.fromCanonical(blankHead))
        .isInstanceOf(GitHubPullRequestException.class)
        .hasMessageContaining("head branch");

    // Blank title
    ObjectNode blankTitle =
        (ObjectNode) validCanonicalAction("   ", "feature/feat", "main", true, "body");
    assertThatThrownBy(() -> GitHubDraftPullRequestAction.fromCanonical(blankTitle))
        .isInstanceOf(GitHubPullRequestException.class)
        .hasMessageContaining("title is required");

    // Invalid head ref format
    ObjectNode invalidHead =
        (ObjectNode)
            validCanonicalAction(
                "Title", "-invalid_leading_dash", "main", true, "body");
    assertThatThrownBy(() -> GitHubDraftPullRequestAction.fromCanonical(invalidHead))
        .isInstanceOf(GitHubPullRequestException.class)
        .hasMessageContaining("format is invalid");

    // Oversized title (>256 chars)
    ObjectNode longTitle =
        (ObjectNode)
            validCanonicalAction(
                "a".repeat(257), "feature/feat", "main", true, "body");
    assertThatThrownBy(() -> GitHubDraftPullRequestAction.fromCanonical(longTitle))
        .isInstanceOf(GitHubPullRequestException.class)
        .hasMessageContaining("256 characters");

    // Oversized body (>65536 chars)
    ObjectNode longBody =
        (ObjectNode)
            validCanonicalAction(
                "Title", "feature/feat", "main", true, "b".repeat(65_537));
    assertThatThrownBy(() -> GitHubDraftPullRequestAction.fromCanonical(longBody))
        .isInstanceOf(GitHubPullRequestException.class)
        .hasMessageContaining("65,536 characters");

    // Action with arguments.owner and arguments.repo instead of target.accountId
    ObjectNode splitRepoAction =
        (ObjectNode) validCanonicalAction("Title", "feature/feat", "main", true, "body");
    ((ObjectNode) splitRepoAction.path("action").path("target")).remove("accountId");
    ((ObjectNode) splitRepoAction.path("action").path("arguments")).remove("repository");
    ((ObjectNode) splitRepoAction.path("action").path("arguments")).put("owner", "tracepermit");
    ((ObjectNode) splitRepoAction.path("action").path("arguments")).put("repo", "agentcore-pr-pilot");
    GitHubDraftPullRequestAction parsedSplit =
        GitHubDraftPullRequestAction.fromCanonical(splitRepoAction);
    assertThat(parsedSplit.repository()).isEqualTo(TEST_REPO);

    // Missing repository altogether
    ObjectNode noRepoAction =
        (ObjectNode) validCanonicalAction("Title", "feature/feat", "main", true, "body");
    ((ObjectNode) noRepoAction.path("action").path("target")).remove("accountId");
    ((ObjectNode) noRepoAction.path("action").path("arguments")).remove("repository");
    assertThatThrownBy(() -> GitHubDraftPullRequestAction.fromCanonical(noRepoAction))
        .isInstanceOf(GitHubPullRequestException.class)
        .hasMessageContaining("Target repository must be specified");
  }

  @Test
  void testAdapterConstructorAndTimeoutValidation() {
    // Default constructor
    GitHubDraftPullRequestAdapter defaultAdapter =
        new GitHubDraftPullRequestAdapter(
            credentialProvider, ledger, resolver, permitAuthority, "github.pull_request.create");
    assertThat(defaultAdapter.expectedAudience()).isEqualTo("github.pull_request.create");

    // Invalid maxResponseBytes
    assertThatThrownBy(
            () ->
                new GitHubDraftPullRequestAdapter(
                    credentialProvider,
                    ledger,
                    resolver,
                    permitAuthority,
                    "github.pull_request.create",
                    server.url("/").uri(),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1),
                    0,
                    CLOCK,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxResponseBytes");

    assertThatThrownBy(
            () ->
                new GitHubDraftPullRequestAdapter(
                    credentialProvider,
                    ledger,
                    resolver,
                    permitAuthority,
                    "github.pull_request.create",
                    server.url("/").uri(),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1),
                    2_000_000,
                    CLOCK,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxResponseBytes");

    // Invalid timeout bounds
    assertThatThrownBy(
            () ->
                new GitHubDraftPullRequestAdapter(
                    credentialProvider,
                    ledger,
                    resolver,
                    permitAuthority,
                    "github.pull_request.create",
                    server.url("/").uri(),
                    Duration.ZERO,
                    Duration.ofSeconds(1),
                    65_536,
                    CLOCK,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bounds");

    assertThatThrownBy(
            () ->
                new GitHubDraftPullRequestAdapter(
                    credentialProvider,
                    ledger,
                    resolver,
                    permitAuthority,
                    "github.pull_request.create",
                    server.url("/").uri(),
                    Duration.ofSeconds(11),
                    Duration.ofSeconds(1),
                    65_536,
                    CLOCK,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bounds");

    // Insecure non-loopback endpoint URL
    assertThatThrownBy(
            () ->
                new GitHubDraftPullRequestAdapter(
                    credentialProvider,
                    ledger,
                    resolver,
                    permitAuthority,
                    "github.pull_request.create",
                    java.net.URI.create("http://insecure.example.com/api/v3"),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1),
                    65_536,
                    CLOCK,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("HTTPS");
  }

  @Test
  void testAdapterHttpErrorResponses() {
    // Empty response body on 201
    JsonNode actionEmpty = validCanonicalAction("Title", "feature/err-empty", "main", true, "body");
    server.enqueue(new MockResponse().setResponseCode(201).setBody(""));
    assertThatThrownBy(() -> adapter.execute(actionEmpty, issuePermit(actionEmpty)))
        .isInstanceOf(GitHubPullRequestException.class)
        .hasMessageContaining("empty body");

    // HTTP 422 Unprocessable Entity
    JsonNode action422 = validCanonicalAction("Title", "feature/err-422", "main", true, "body");
    server.enqueue(
        new MockResponse()
            .setResponseCode(422)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"message\":\"Validation Failed\",\"errors\":[{\"message\":\"A pull request already"
                    + " exists\"}]}"));
    assertThatThrownBy(() -> adapter.execute(action422, issuePermit(action422)))
        .isInstanceOf(GitHubPullRequestException.class)
        .hasMessageContaining("rejected");

    // HTTP 404 Repository Not Found
    JsonNode action404 = validCanonicalAction("Title", "feature/err-404", "main", true, "body");
    server.enqueue(
        new MockResponse()
            .setResponseCode(404)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"message\":\"Not Found\"}"));
    assertThatThrownBy(() -> adapter.execute(action404, issuePermit(action404)))
        .isInstanceOf(GitHubPullRequestException.class)
        .hasMessageContaining("not found");

    // HTTP 500 Internal Server Error
    JsonNode action500 = validCanonicalAction("Title", "feature/err-500", "main", true, "body");
    server.enqueue(
        new MockResponse()
            .setResponseCode(500)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"message\":\"Internal Server Error\"}"));
    assertThatThrownBy(() -> adapter.execute(action500, issuePermit(action500)))
        .isInstanceOf(GitHubPullRequestException.class)
        .hasMessageContaining("HTTP 500");
  }

  @Test
  void testResolverEdgeCases() {
    // Unregistered idempotency key
    String missingKey = "sha256:" + "0".repeat(64);
    ReconciliationCandidate missingKeyCandidate =
        new ReconciliationCandidate(
            TENANT,
            "att-missing",
            "github.pull_request.create",
            missingKey,
            NOW,
            1,
            "worker-1",
            NOW.plusSeconds(30));
    ReconciliationObservation obs1 = resolver.lookup(missingKeyCandidate);
    assertThat(obs1.outcome()).isEqualTo("unknown");
    assertThat(obs1.reasonCode()).isEqualTo("LOOKUP_METADATA_UNAVAILABLE");

    // Malformed repository in registered candidate
    String badRepoKey = "sha256:" + "1".repeat(64);
    resolver.registerOperation(
        badRepoKey, "invalid-repo-without-slash", "feat", "main", INSTALLATION_ID);
    ReconciliationCandidate badRepoCandidate =
        new ReconciliationCandidate(
            TENANT,
            "att-1",
            "github.pull_request.create",
            badRepoKey,
            NOW,
            1,
            "worker-1",
            NOW.plusSeconds(30));
    ReconciliationObservation obs2 = resolver.lookup(badRepoCandidate);
    assertThat(obs2.outcome()).isEqualTo("failed");
    assertThat(obs2.reasonCode()).isEqualTo("MALFORMED_REPOSITORY");

    // Credential provider failure
    GitHubPullRequestOutcomeResolver failingAuthResolver =
        new GitHubPullRequestOutcomeResolver(
            repo -> {
              throw new RuntimeException("auth token failure");
            },
            server.url("/").uri(),
            Duration.ofSeconds(2));
    String authFailKey = "sha256:" + "2".repeat(64);
    failingAuthResolver.registerOperation(
        authFailKey, TEST_REPO, "feat", "main", INSTALLATION_ID);
    ReconciliationObservation obs3 =
        failingAuthResolver.lookup(
            new ReconciliationCandidate(
                TENANT,
                "att-2",
                "github.pull_request.create",
                authFailKey,
                NOW,
                1,
                "worker-1",
                NOW.plusSeconds(30)));
    assertThat(obs3.outcome()).isEqualTo("unknown");
    assertThat(obs3.reasonCode()).isEqualTo("TARGET_AUTH_UNAVAILABLE");

    // HTTP 404 on lookup
    String key404 = "sha256:" + "3".repeat(64);
    resolver.registerOperation(key404, TEST_REPO, "tracepermit:feat", "main", INSTALLATION_ID);
    server.enqueue(new MockResponse().setResponseCode(404));
    ReconciliationObservation obs4 =
        resolver.lookup(
            new ReconciliationCandidate(
                TENANT,
                "att-3",
                "github.pull_request.create",
                key404,
                NOW,
                1,
                "worker-1",
                NOW.plusSeconds(30)));
    assertThat(obs4.outcome()).isEqualTo("failed");
    assertThat(obs4.reasonCode()).isEqualTo("REPOSITORY_NOT_FOUND");

    // HTTP 401 on lookup
    String key401 = "sha256:" + "4".repeat(64);
    resolver.registerOperation(key401, TEST_REPO, "tracepermit:feat", "main", INSTALLATION_ID);
    server.enqueue(new MockResponse().setResponseCode(401));
    ReconciliationObservation obs5 =
        resolver.lookup(
            new ReconciliationCandidate(
                TENANT,
                "att-4",
                "github.pull_request.create",
                key401,
                NOW,
                1,
                "worker-1",
                NOW.plusSeconds(30)));
    assertThat(obs5.outcome()).isEqualTo("failed");
    assertThat(obs5.reasonCode()).isEqualTo("TARGET_AUTH_FAILED");

    // HTTP 302 Redirect on lookup
    String key302 = "sha256:" + "5".repeat(64);
    resolver.registerOperation(key302, TEST_REPO, "tracepermit:feat", "main", INSTALLATION_ID);
    server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "/redirect"));
    ReconciliationObservation obs6 =
        resolver.lookup(
            new ReconciliationCandidate(
                TENANT,
                "att-5",
                "github.pull_request.create",
                key302,
                NOW,
                1,
                "worker-1",
                NOW.plusSeconds(30)));
    assertThat(obs6.outcome()).isEqualTo("failed");
    assertThat(obs6.reasonCode()).isEqualTo("UNEXPECTED_REDIRECT");

    // Empty lookup response on 200
    String keyEmpty = "sha256:" + "6".repeat(64);
    resolver.registerOperation(keyEmpty, TEST_REPO, "tracepermit:feat", "main", INSTALLATION_ID);
    server.enqueue(new MockResponse().setResponseCode(200).setBody(""));
    ReconciliationObservation obs7 =
        resolver.lookup(
            new ReconciliationCandidate(
                TENANT,
                "att-6",
                "github.pull_request.create",
                keyEmpty,
                NOW,
                1,
                "worker-1",
                NOW.plusSeconds(30)));
    assertThat(obs7.outcome()).isEqualTo("unknown");
    assertThat(obs7.reasonCode()).isEqualTo("EMPTY_LOOKUP_RESPONSE");

    // Response too large on lookup
    String keyLarge = "sha256:" + "7".repeat(64);
    resolver.registerOperation(keyLarge, TEST_REPO, "tracepermit:feat", "main", INSTALLATION_ID);
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("[" + "{\"id\":1},".repeat(10000) + "{\"id\":99999}]"));
    ReconciliationObservation obs8 =
        resolver.lookup(
            new ReconciliationCandidate(
                TENANT,
                "att-7",
                "github.pull_request.create",
                keyLarge,
                NOW,
                1,
                "worker-1",
                NOW.plusSeconds(30)));
    assertThat(obs8.outcome()).isEqualTo("failed");
    assertThat(obs8.reasonCode()).isEqualTo("RESPONSE_TOO_LARGE");

    // Invalid JSON (not an array) on lookup
    String keyObj = "sha256:" + "8".repeat(64);
    resolver.registerOperation(keyObj, TEST_REPO, "tracepermit:feat", "main", INSTALLATION_ID);
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"message\":\"Not an array\"}"));
    ReconciliationObservation obs9 =
        resolver.lookup(
            new ReconciliationCandidate(
                TENANT,
                "att-8",
                "github.pull_request.create",
                keyObj,
                NOW,
                1,
                "worker-1",
                NOW.plusSeconds(30)));
    assertThat(obs9.outcome()).isEqualTo("unknown");
    assertThat(obs9.reasonCode()).isEqualTo("INVALID_LOOKUP_PAYLOAD");

    // Server error 500 on lookup
    String key500 = "sha256:" + "9".repeat(64);
    resolver.registerOperation(key500, TEST_REPO, "tracepermit:feat", "main", INSTALLATION_ID);
    server.enqueue(new MockResponse().setResponseCode(500));
    ReconciliationObservation obs10 =
        resolver.lookup(
            new ReconciliationCandidate(
                TENANT,
                "att-9",
                "github.pull_request.create",
                key500,
                NOW,
                1,
                "worker-1",
                NOW.plusSeconds(30)));
    assertThat(obs10.outcome()).isEqualTo("unknown");
    assertThat(obs10.reasonCode()).isEqualTo("TARGET_LOOKUP_FAILED");

    // Resolver constructor validation
    assertThatThrownBy(
            () ->
                new GitHubPullRequestOutcomeResolver(
                    credentialProvider,
                    server.url("/").uri(),
                    Duration.ofSeconds(1),
                    0,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new GitHubPullRequestOutcomeResolver(
                    credentialProvider,
                    server.url("/").uri(),
                    Duration.ofSeconds(1),
                    2_000_000,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new GitHubPullRequestOutcomeResolver(
                    credentialProvider,
                    server.url("/").uri(),
                    Duration.ZERO,
                    65_536,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private String issuePermit(JsonNode action) {
    ObjectNode evaluation = JsonSupport.MAPPER.createObjectNode();
    evaluation.put("schemaVersion", "1.0");
    evaluation.put("evaluationId", "eval-1");
    evaluation.put("eventId", action.path("eventId").asText());
    evaluation.put("evaluatedAt", NOW.toString());

    ObjectNode decision = evaluation.putObject("decision");
    decision.put("outcome", "allow");
    decision.put("reasonCode", "AGENTCORE_PILOT_ALLOWED");
    decision.put("policyId", "policy-pilot");
    decision.put("policyVersion", 1);

    return permitAuthority.issue(action, evaluation, "github.pull_request.create");
  }

  private static JsonNode validCanonicalAction(
      String title, String head, String base, boolean draft, String body) {
    return buildAction(
        TEST_REPO, "github.pull_request.create", base, head, draft, title, body);
  }

  private static JsonNode buildAction(
      String repository,
      String toolName,
      String base,
      String head,
      Boolean draft,
      String title,
      String body) {
    ObjectNode root = JsonSupport.MAPPER.createObjectNode();
    root.put("schemaVersion", "1.0");
    root.put("eventId", UUID.randomUUID().toString());
    root.put("observedAt", NOW.toString());
    root.put("traceId", "0123456789abcdef0123456789abcdef");

    // Identity
    ObjectNode identity = root.putObject("identity");
    ObjectNode subject = identity.putObject("subject");
    subject.put("id", "agentcore-workload-1");
    subject.put("organizationId", "tracepermit");
    subject.put("authenticationMethod", "workload_token");
    subject.put("assurance", "authenticated");
    subject.put("type", "workload");

    ArrayNode evidence = identity.putArray("evidence");
    ObjectNode ev = evidence.addObject();
    ev.put("evidenceId", "ev-1");
    ev.put("subjectId", "agentcore-workload-1");
    ev.put("kind", "workload_token");
    ev.put("assurance", "authenticated");
    ev.put("issuer", "https://agentcore.tracepermit.invalid");
    ev.put("verifiedAt", NOW.toString());
    ev.put("credentialFingerprint", CREDENTIAL_FINGERPRINT);

    // Trigger
    ObjectNode trigger = root.putObject("trigger");
    trigger.put("kind", "service");
    trigger.put("origin", "api");
    trigger.put("requestId", "req-1");

    // Action
    ObjectNode action = root.putObject("action");
    action.put("protocol", "http");
    action.put("toolName", toolName != null ? toolName : "github.pull_request.create");

    ObjectNode target = action.putObject("target");
    target.put("system", "github");
    target.put("environment", "test");
    if (repository != null) {
      target.put("accountId", repository);
    }
    target.put("resourceType", "pull_request");

    ObjectNode arguments = action.putObject("arguments");
    if (repository != null) {
      arguments.put("repository", repository);
    }
    if (title != null) {
      arguments.put("title", title);
    }
    if (head != null) {
      arguments.put("head", head);
    }
    if (base != null) {
      arguments.put("base", base);
    }
    if (draft != null) {
      arguments.put("draft", draft);
    }
    if (body != null) {
      arguments.put("body", body);
    }

    // Lineage
    ObjectNode lineage = root.putObject("lineage");
    lineage.put("rootPrincipalId", "agentcore-workload-1");
    ArrayNode hops = lineage.putArray("hops");
    ObjectNode hop = hops.addObject();
    hop.put("hopId", "hop-1");
    hop.put("subjectId", "agentcore-workload-1");
    hop.put("observedAt", NOW.toString());
    hop.put("kind", "principal");
    hop.put("assurance", "authenticated");
    hop.put("source", "workload_token");

    return root;
  }
}
