package io.tracepermit.adapters.remote.github;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.InMemoryExecutionLedger;
import io.tracepermit.security.ExecutionPermitAuthority;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;

/** Permit-protected, preflight-bound draft-only foundation. Not a durable production executor. */
public final class GitHubDraftPullRequestAdapter {
  public static final URI PRODUCTION_GITHUB_API_URI = URI.create("https://api.github.com");
  public static final int DEFAULT_MAX_RESPONSE_BYTES = 65_536;
  public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
  public static final Duration DEFAULT_CALL_TIMEOUT = Duration.ofSeconds(10);
  private final GitHubAppCredentialProvider credentialProvider;
  private final InMemoryExecutionLedger executionLedger;
  private final GitHubPullRequestOutcomeResolver outcomeResolver;
  private final ExecutionPermitAuthority permitAuthority;
  private final String expectedAudience;
  private final GitHubApiTransport transport;
  private final GitHubPilotTargetBinding binding;
  private final Clock clock;

  /** Compatibility construction conveys no target binding; execute fails closed before network. */
  public GitHubDraftPullRequestAdapter(GitHubAppCredentialProvider provider, InMemoryExecutionLedger ledger,
      GitHubPullRequestOutcomeResolver resolver, ExecutionPermitAuthority authority, String audience) {
    this(provider, ledger, resolver, authority, audience, PRODUCTION_GITHUB_API_URI,
        DEFAULT_CONNECT_TIMEOUT, DEFAULT_CALL_TIMEOUT, DEFAULT_MAX_RESPONSE_BYTES, Clock.systemUTC(), null, null);
  }

  /** Legacy endpoint/TLS inputs cannot opt production callers into a synthetic transport. */
  public GitHubDraftPullRequestAdapter(GitHubAppCredentialProvider provider, InMemoryExecutionLedger ledger,
      GitHubPullRequestOutcomeResolver resolver, ExecutionPermitAuthority authority, String audience,
      URI origin, Duration connect, Duration call, int limit, Clock clock,
      SSLSocketFactory tls, X509TrustManager trust) {
    this(provider, ledger, resolver, authority, audience, null,
        new GitHubApiTransport(origin, connect, call, limit, tls, trust), clock);
  }

  public GitHubDraftPullRequestAdapter(GitHubAppCredentialProvider provider, InMemoryExecutionLedger ledger,
      GitHubPullRequestOutcomeResolver resolver, ExecutionPermitAuthority authority, String audience,
      GitHubPilotTargetBinding binding) {
    this(provider, ledger, resolver, authority, audience, Objects.requireNonNull(binding),
        new GitHubApiTransport(PRODUCTION_GITHUB_API_URI, DEFAULT_CONNECT_TIMEOUT, DEFAULT_CALL_TIMEOUT,
            DEFAULT_MAX_RESPONSE_BYTES, null, null), Clock.systemUTC());
  }

  private GitHubDraftPullRequestAdapter(GitHubAppCredentialProvider provider, InMemoryExecutionLedger ledger,
      GitHubPullRequestOutcomeResolver resolver, ExecutionPermitAuthority authority, String audience,
      GitHubPilotTargetBinding binding, GitHubApiTransport transport, Clock clock) {
    this.credentialProvider = Objects.requireNonNull(provider);
    this.executionLedger = Objects.requireNonNull(ledger);
    this.outcomeResolver = Objects.requireNonNull(resolver);
    this.permitAuthority = authority;
    this.expectedAudience = audience;
    this.binding = binding;
    this.transport = Objects.requireNonNull(transport);
    this.clock = Objects.requireNonNull(clock);
    if (binding != null && !resolver.boundTo(binding)) throw GitHubApiTransport.rejected("TARGET_BINDING_INVALID");
  }

  static GitHubDraftPullRequestAdapter synthetic(GitHubAppCredentialProvider provider,
      InMemoryExecutionLedger ledger, GitHubPullRequestOutcomeResolver resolver,
      ExecutionPermitAuthority authority, GitHubPilotTargetBinding binding, GitHubApiTransport transport, Clock clock) {
    return new GitHubDraftPullRequestAdapter(provider, ledger, resolver, authority,
        GitHubDraftPullRequestAction.OPERATION, binding, transport, clock);
  }

  public String expectedAudience() { return expectedAudience; }

  public static String computeOperationKey(long installationId, String repository, String head, String base) {
    String material = "github.pull_request.create:" + installationId + ":"
        + repository.toLowerCase(Locale.ROOT).trim() + ":" + head.trim() + ":" + base.trim();
    return "sha256:" + HexFormat.of().formatHex(CanonicalJson.sha256Bytes(material.getBytes(StandardCharsets.UTF_8)));
  }

  public GitHubDraftPullRequestResult execute(JsonNode canonicalAction, String permitToken) {
    JsonNode snapshot = canonicalAction == null ? null : canonicalAction.deepCopy();
    GitHubDraftPullRequestAction action;
    try { action = GitHubDraftPullRequestAction.fromCanonical(snapshot); }
    catch (RuntimeException failure) {
      throw GitHubApiTransport.rejected(failure instanceof GitHubPullRequestException rejected
          ? rejected.errorCode() : "INVALID_ACTION");
    }
    if (binding == null) throw GitHubApiTransport.rejected("TARGET_PREFLIGHT_BINDING_REQUIRED");
    binding.requireAction(action);
    if (permitAuthority == null || !GitHubDraftPullRequestAction.OPERATION.equals(expectedAudience)) {
      throw GitHubApiTransport.rejected("PERMIT_AUTHORITY_REQUIRED");
    }
    if (permitToken == null || permitToken.isBlank()) throw GitHubApiTransport.rejected("MISSING_PERMIT");
    JsonNode permitClaims = permitAuthority.verifyAndConsume(permitToken, snapshot, expectedAudience);
    String payloadHash = CanonicalJson.sha256(snapshot.path("action"));
    GitHubAppCredentials credentials;
    try { credentials = credentialProvider.getInstallationCredentials(action.repository()); }
    catch (RuntimeException failure) { throw GitHubApiTransport.rejected("TARGET_AUTH_UNAVAILABLE"); }
    GitHubApiTransport.requireCredentials(credentials, binding.installationId(), clock);
    String key = computeOperationKey(binding.installationId(), action.repository(), action.head(), action.base());
    // Immutable registration prevents a same-tuple changed body/title from borrowing cached success.
    outcomeResolver.registerOperation(key, binding, action.title(), action.body());
    var begin = executionLedger.begin(action.eventId(), permitClaims.path("permitId").asText(), key, clock.instant());
    if (!begin.started()) {
      var attempt = begin.attempt();
      if ("succeeded".equals(attempt.status())) {
        JsonNode cached = attempt.result();
        if (cached == null || !cached.path("number").isIntegralNumber() || !cached.path("number").canConvertToInt()
            || !cached.path("id").isIntegralNumber() || !cached.path("id").canConvertToLong()
            || cached.path("number").asInt() <= 0 || cached.path("id").asLong() <= 0
            || !cached.path("installationId").isIntegralNumber()
            || !cached.path("installationId").canConvertToLong()
            || cached.path("installationId").asLong() != binding.installationId()
            || !binding.fingerprint().equals(cached.path("targetBindingHash").asText())
            || !payloadHash.equals(cached.path("payloadHash").asText())
            || !binding.repository().equals(cached.path("repository").asText())
            || !binding.head().equals(cached.path("head").asText()) || !binding.base().equals(cached.path("base").asText())
            || !cached.path("draft").isBoolean() || !cached.path("draft").booleanValue()
            || !"open".equals(cached.path("state").asText())
            || !("https://github.com/" + binding.repository() + "/pull/" + cached.path("number").intValue())
                .equals(cached.path("htmlUrl").asText())) {
          throw GitHubApiTransport.rejected("INVALID_CACHED_RESULT");
        }
        return new GitHubDraftPullRequestResult(cached.path("number").intValue(), cached.path("id").longValue(),
            cached.path("htmlUrl").asText(), true, "open", binding.repository(), binding.head(), binding.base(),
            key, credentials.credentialFingerprint(), binding.installationId());
      }
      throw GitHubApiTransport.rejected(switch (attempt.status()) {
        case "executing" -> "CONCURRENT_EXECUTION_IN_PROGRESS";
        case "unknown" -> "EXECUTION_OUTCOME_UNKNOWN";
        default -> "PREVIOUS_EXECUTION_FAILED";
      });
    }

    boolean dispatched = false;
    try (var session = transport.open()) {
      GitHubTargetPreflight.verify(session, binding, credentials, clock);
      // Credential time is sampled again immediately before the write, inside request().
      GitHubApiTransport.requireCredentials(credentials, binding.installationId(), clock);
      var payload = JsonSupport.MAPPER.createObjectNode().put("title", action.title()).put("head", action.head())
          .put("base", action.base()).put("draft", true).put("body", action.body());
      dispatched = true; // Conservatively uncertain even if a later transport check refuses the call.
      var response = session.request(GitHubTargetPreflight.repoPath(binding, "pulls"), Map.of(), payload,
          credentials, binding.installationId(), clock);
      if (response.status() != 201) {
        String reason = switch (response.status()) {
          case 401, 403 -> "TARGET_AUTH_FAILED";
          case 404 -> "REPOSITORY_NOT_FOUND";
          case 422 -> "PR_CREATION_REJECTED";
          default -> "OUTCOME_UNCERTAIN";
        };
        if (response.status() >= 300 && response.status() < 400) reason = "UNEXPECTED_REDIRECT";
        if (response.status() == 401 || response.status() == 403 || response.status() == 404 || response.status() == 422) {
          executionLedger.fail(begin.attempt().attemptId(), clock.instant(), reason);
          dispatched = false;
          throw GitHubApiTransport.rejected(reason);
        }
        throw GitHubApiTransport.rejected(reason);
      }
      if (response.pagination()) throw GitHubApiTransport.rejected("INVALID_TARGET_CONFIRMATION");
      var result = GitHubTargetPreflight.confirmation(response.json(), binding, key, credentials);
      var confirmed = response.json();
      if (!action.title().equals(GitHubTargetPreflight.text(confirmed, "title"))
          || !(action.body().equals(confirmed.path("body").asText(null))
              || (action.body().isEmpty() && confirmed.path("body").isNull()))) {
        throw GitHubApiTransport.rejected("INVALID_TARGET_CONFIRMATION");
      }
      executionLedger.succeed(begin.attempt().attemptId(), clock.instant(), result.toJsonNode()
          .put("targetBindingHash", binding.fingerprint()).put("payloadHash", payloadHash));
      return result;
    } catch (IOException | RuntimeException failure) {
      var attempt = executionLedger.findByIdempotencyKey(key);
      String reason = failure instanceof GitHubPullRequestException rejected
          ? rejected.errorCode() : "TARGET_TRANSPORT_UNAVAILABLE";
      if ("executing".equals(attempt.status())) {
        if (dispatched) executionLedger.markUnknown(attempt.attemptId(), clock.instant(), reason);
        else executionLedger.fail(attempt.attemptId(), clock.instant(), reason);
      }
      throw GitHubApiTransport.rejected(reason);
    }
  }
}
