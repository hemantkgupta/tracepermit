package io.tracepermit.adapters.remote.github;

import io.tracepermit.application.ReconciliationObservation;
import io.tracepermit.application.TargetOutcomeResolver;
import io.tracepermit.execution.ReconciliationCandidate;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;

/** Process-local GET-only desired-effect observation. Never proves absence or retries an effect. */
public final class GitHubPullRequestOutcomeResolver implements TargetOutcomeResolver {
  public record RegisteredOperation(String idempotencyKey, String repository, String head, String base,
      long installationId, GitHubPilotTargetBinding binding, String title, String body) {
    @Override public String toString() { return "RegisteredOperation[redacted]"; }
  }
  private final GitHubAppCredentialProvider credentialProvider;
  private final GitHubApiTransport transport;
  private final GitHubPilotTargetBinding binding;
  private final Clock clock;
  private final Map<String, RegisteredOperation> operations = new ConcurrentHashMap<>();
  private final Map<String, GitHubDraftPullRequestResult> confirmedResults = new ConcurrentHashMap<>();

  public GitHubPullRequestOutcomeResolver(GitHubAppCredentialProvider provider, URI origin, Duration timeout) {
    this(provider, origin, timeout, 65_536, null, null);
  }
  public GitHubPullRequestOutcomeResolver(GitHubAppCredentialProvider provider, URI origin, Duration timeout,
      int limit, SSLSocketFactory tls, X509TrustManager trust) {
    this(provider, null, new GitHubApiTransport(origin, timeout, timeout, limit, tls, trust), Clock.systemUTC());
  }
  public GitHubPullRequestOutcomeResolver(GitHubAppCredentialProvider provider, GitHubPilotTargetBinding binding) {
    this(provider, Objects.requireNonNull(binding), new GitHubApiTransport(
        GitHubDraftPullRequestAdapter.PRODUCTION_GITHUB_API_URI, Duration.ofSeconds(5), Duration.ofSeconds(10),
        65_536, null, null), Clock.systemUTC());
  }
  private GitHubPullRequestOutcomeResolver(GitHubAppCredentialProvider provider, GitHubPilotTargetBinding binding,
      GitHubApiTransport transport, Clock clock) {
    this.credentialProvider = Objects.requireNonNull(provider); this.binding = binding;
    this.transport = Objects.requireNonNull(transport); this.clock = Objects.requireNonNull(clock);
  }
  static GitHubPullRequestOutcomeResolver synthetic(GitHubAppCredentialProvider provider, GitHubPilotTargetBinding binding,
      GitHubApiTransport transport, Clock clock) {
    return new GitHubPullRequestOutcomeResolver(provider, binding, transport, clock);
  }
  boolean boundTo(GitHubPilotTargetBinding expected) { return expected.equals(binding); }

  /** Old unbound metadata is readable for compatibility but can never certify an outcome. */
  public void registerOperation(String key, String repository, String head, String base, long installationId) {
    register(new RegisteredOperation(key, repository, head, base, installationId, null, null, null));
  }
  void registerOperation(String key, GitHubPilotTargetBinding pinned, String title, String body) {
    if (!boundTo(pinned) || !GitHubDraftPullRequestAdapter.computeOperationKey(
        pinned.installationId(), pinned.repository(), pinned.head(), pinned.base()).equals(key)) {
      throw GitHubApiTransport.rejected("LOOKUP_BINDING_INVALID");
    }
    register(new RegisteredOperation(key, pinned.repository(), pinned.head(), pinned.base(), pinned.installationId(),
        pinned, Objects.requireNonNull(title), Objects.requireNonNull(body)));
  }
  private void register(RegisteredOperation operation) {
    var previous = operations.putIfAbsent(Objects.requireNonNull(operation.idempotencyKey()), operation);
    if (previous != null && !previous.equals(operation)) throw GitHubApiTransport.rejected("OPERATION_BINDING_CONFLICT");
  }
  public RegisteredOperation getOperation(String key) { return operations.get(key); }
  public GitHubDraftPullRequestResult getConfirmedResult(String key) { return confirmedResults.get(key); }

  @Override public synchronized ReconciliationObservation lookup(ReconciliationCandidate candidate) {
    Objects.requireNonNull(candidate);
    confirmedResults.remove(candidate.idempotencyKey());
    var operation = operations.get(candidate.idempotencyKey());
    if (binding == null || operation == null || !binding.equals(operation.binding())
        || !GitHubDraftPullRequestAction.OPERATION.equals(candidate.adapterId())) {
      return unknown("LOOKUP_METADATA_UNAVAILABLE");
    }
    GitHubAppCredentials credentials;
    try {
      credentials = credentialProvider.getInstallationCredentials(operation.repository());
      GitHubApiTransport.requireCredentials(credentials, operation.installationId(), clock);
    } catch (RuntimeException failure) { return unknown("TARGET_CREDENTIAL_REJECTED"); }
    try (var session = transport.open()) {
      GitHubTargetPreflight.verify(session, binding, credentials, clock);
      var response = session.request(GitHubTargetPreflight.repoPath(binding, "pulls"), Map.of(
          "head", operation.repository().split("/")[0] + ":" + operation.head(), "base", operation.base(),
          "state", "all", "per_page", "100", "page", "1"), null, credentials, operation.installationId(), clock);
      if (response.status() != 200) return unknown("TARGET_LOOKUP_UNAVAILABLE");
      if (response.pagination()) return unknown("LOOKUP_INCOMPLETE");
      var array = response.json();
      if (!array.isArray()) return unknown("INVALID_LOOKUP_PAYLOAD");
      // Empty, duplicate, partial or contradictory observations are never evidence of absence.
      if (array.size() != 1) return unknown("LOOKUP_AMBIGUOUS_OR_ABSENT");
      var pr = array.get(0);
      var result = GitHubTargetPreflight.confirmation(pr, binding, operation.idempotencyKey(), credentials);
      if (!operation.title().equals(GitHubTargetPreflight.text(pr, "title"))
          || !(operation.body().equals(pr.path("body").asText(null))
              || (operation.body().isEmpty() && pr.path("body").isNull()))) {
        return unknown("LOOKUP_PAYLOAD_MISMATCH");
      }
      confirmedResults.put(operation.idempotencyKey(), result);
      return new ReconciliationObservation("succeeded", "PR_CONFIRMED_SUCCESS");
    } catch (IOException | RuntimeException failure) {
      return unknown(failure instanceof GitHubPullRequestException rejected
          ? rejected.errorCode() : "TARGET_LOOKUP_UNAVAILABLE");
    }
  }
  private static ReconciliationObservation unknown(String reason) { return new ReconciliationObservation("unknown", reason); }
}
