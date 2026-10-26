package io.tracepermit.adapters.remote.agentcore;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.adapters.remote.github.GitHubBusinessOperationBinding;
import io.tracepermit.application.BusinessOperationRegistration;
import io.tracepermit.application.BusinessOperationRepository;
import io.tracepermit.application.BusinessOperationScope;
import io.tracepermit.application.BusinessOperationStorageException;
import io.tracepermit.contracts.ActionValidator;
import io.tracepermit.contracts.BusinessOperationValidator;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.identity.AuthenticatedConnector;
import io.tracepermit.identity.ConnectorScope;
import io.tracepermit.identity.OidcAccessTokenVerifier;
import io.tracepermit.identity.WorkloadIdentityReplayStore;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Unwired, workload-only registration adapter. No listener, execution or target credential port.
 * Operator enrollment and a verified access token are both required; neither proves human OBO.
 */
public final class AgentCoreRegistrationIngress {
  public static final String PROFILE = "tracepermit-agentcore-registration-v1";
  public static final ConnectorScope REQUIRED_SCOPE = ConnectorScope.of("operations.register");
  public static final int MAXIMUM_REQUEST_BYTES = 32_768;
  private static final ObjectMapper STRICT =
      new ObjectMapper(
              JsonFactory.builder()
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(4)
                          .maxNameLength(64)
                          .maxStringLength(16_384)
                          .maxNumberLength(32)
                          .build())
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  /** One exact operator-owned identity/scope/target enrollment per instance; never body-derived. */
  public record Enrollment(
      String issuer,
      String verifiedSubjectId,
      String clientId,
      BusinessOperationScope scope,
      GitHubBusinessOperationBinding.ExpectedProfile targetProfile,
      String retentionPolicyId,
      Duration retention) {
    public Enrollment {
      require(issuer != null && issuer.length() <= 512 && issuer.startsWith("https://"));
      require(verifiedSubjectId != null && verifiedSubjectId.length() <= 1_024);
      require(verifiedSubjectId.startsWith(issuer + "#") && verifiedSubjectId.length() > issuer.length() + 1);
      require(clientId != null && clientId.matches("[A-Za-z0-9][A-Za-z0-9._:@/+=-]{0,255}"));
      Objects.requireNonNull(scope);
      Objects.requireNonNull(targetProfile);
      require(retentionPolicyId != null && retentionPolicyId.matches("[A-Za-z0-9][A-Za-z0-9._:@/-]{0,255}"));
      require(retention != null && retention.compareTo(Duration.ofSeconds(1)) >= 0
          && retention.compareTo(Duration.ofDays(365)) <= 0);
    }

    @Override
    public String toString() {
      return "AgentCoreEnrollment[redacted; workload registration only]";
    }
  }

  /** Fixed codes retain no token, parser, repository or suppressed exception details. */
  public static final class IngressException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private IngressException(String code) {
      super(code, null, false, false);
    }

    public String code() {
      return getMessage();
    }
  }

  /** Minimal response deliberately omits the stored snapshot and credential evidence. */
  public record Receipt(boolean created, String operationId) {
    public boolean executionEnabled() {
      return false;
    }
  }

  private final OidcAccessTokenVerifier verifier;
  private final WorkloadIdentityReplayStore replay;
  private final BusinessOperationRepository repository;
  private final Clock clock;
  private final Enrollment enrollment;

  public AgentCoreRegistrationIngress(
      OidcAccessTokenVerifier verifier,
      WorkloadIdentityReplayStore replay,
      BusinessOperationRepository repository,
      Clock clock,
      Enrollment enrollment) {
    this.verifier = Objects.requireNonNull(verifier);
    this.replay = Objects.requireNonNull(replay);
    this.repository = Objects.requireNonNull(repository);
    this.clock = Objects.requireNonNull(clock);
    this.enrollment = Objects.requireNonNull(enrollment);
  }

  /**
   * A token is single-use, including failed/uncertain registration attempts after consumption.
   * Explicit idempotent retries need a fresh verified token and the same scoped request key.
   * Any second authorization value is refused: two valid tokens do not establish delegation.
   */
  public Receipt register(String authorization, String humanAuthorization, byte[] body) {
    if (humanAuthorization != null) throw failure("AGENTCORE_DELEGATION_CONTRACT_REQUIRED");
    ObjectNode request = parse(body);
    AuthenticatedConnector identity;
    try {
      require(authorization != null && authorization.length() <= 16_391);
      identity = verifier.authenticate(authorization);
      require(enrollment.issuer().equals(identity.issuer())
          && enrollment.verifiedSubjectId().equals(identity.subjectId())
          && enrollment.clientId().equals(identity.clientId())
          && enrollment.scope().tenantId().equals(identity.organizationId())
          && identity.hasScope(REQUIRED_SCOPE)
          && identity.senderConstraint().isEmpty());
    } catch (RuntimeException rejected) {
      throw failure("AGENTCORE_IDENTITY_REJECTED");
    }
    ObjectNode operation;
    try {
      Instant now = clock.instant();
      require(!identity.verifiedAt().isAfter(now) && identity.expiresAt().isAfter(now));
      operation = operation(request, identity, now);
      // Reuse the exact merged target/profile contract without acquiring credentials or authority.
      GitHubBusinessOperationBinding.bind(enrollment.scope(), operation, enrollment.targetProfile());
      require(replay.consume(enrollment.scope().tenantId(), identity.issuer(),
          identity.credentialFingerprint().value(), identity.expiresAt(), clock.instant()));
    } catch (RuntimeException rejected) {
      throw failure("AGENTCORE_REGISTRATION_REJECTED");
    }
    try {
      BusinessOperationRegistration result = repository.createOrGet(enrollment.scope(), operation);
      // Return the repository's original registration; retries never replace it with proposed data.
      return new Receipt(result.created(), result.operation().path("operationId").textValue());
    } catch (BusinessOperationStorageException rejected) {
      throw failure(switch (rejected.code()) {
        case OPERATION_REQUEST_KEY_CONFLICT -> "AGENTCORE_REQUEST_KEY_CONFLICT";
        case OPERATION_COMMIT_UNKNOWN -> "AGENTCORE_REGISTRATION_UNKNOWN";
        default -> "AGENTCORE_STORAGE_UNAVAILABLE";
      });
    } catch (RuntimeException rejected) {
      throw failure("AGENTCORE_STORAGE_UNAVAILABLE");
    }
  }

  private static ObjectNode parse(byte[] body) {
    try {
      require(body != null && body.length > 0 && body.length <= MAXIMUM_REQUEST_BYTES);
      byte[] snapshot = body.clone();
      String json = StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(snapshot)).toString();
      JsonNode parsed = STRICT.readTree(json);
      require(parsed != null && parsed.isObject());
      ObjectNode request = (ObjectNode) parsed;
      Set<String> allowed = Set.of("schemaVersion", "clientRequestKey", "title", "body");
      request.fieldNames().forEachRemaining(field -> require(allowed.contains(field)));
      require("1.0".equals(text(request, "schemaVersion", 3)));
      require(text(request, "clientRequestKey", 128).matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"));
      text(request, "title", 256);
      if (request.has("body")) text(request, "body", 16_384);
      return request;
    } catch (Exception rejected) {
      throw failure("AGENTCORE_REQUEST_INVALID");
    }
  }

  private static String text(JsonNode parent, String name, int maximum) {
    JsonNode value = parent.get(name);
    require(value != null && value.isTextual());
    String text = value.textValue();
    require(!text.isBlank() && text.length() <= maximum);
    for (int i = 0; i < text.length(); i++) {
      char current = text.charAt(i);
      if (Character.isHighSurrogate(current)) {
        require(i + 1 < text.length() && Character.isLowSurrogate(text.charAt(++i)));
      } else {
        require(!Character.isLowSurrogate(current));
      }
    }
    return text;
  }

  private ObjectNode operation(ObjectNode request, AuthenticatedConnector identity, Instant now) {
    BusinessOperationScope scope = enrollment.scope();
    var profile = enrollment.targetProfile();
    var target = profile.targetBinding();
    String requester = scope.requesterPrincipalId();
    ObjectNode event = JsonSupport.MAPPER.createObjectNode();
    String eventId = UUID.randomUUID().toString();
    event.put("schemaVersion", "1.0").put("eventId", eventId).put("observedAt", now.toString())
        .put("traceId", UUID.randomUUID().toString().replace("-", ""));
    ObjectNode subject = event.putObject("identity").putObject("subject");
    subject.put("id", requester).put("organizationId", scope.tenantId())
        .put("type", "workload").put("authenticationMethod", "oauth").put("assurance", "authenticated");
    ((ObjectNode) event.get("identity")).putArray("evidence").addObject()
        .put("evidenceId", "workload-token").put("subjectId", requester).put("kind", "oauth_token")
        .put("assurance", "authenticated").put("issuer", identity.issuer())
        .put("verifiedAt", identity.verifiedAt().toString())
        .put("credentialFingerprint", identity.credentialFingerprint().value());
    event.putObject("trigger").put("kind", "service").put("origin", "api").put("requestId", eventId);
    ObjectNode action = event.putObject("action");
    action.put("protocol", "http").put("toolName", "github.pull_request.create");
    ObjectNode arguments = action.putObject("arguments");
    arguments.put("repository", target.repository()).put("head", target.head()).put("base", target.base())
        .put("draft", true).put("title", request.path("title").textValue());
    if (request.has("body")) arguments.set("body", request.get("body"));
    action.putObject("target").put("system", "github").put("environment", profile.environment())
        .put("accountId", target.repository()).put("resourceType", "pull_request");
    ObjectNode lineage = event.putObject("lineage");
    lineage.put("rootPrincipalId", requester);
    lineage.putArray("hops").addObject().put("hopId", "registered-workload").put("kind", "principal")
        .put("subjectId", requester).put("assurance", "authenticated").put("source", PROFILE)
        .put("observedAt", now.toString());
    lineage.putArray("delegations");
    event.putObject("clientClaims");
    ObjectNode operation = JsonSupport.MAPPER.createObjectNode();
    String operationId = UUID.randomUUID().toString();
    ObjectNode key = JsonSupport.MAPPER.createObjectNode().put("profile", PROFILE)
        .put("scopeHash", scope.fingerprint()).put("clientRequestKey", request.path("clientRequestKey").textValue());
    operation.put("schemaVersion", "1.0").put("operationId", operationId).set("trustedContext", scope.trustedContext());
    operation.put("adapterId", profile.adapterId()).put("clientRequestKeyHash", CanonicalJson.sha256(key))
        .put("canonicalization", CanonicalJson.PROFILE);
    operation.set("canonicalAction", ActionValidator.validate(event));
    operation.put("canonicalActionHash", CanonicalJson.sha256(operation.get("canonicalAction")))
        .put("semanticPayloadDigest", CanonicalJson.sha256(action));
    operation.set("resourceBinding", profile.resourceBinding());
    operation.put("resourceBindingHash", CanonicalJson.sha256(profile.resourceBinding()))
        .put("targetIdempotencyKeyHash", BusinessOperationValidator.targetIdempotencyKeyHash(
            operationId, profile.adapterId(), profile.recoveryGeneration()))
        .put("recoveryGeneration", profile.recoveryGeneration());
    operation.putObject("policyRelease").put("policyId", profile.policyRelease().policyId())
        .put("version", profile.policyRelease().version()).put("contentHash", profile.policyRelease().contentHash());
    var artifacts = operation.putArray("artifactDigests");
    new TreeMap<>(profile.artifactDigests()).forEach((kind, digest) ->
        artifacts.addObject().put("kind", kind).put("digest", digest));
    operation.putObject("retention").put("policyId", enrollment.retentionPolicyId())
        .put("retainUntil", now.plus(enrollment.retention()).toString());
    operation.put("createdAt", now.toString()).put("updatedAt", now.toString());
    String[] dimensions = {"authorization", "execution", "evidence"};
    String[] states = {"evaluating", "not_started", "pending"};
    String[] reasons = {"OPERATION_CREATED", "EXECUTION_NOT_AUTHORIZED", "EVIDENCE_PENDING"};
    var transitions = operation.putArray("transitions");
    for (int i = 0; i < dimensions.length; i++) {
      operation.putObject(dimensions[i]).put("status", states[i]).put("version", 1).put("updatedAt", now.toString());
      transitions.addObject().put("sequence", i + 1).put("dimension", dimensions[i])
          .put("to", states[i]).put("version", 1).put("at", now.toString()).put("reasonCode", reasons[i]);
    }
    return (ObjectNode) BusinessOperationValidator.validate(operation);
  }

  private static void require(boolean condition) {
    if (!condition) throw failure("AGENTCORE_CONFIGURATION_INVALID");
  }

  private static IngressException failure(String code) {
    return new IngressException(code);
  }
}
