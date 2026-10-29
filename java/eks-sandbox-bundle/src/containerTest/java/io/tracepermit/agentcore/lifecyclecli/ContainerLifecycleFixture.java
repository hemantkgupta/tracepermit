package io.tracepermit.agentcore.lifecyclecli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.adapters.remote.agentcore.AgentCoreRegistrationIngress;
import io.tracepermit.adapters.remote.github.GitHubBusinessOperationBinding;
import io.tracepermit.adapters.remote.github.GitHubPilotTargetBinding;
import io.tracepermit.agentcore.registration.DurableAgentCoreRegistration;
import io.tracepermit.application.BusinessOperationScope;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.persistence.operations.JdbcBusinessOperationRepository;
import io.tracepermit.persistence.operations.OperationAuthorityAdministration;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/** New synthetic assembly only; accepted identity, DB bootstrap/grants and trust code are reused. */
final class ContainerLifecycleFixture implements AutoCloseable {
  final LifecycleTestIdentity identity = new LifecycleTestIdentity();
  final LifecycleTestDatabase database;
  final KeyPair signing;
  final BusinessOperationScope scope;
  final GitHubBusinessOperationBinding.ExpectedProfile expected;
  final ObjectNode configuration;
  private final ObjectNode policy;

  ContainerLifecycleFixture(boolean withDatabase) throws Exception {
    database = withDatabase ? startDatabase() : null;
    try {
    signing = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    scope = new BusinessOperationScope(LifecycleTestIdentity.TENANT, LifecycleTestIdentity.WORKSPACE, identity.requesterPrincipal());
    policy = (ObjectNode) JsonSupport.MAPPER.readTree("""
        {"schemaVersion":"1.0","id":"synthetic-container-policy","version":1,
         "name":"Synthetic container rehearsal","description":"No live target","enabled":true,
         "defaultDecision":{"outcome":"block","reasonCode":"SYNTHETIC_DENY"},
         "rules":[{"id":"draft","priority":1,"description":"Synthetic",
         "match":{"toolNames":["github.pull_request.create"]},
         "decision":{"outcome":"require_approval","reasonCode":"SYNTHETIC_REVIEW",
         "approvalGroup":"synthetic-reviewers"}}]}
        """);
    String digest = "sha256:" + "a".repeat(64);
    expected = new GitHubBusinessOperationBinding.ExpectedProfile("github-draft-pr-v1", "test", 1,
        new GitHubBusinessOperationBinding.PolicyRelease("synthetic-container-policy", 1, CanonicalJson.sha256(policy)),
        Map.of("adapter_release", digest), new GitHubPilotTargetBinding(71, 101, 201,
            "tracepermit/agentcore-pr-pilot", "synthetic-change", "main", "a".repeat(40), "b".repeat(40)));
    configuration = configuration(digest);
    // Parse using the actual unchanged operator parser before any Docker input is constructed.
    LifecycleOperatorConfig.parse(configuration);
    } catch (Exception | Error failure) {
      if (database != null) database.close();
      throw failure;
    }
  }

  private static LifecycleTestDatabase startDatabase() {
    try { return new LifecycleTestDatabase("postgres:16.4-alpine"); }
    catch (RuntimeException | Error failure) { throw new AssertionError("SYNTHETIC_POSTGRES_SETUP_FAILED"); }
  }

  private ObjectNode configuration(String digest) {
    ObjectNode root = JsonSupport.MAPPER.createObjectNode().put("schemaVersion", "1.0").put("profile", LifecycleProtocol.PROFILE);
    ObjectNode operations = database == null
        ? JsonSupport.MAPPER.createObjectNode().put("engine", "POSTGRESQL_16_4").put("host", "127.0.0.1")
            .put("port", 5432).put("catalog", "synthetic_offline").put("user", "synthetic_user")
            .put("password", "synthetic-unused-offline-placeholder")
        : database.operationConfig().deepCopy().put("port", 5432);
    root.set("operations", operations);
    root.putObject("scope").put("tenantId", scope.tenantId()).put("workspaceId", scope.workspaceId())
        .put("requesterPrincipalId", scope.requesterPrincipalId());
    root.putObject("expectedTarget").put("adapterId", expected.adapterId()).put("environment", "test")
        .put("recoveryGeneration", 1).put("policyId", "synthetic-container-policy").put("policyVersion", 1)
        .put("policyContentHash", CanonicalJson.sha256(policy)).put("installationId", 71).put("repositoryId", 101)
        .put("ownerId", 201).put("repository", "tracepermit/agentcore-pr-pilot").put("head", "synthetic-change")
        .put("base", "main").put("headSha", "a".repeat(40)).put("baseSha", "b".repeat(40))
        .putObject("artifactDigests").put("adapter_release", digest);
    var artifacts = JsonSupport.MAPPER.createArrayNode();
    artifacts.addObject().put("kind", "adapter_release").put("digest", digest);
    var roster = root.putArray("reviewerMemberships");
    roster.addObject().put("verifiedSubjectId", identity.reviewerVerifiedSubject())
        .put("clientId", LifecycleTestIdentity.REVIEWER_CLIENT).put("tenantId", scope.tenantId())
        .put("workspaceId", scope.workspaceId()).put("groupId", "synthetic-reviewers").put("human", true);
    root.putObject("authority").put("adapterId", expected.adapterId()).put("recoveryGeneration", 1)
        .put("artifactDigestsHash", CanonicalJson.sha256(artifacts))
        .put("resourceBindingHash", CanonicalJson.sha256(expected.resourceBinding()))
        .put("reviewerGroupId", "synthetic-reviewers")
        .put("membershipSourceFingerprint", LifecycleMemberships.parse(roster).fingerprint())
        .put("approvalLifetimeSeconds", 120).put("permitIssuer", "https://synthetic-permit-issuer.invalid")
        .put("permitKeyId", "synthetic-container-permit").put("permitAudience", "synthetic-operation-admission")
        .put("permitPublicKeySpki", Base64.getEncoder().encodeToString(signing.getPublic().getEncoded()))
        .put("permitLifetimeSeconds", 60).set("policy", policy.deepCopy());
    root.set("requesterProvider", identity.requesterProvider());
    root.set("reviewerProvider", identity.reviewerProvider());
    root.putObject("requesterEnrollment").put("verifiedSubjectId", identity.requesterVerifiedSubject())
        .put("clientId", LifecycleTestIdentity.REQUESTER_CLIENT).put("tenantId", scope.tenantId())
        .put("workspaceId", scope.workspaceId()).put("requesterPrincipalId", scope.requesterPrincipalId());
    return root;
  }

  String registerAndActivate() {
    if (database == null) throw new AssertionError("POSTGRES_FIXTURE_REQUIRED");
    var enrollment = new AgentCoreRegistrationIngress.Enrollment(LifecycleTestIdentity.REGISTRATION_ISSUER,
        LifecycleTestIdentity.REGISTRATION_ISSUER + "#" + LifecycleTestIdentity.REGISTRATION_SUBJECT,
        LifecycleTestIdentity.REGISTRATION_CLIENT, scope, expected, "synthetic-retention", Duration.ofDays(30));
    var registration = DurableAgentCoreRegistration.create(identity.registrationVerifier(), enrollment,
        Clock.systemUTC(), database.replay, database.operations, 64, Duration.ofSeconds(2));
    var request = JsonSupport.MAPPER.createObjectNode().put("schemaVersion", "1.0")
        .put("clientRequestKey", "container-rehearsal").put("title", "Synthetic container rehearsal");
    var receipt = registration.register(identity.registrationToken(), null, request.toString().getBytes(StandardCharsets.UTF_8));
    if (!receipt.created()) throw new AssertionError("SYNTHETIC_REGISTRATION_NOT_CREATED");
    JsonNode stored = new JdbcBusinessOperationRepository(database.operations).find(scope, receipt.operationId()).orElseThrow();
    LifecycleOperatorConfig parsed = LifecycleOperatorConfig.parse(configuration);
    if (!"require_approval".equals(parsed.authority().evaluate(stored).outcome())) {
      throw new AssertionError("SYNTHETIC_PROFILE_BINDING_MISMATCH");
    }
    OperationAuthorityAdministration.stageNew(database.operationAdmin, parsed.authority());
    OperationAuthorityAdministration.activateStaged(database.operationAdmin, parsed.authority());
    return receipt.operationId();
  }

  byte[] offlineInput() { return frames(bytes(configuration)); }

  byte[] input(String operationId, String command, String selected) {
    ObjectNode value = JsonSupport.MAPPER.createObjectNode().put("schemaVersion", "1.0")
        .put("profile", LifecycleProtocol.PROFILE).put("operationId", operationId);
    if (command.equals("review")) value.put("decision", selected);
    if (command.equals("deliver-synthetic")) value.put("syntheticOutcome", selected);
    String bearer = command.equals("review") ? identity.reviewerToken() : identity.requesterToken();
    if (!command.equals("deliver-synthetic")) return frames(bytes(configuration), bearer.getBytes(StandardCharsets.UTF_8), bytes(value));
    byte[] key = signing.getPrivate().getEncoded();
    try { return frames(bytes(configuration), bearer.getBytes(StandardCharsets.UTF_8), key, bytes(value)); }
    finally { java.util.Arrays.fill(key, (byte) 0); }
  }

  private static byte[] frames(byte[]... frames) {
    try {
      var buffer = new ByteArrayOutputStream();
      var output = new DataOutputStream(buffer);
      for (byte[] frame : frames) { output.writeInt(frame.length); output.write(frame); }
      return buffer.toByteArray();
    } catch (java.io.IOException impossible) { throw new AssertionError("SYNTHETIC_FRAME_ENCODING_FAILED"); }
  }

  private static byte[] bytes(JsonNode value) { return value.toString().getBytes(StandardCharsets.UTF_8); }

  @Override public void close() { if (database != null) database.close(); }
}
