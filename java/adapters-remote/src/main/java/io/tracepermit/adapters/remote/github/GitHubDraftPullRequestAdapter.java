package io.tracepermit.adapters.remote.github;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.BeginExecutionResult;
import io.tracepermit.execution.InMemoryExecutionLedger;
import io.tracepermit.security.ExecutionPermitAuthority;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Bounded Java adapter for the AgentCore pilot operation: github.pull_request.create.
 *
 * <p>Enforces strict canonical validation, allowlists (repository tracepermit/agentcore-pr-pilot,
 * base branch main, draft=true), bounded timeouts, NO_PROXY, no redirects, no retries, response size
 * limits, and credential-safe redaction. Separates policy admission from provider execution and treats
 * ambiguous timeouts/disconnects as UNKNOWN requiring read-only reconciliation.
 */
public final class GitHubDraftPullRequestAdapter {
  public static final URI PRODUCTION_GITHUB_API_URI = URI.create("https://api.github.com");
  public static final int DEFAULT_MAX_RESPONSE_BYTES = 65_536;
  public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
  public static final Duration DEFAULT_CALL_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration MAXIMUM_TIMEOUT = Duration.ofSeconds(10);
  private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

  private final GitHubAppCredentialProvider credentialProvider;
  private final InMemoryExecutionLedger executionLedger;
  private final GitHubPullRequestOutcomeResolver outcomeResolver;
  private final ExecutionPermitAuthority permitAuthority;
  private final String expectedAudience;
  private final URI baseUri;
  private final Duration connectTimeout;
  private final Duration callTimeout;
  private final int maxResponseBytes;
  private final Clock clock;
  private final SSLSocketFactory sslSocketFactory;
  private final X509TrustManager trustManager;

  public GitHubDraftPullRequestAdapter(
      GitHubAppCredentialProvider credentialProvider,
      InMemoryExecutionLedger executionLedger,
      GitHubPullRequestOutcomeResolver outcomeResolver,
      ExecutionPermitAuthority permitAuthority,
      String expectedAudience) {
    this(
        credentialProvider,
        executionLedger,
        outcomeResolver,
        permitAuthority,
        expectedAudience,
        PRODUCTION_GITHUB_API_URI,
        DEFAULT_CONNECT_TIMEOUT,
        DEFAULT_CALL_TIMEOUT,
        DEFAULT_MAX_RESPONSE_BYTES,
        Clock.systemUTC(),
        null,
        null);
  }

  public GitHubDraftPullRequestAdapter(
      GitHubAppCredentialProvider credentialProvider,
      InMemoryExecutionLedger executionLedger,
      GitHubPullRequestOutcomeResolver outcomeResolver,
      ExecutionPermitAuthority permitAuthority,
      String expectedAudience,
      URI baseUri,
      Duration connectTimeout,
      Duration callTimeout,
      int maxResponseBytes,
      Clock clock,
      SSLSocketFactory sslSocketFactory,
      X509TrustManager trustManager) {
    this.credentialProvider =
        Objects.requireNonNull(credentialProvider, "credentialProvider must not be null");
    this.executionLedger =
        Objects.requireNonNull(executionLedger, "executionLedger must not be null");
    this.outcomeResolver =
        Objects.requireNonNull(outcomeResolver, "outcomeResolver must not be null");
    this.permitAuthority = permitAuthority; // optional if authority verification happens upstream
    this.expectedAudience = expectedAudience;
    this.baseUri = Objects.requireNonNull(baseUri, "baseUri must not be null");
    if (baseUri.getScheme() == null
        || (!"https".equalsIgnoreCase(baseUri.getScheme())
            && !"127.0.0.1".equals(baseUri.getHost())
            && !"localhost".equals(baseUri.getHost()))) {
      throw new IllegalArgumentException("Insecure non-HTTPS endpoint requested: " + baseUri);
    }
    this.connectTimeout = boundedTimeout(connectTimeout, "connectTimeout");
    this.callTimeout = boundedTimeout(callTimeout, "callTimeout");
    if (maxResponseBytes <= 0 || maxResponseBytes > 1_048_576) {
      throw new IllegalArgumentException("maxResponseBytes is outside safe limits.");
    }
    this.maxResponseBytes = maxResponseBytes;
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.sslSocketFactory = sslSocketFactory;
    this.trustManager = trustManager;
  }

  public String expectedAudience() {
    return expectedAudience;
  }

  /**
   * Computes the stable logical operation key for creating a pull request.
   * Bound to installation ID, repository, head branch, and base branch.
   */
  public static String computeOperationKey(
      long installationId, String repository, String head, String base) {
    Objects.requireNonNull(repository, "repository must not be null");
    Objects.requireNonNull(head, "head must not be null");
    Objects.requireNonNull(base, "base must not be null");
    String material =
        "github.pull_request.create:"
            + installationId
            + ":"
            + repository.toLowerCase(Locale.ROOT).trim()
            + ":"
            + head.trim()
            + ":"
            + base.trim();
    try {
      byte[] hashBytes = MessageDigest.getInstance("SHA-256")
          .digest(material.getBytes(StandardCharsets.UTF_8));
      return "sha256:" + HexFormat.of().formatHex(hashBytes);
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 digest unavailable", failure);
    }
  }

  /**
   * Validates the canonical action, verifies/consumes the permit, claims the logical attempt,
   * dispatches the bounded HTTP request, and records secret-free evidence.
   */
  public GitHubDraftPullRequestResult execute(JsonNode canonicalAction, String permitToken) {
    Objects.requireNonNull(canonicalAction, "canonicalAction must not be null");

    // 1. Strict canonical validation
    GitHubDraftPullRequestAction action = GitHubDraftPullRequestAction.fromCanonical(canonicalAction);

    // 2. Separate Policy Admission / Permit Verification
    if (permitAuthority != null) {
      if (permitToken == null || permitToken.isBlank()) {
        throw new GitHubPullRequestException(
            "MISSING_PERMIT", "NOT_AUTHORIZED", "A valid execution permit token is required.");
      }
      permitAuthority.verifyAndConsume(permitToken, canonicalAction, expectedAudience);
    }

    // 3. Resolve installation credentials from trusted provider (SEC-001 / SEC-003)
    GitHubAppCredentials credentials =
        credentialProvider.getInstallationCredentials(action.repository());

    // 4. Stable Operation Key and Process Ledger Check (ADR 0005, ADR 0020)
    String operationKey =
        computeOperationKey(
            credentials.installationId(), action.repository(), action.head(), action.base());
    Instant now = clock.instant();
    BeginExecutionResult begin =
        executionLedger.begin(
            action.eventId(), permitToken != null ? permitToken : action.eventId(), operationKey, now);

    if (!begin.started()) {
      var attempt = begin.attempt();
      String status = attempt.status();
      if ("succeeded".equals(status)) {
        // Idempotent return of cached result
        JsonNode resultJson = attempt.result();
        if (resultJson != null && resultJson.has("number")) {
          return new GitHubDraftPullRequestResult(
              resultJson.path("number").asInt(),
              resultJson.path("id").asLong(),
              resultJson.path("htmlUrl").asText(),
              true,
              resultJson.path("state").asText("open"),
              action.repository(),
              action.head(),
              action.base(),
              operationKey,
              credentials.credentialFingerprint(),
              credentials.installationId());
        }
      } else if ("executing".equals(status)) {
        throw new GitHubPullRequestException(
            "CONCURRENT_EXECUTION_IN_PROGRESS",
            "CONCURRENT_CLAIM",
            "An execution attempt for this pull request operation is already in progress.");
      } else if ("unknown".equals(status)) {
        throw new GitHubPullRequestException(
            "EXECUTION_OUTCOME_UNKNOWN",
            "OUTCOME_UNCERTAIN",
            "Previous attempt outcome is uncertain and awaiting reconciliation lookup.");
      } else if ("failed".equals(status)) {
        throw new GitHubPullRequestException(
            "PREVIOUS_EXECUTION_FAILED",
            "PREVIOUS_ATTEMPT_FAILED",
            "Previous execution attempt failed and will not be re-executed.");
      }
    }

    // Register operation with reconciliation resolver so uncertain outcomes can be looked up
    outcomeResolver.registerOperation(
        operationKey, action.repository(), action.head(), action.base(), credentials.installationId());

    // 5. Provider Execution via Bounded HTTP Client
    String[] parts = action.repository().split("/", 2);
    String owner = parts[0];
    String repo = parts[1];

    OkHttpClient.Builder clientBuilder =
        new OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(connectTimeout)
            .readTimeout(callTimeout)
            .callTimeout(callTimeout);
    if (sslSocketFactory != null && trustManager != null) {
      clientBuilder.sslSocketFactory(sslSocketFactory, trustManager);
    }
    OkHttpClient client = clientBuilder.build();

    var payload = JsonSupport.MAPPER.createObjectNode();
    payload.put("title", action.title());
    payload.put("head", action.head());
    payload.put("base", action.base());
    payload.put("draft", true);
    if (action.body() != null && !action.body().isBlank()) {
      payload.put("body", action.body());
    }

    HttpUrl url =
        HttpUrl.get(baseUri)
            .newBuilder()
            .addPathSegment("repos")
            .addPathSegment(owner)
            .addPathSegment(repo)
            .addPathSegment("pulls")
            .build();

    Request request =
        new Request.Builder()
            .url(url)
            .header("Authorization", "Bearer " + credentials.token())
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "TracePermit-AgentCore-Pilot")
            .post(RequestBody.create(payload.toString(), JSON_MEDIA_TYPE))
            .build();

    try (Response response = client.newCall(request).execute()) {
      int code = response.code();

      if (code == 201) {
        ResponseBody responseBody = response.body();
        if (responseBody == null) {
          executionLedger.markUnknown(begin.attempt().attemptId(), clock.instant(), "EMPTY_RESPONSE_BODY");
          throw new GitHubPullRequestException(
              "OUTCOME_UNCERTAIN", "OUTCOME_UNCERTAIN", "GitHub returned HTTP 201 without a body.");
        }
        byte[] responseBytes =
            BoundedGitHubResponse.read(responseBody.byteStream(), maxResponseBytes);
        if (responseBytes.length == 0) {
          executionLedger.markUnknown(begin.attempt().attemptId(), clock.instant(), "EMPTY_RESPONSE_BODY");
          throw new GitHubPullRequestException(
              "OUTCOME_UNCERTAIN", "OUTCOME_UNCERTAIN", "GitHub returned HTTP 201 with an empty body.");
        }
        if (responseBytes.length > maxResponseBytes) {
          // HTTP 201 may already represent a committed effect; an unreadable confirmation is not
          // proof of failure. Keep the attempt blocked pending read-only reconciliation.
          executionLedger.markUnknown(
              begin.attempt().attemptId(), clock.instant(), "RESPONSE_TOO_LARGE");
          throw new GitHubPullRequestException(
              "RESPONSE_TOO_LARGE",
              "BOUNDS_EXCEEDED",
              "GitHub response size exceeded limit of " + maxResponseBytes + " bytes.");
        }

        JsonNode responseJson =
            JsonSupport.MAPPER.readTree(new String(responseBytes, StandardCharsets.UTF_8));
        int number = responseJson.path("number").asInt();
        long id = responseJson.path("id").asLong();
        String htmlUrl = responseJson.path("html_url").asText();
        boolean draft = responseJson.path("draft").asBoolean(true);
        String state = responseJson.path("state").asText("open");

        GitHubDraftPullRequestResult result =
            new GitHubDraftPullRequestResult(
                number,
                id,
                htmlUrl,
                draft,
                state,
                action.repository(),
                action.head(),
                action.base(),
                operationKey,
                credentials.credentialFingerprint(),
                credentials.installationId());

        executionLedger.succeed(begin.attempt().attemptId(), clock.instant(), result.toJsonNode());
        return result;
      }

      if (code >= 300 && code < 400) {
        executionLedger.fail(begin.attempt().attemptId(), clock.instant(), "UNEXPECTED_REDIRECT");
        throw new GitHubPullRequestException(
            "UNEXPECTED_REDIRECT",
            "REDIRECT_REJECTED",
            "GitHub returned unexpected redirect status: " + code);
      }

      if (code == 422) {
        executionLedger.fail(begin.attempt().attemptId(), clock.instant(), "PR_CREATION_REJECTED");
        throw new GitHubPullRequestException(
            "PR_CREATION_REJECTED",
            "VALIDATION_FAILED",
            "GitHub rejected pull request creation (HTTP 422).");
      }

      if (code == 401 || code == 403) {
        executionLedger.fail(begin.attempt().attemptId(), clock.instant(), "TARGET_AUTH_FAILED");
        throw new GitHubPullRequestException(
            "TARGET_AUTH_FAILED",
            "AUTHENTICATION_FAILED",
            "GitHub rejected credentials (HTTP " + code + ").");
      }

      if (code == 404) {
        executionLedger.fail(begin.attempt().attemptId(), clock.instant(), "REPOSITORY_NOT_FOUND");
        throw new GitHubPullRequestException(
            "REPOSITORY_NOT_FOUND",
            "RESOURCE_NOT_FOUND",
            "GitHub repository was not found (HTTP 404).");
      }

      // 5xx Server Error: effect may or may not have taken place; treat as UNKNOWN
      executionLedger.markUnknown(
          begin.attempt().attemptId(), clock.instant(), "OUTCOME_UNCERTAIN_SERVER_ERROR");
      throw new GitHubPullRequestException(
          "OUTCOME_UNCERTAIN_SERVER_ERROR",
          "OUTCOME_UNCERTAIN",
          "GitHub returned server error (HTTP " + code + "); recorded as UNKNOWN for reconciliation.");

    } catch (InterruptedIOException timeoutFailure) {
      // Ambiguous timeout: effect may have completed on target; do not re-execute!
      executionLedger.markUnknown(
          begin.attempt().attemptId(), clock.instant(), "OUTCOME_UNCERTAIN_TIMEOUT");
      throw new GitHubPullRequestException(
          "OUTCOME_UNCERTAIN_TIMEOUT",
          "OUTCOME_UNCERTAIN",
          "HTTP request timed out before confirmation; recorded as UNKNOWN for reconciliation.",
          timeoutFailure);
    } catch (IOException ioFailure) {
      // Ambiguous connection failure: effect status uncertain
      executionLedger.markUnknown(
          begin.attempt().attemptId(), clock.instant(), "OUTCOME_UNCERTAIN_IO_ERROR");
      throw new GitHubPullRequestException(
          "OUTCOME_UNCERTAIN_IO_ERROR",
          "OUTCOME_UNCERTAIN",
          "Network I/O failed during request; recorded as UNKNOWN for reconciliation.",
          ioFailure);
    }
  }

  private static Duration boundedTimeout(Duration value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isZero() || value.isNegative() || value.compareTo(MAXIMUM_TIMEOUT) > 0) {
      throw new IllegalArgumentException(field + " is outside safe bounds (0 < timeout <= 10s).");
    }
    return value;
  }
}
