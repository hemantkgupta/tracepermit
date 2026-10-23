package io.tracepermit.adapters.remote.github;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.application.ReconciliationObservation;
import io.tracepermit.application.TargetOutcomeResolver;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.ReconciliationCandidate;
import java.io.IOException;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Read-only target lookup port for reconciling uncertain GitHub pull request operations.
 * Implements SEC-019 and ADR 0020: never re-executes; only queries GET /repos/{owner}/{repo}/pulls.
 */
public final class GitHubPullRequestOutcomeResolver implements TargetOutcomeResolver {
  private static final int DEFAULT_MAX_RESPONSE_BYTES = 65_536;
  private static final Duration MAXIMUM_TIMEOUT = Duration.ofSeconds(10);

  public record RegisteredOperation(
      String idempotencyKey,
      String repository,
      String head,
      String base,
      long installationId) {}

  private final GitHubAppCredentialProvider credentialProvider;
  private final URI baseUri;
  private final Duration timeout;
  private final int maxResponseBytes;
  private final SSLSocketFactory sslSocketFactory;
  private final X509TrustManager trustManager;
  private final Map<String, RegisteredOperation> operations = new ConcurrentHashMap<>();
  private final Map<String, GitHubDraftPullRequestResult> confirmedResults = new ConcurrentHashMap<>();

  public GitHubPullRequestOutcomeResolver(
      GitHubAppCredentialProvider credentialProvider,
      URI baseUri,
      Duration timeout) {
    this(credentialProvider, baseUri, timeout, DEFAULT_MAX_RESPONSE_BYTES, null, null);
  }

  public GitHubPullRequestOutcomeResolver(
      GitHubAppCredentialProvider credentialProvider,
      URI baseUri,
      Duration timeout,
      int maxResponseBytes,
      SSLSocketFactory sslSocketFactory,
      X509TrustManager trustManager) {
    this.credentialProvider = Objects.requireNonNull(credentialProvider, "credentialProvider must not be null");
    this.baseUri = Objects.requireNonNull(baseUri, "baseUri must not be null");
    if (baseUri.getScheme() == null
        || (!"https".equalsIgnoreCase(baseUri.getScheme())
            && !"127.0.0.1".equals(baseUri.getHost())
            && !"localhost".equals(baseUri.getHost()))) {
      throw new IllegalArgumentException("Insecure non-HTTPS endpoint requested: " + baseUri);
    }
    this.timeout = boundedTimeout(timeout, "timeout");
    if (maxResponseBytes <= 0 || maxResponseBytes > 1_048_576) {
      throw new IllegalArgumentException("maxResponseBytes is outside safe limits.");
    }
    this.maxResponseBytes = maxResponseBytes;
    this.sslSocketFactory = sslSocketFactory;
    this.trustManager = trustManager;
  }

  public void registerOperation(
      String idempotencyKey, String repository, String head, String base, long installationId) {
    Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
    Objects.requireNonNull(repository, "repository must not be null");
    Objects.requireNonNull(head, "head must not be null");
    Objects.requireNonNull(base, "base must not be null");
    operations.put(
        idempotencyKey,
        new RegisteredOperation(idempotencyKey, repository, head, base, installationId));
  }

  public RegisteredOperation getOperation(String idempotencyKey) {
    return operations.get(idempotencyKey);
  }

  public GitHubDraftPullRequestResult getConfirmedResult(String idempotencyKey) {
    return confirmedResults.get(idempotencyKey);
  }

  @Override
  public ReconciliationObservation lookup(ReconciliationCandidate candidate) {
    Objects.requireNonNull(candidate, "candidate must not be null");
    RegisteredOperation operation = operations.get(candidate.idempotencyKey());
    if (operation == null) {
      return new ReconciliationObservation("unknown", "LOOKUP_METADATA_UNAVAILABLE");
    }

    String[] parts = operation.repository().split("/", 2);
    if (parts.length != 2) {
      return new ReconciliationObservation("failed", "MALFORMED_REPOSITORY");
    }
    String owner = parts[0];
    String repo = parts[1];

    GitHubAppCredentials credentials;
    try {
      credentials = credentialProvider.getInstallationCredentials(operation.repository());
    } catch (RuntimeException failure) {
      return new ReconciliationObservation("unknown", "TARGET_AUTH_UNAVAILABLE");
    }

    OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(timeout)
        .readTimeout(timeout)
        .callTimeout(timeout);
    if (sslSocketFactory != null && trustManager != null) {
      clientBuilder.sslSocketFactory(sslSocketFactory, trustManager);
    }
    OkHttpClient client = clientBuilder.build();

    // Query: GET /repos/{owner}/{repo}/pulls?head={owner}:{head}&base={base}&state=all
    HttpUrl url = HttpUrl.get(baseUri)
        .newBuilder()
        .addPathSegment("repos")
        .addPathSegment(owner)
        .addPathSegment(repo)
        .addPathSegment("pulls")
        .addQueryParameter("head", owner + ":" + operation.head())
        .addQueryParameter("base", operation.base())
        .addQueryParameter("state", "all")
        .build();

    Request request = new Request.Builder()
        .url(url)
        .header("Authorization", "Bearer " + credentials.token())
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "TracePermit-AgentCore-Pilot")
        .get()
        .build();

    try (Response response = client.newCall(request).execute()) {
      int code = response.code();
      if (code == 404) {
        return new ReconciliationObservation("failed", "REPOSITORY_NOT_FOUND");
      }
      if (code == 401 || code == 403) {
        return new ReconciliationObservation("failed", "TARGET_AUTH_FAILED");
      }
      if (code >= 300 && code < 400) {
        return new ReconciliationObservation("failed", "UNEXPECTED_REDIRECT");
      }
      if (code != 200) {
        return new ReconciliationObservation("unknown", "TARGET_LOOKUP_FAILED");
      }

      ResponseBody body = response.body();
      if (body == null) {
        return new ReconciliationObservation("unknown", "EMPTY_LOOKUP_RESPONSE");
      }
      byte[] bytes = body.bytes();
      if (bytes.length == 0) {
        return new ReconciliationObservation("unknown", "EMPTY_LOOKUP_RESPONSE");
      }
      if (bytes.length > maxResponseBytes) {
        return new ReconciliationObservation("failed", "RESPONSE_TOO_LARGE");
      }

      JsonNode array;
      try {
        array = JsonSupport.MAPPER.readTree(bytes);
      } catch (IOException failure) {
        return new ReconciliationObservation("unknown", "INVALID_LOOKUP_PAYLOAD");
      }
      if (array == null || !array.isArray()) {
        return new ReconciliationObservation("unknown", "INVALID_LOOKUP_PAYLOAD");
      }

      for (JsonNode item : array) {
        String itemHeadRef = item.path("head").path("ref").asText();
        String itemBaseRef = item.path("base").path("ref").asText();
        if (operation.head().equals(itemHeadRef) && operation.base().equals(itemBaseRef)) {
          int number = item.path("number").asInt();
          long id = item.path("id").asLong();
          String htmlUrl = item.path("html_url").asText();
          boolean draft = item.path("draft").asBoolean(true);
          String state = item.path("state").asText("open");

          GitHubDraftPullRequestResult result = new GitHubDraftPullRequestResult(
              number,
              id,
              htmlUrl,
              draft,
              state,
              operation.repository(),
              operation.head(),
              operation.base(),
              operation.idempotencyKey(),
              credentials.credentialFingerprint(),
              credentials.installationId());
          confirmedResults.put(operation.idempotencyKey(), result);

          return new ReconciliationObservation("succeeded", "PR_CONFIRMED_SUCCESS");
        }
      }

      return new ReconciliationObservation("failed", "PR_CONFIRMED_ABSENT");
    } catch (IOException | RuntimeException failure) {
      return new ReconciliationObservation("unknown", "TARGET_LOOKUP_FAILED");
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
