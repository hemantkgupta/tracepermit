package io.tracepermit.agentcore.organizationcli;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.adapters.remote.agentcore.OktaOperationReviewerRoster;
import io.tracepermit.adapters.remote.github.GitHubBusinessOperationBinding;
import io.tracepermit.adapters.remote.github.GitHubOrganizationOperationBinding;
import io.tracepermit.adapters.remote.github.GitHubOrganizationTargetBinding;
import io.tracepermit.application.BusinessOperationScope;
import io.tracepermit.contracts.CanonicalJson;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Runtime-generated cryptographic fixture with deterministic non-secret organization pins. */
final class OrganizationOperatorFixture {
  static final String ISSUER = "https://synthetic.okta.invalid/oauth2/org-operator";
  static final String AUDIENCE = "https://tracepermit.synthetic.invalid/org-operator";
  static final String EXTERNAL_TENANT = "synthetic-org";
  static final String TENANT = "tenant:synthetic-org";
  static final String WORKSPACE = "github-org-sandbox";
  static final String REQUESTER_CLIENT = "tracepermit-org-requester";
  static final String REVIEWER_CLIENT = "tracepermit-org-reviewer";
  static final String REQUESTER_SUBJECT = "00u-synthetic-requester";
  static final String REVIEWER_SUBJECT = "00u-synthetic-reviewer";
  static final String REQUESTER_GROUP = "org-sandbox-requesters";
  static final String REVIEWER_GROUP = "org-sandbox-reviewers";
  static final String REQUESTER_PRINCIPAL = "principal:synthetic-requester-owner";
  static final String HASH = "sha256:" + "a".repeat(64);

  final KeyPair requesterKey = rsa();
  final KeyPair reviewerKey = rsa();
  final KeyPair permitKey = ed25519();
  final ObjectNode configuration;

  OrganizationOperatorFixture() {
    this(REVIEWER_SUBJECT);
  }

  OrganizationOperatorFixture(String reviewerSubject) {
    configuration = configuration(reviewerSubject);
  }

  byte[] configBytes() {
    return configuration.toString().getBytes(StandardCharsets.UTF_8);
  }

  byte[] registrationBytes(String key) {
    return OrganizationOperatorProtocol.JSON
        .createObjectNode()
        .put("schemaVersion", "1.0")
        .put("clientRequestKey", key)
        .put("title", "Synthetic organization change")
        .put("body", "No live GitHub provider is reachable from this fixture.")
        .toString()
        .getBytes(StandardCharsets.UTF_8);
  }

  byte[] commandBytes(String operationId, String command, String value) {
    ObjectNode frame =
        OrganizationOperatorProtocol.JSON
            .createObjectNode()
            .put("schemaVersion", "1.0")
            .put("profile", OrganizationOperatorProtocol.PROFILE)
            .put("operationId", operationId);
    if (command.equals("review")) frame.put("decision", value);
    if (command.equals("deliver-synthetic")) frame.put("syntheticOutcome", value);
    return frame.toString().getBytes(StandardCharsets.UTF_8);
  }

  byte[] requesterToken(String subject, Set<String> scopes) {
    return token(
            requesterKey,
            "requester-key",
            REQUESTER_CLIENT,
            subject,
            REQUESTER_GROUP,
            scopes,
            Duration.ofMinutes(5))
        .getBytes(StandardCharsets.US_ASCII);
  }

  byte[] reviewerToken(String subject) {
    return token(
            reviewerKey,
            "reviewer-key",
            REVIEWER_CLIENT,
            subject,
            REVIEWER_GROUP,
            Set.of("operations.decide"),
            Duration.ofMinutes(5))
        .getBytes(StandardCharsets.US_ASCII);
  }

  byte[] privateKey() {
    return permitKey.getPrivate().getEncoded();
  }

  static byte[] frames(byte[]... values) {
    try {
      var bytes = new ByteArrayOutputStream();
      var output = new DataOutputStream(bytes);
      for (byte[] value : values) {
        output.writeInt(value.length);
        output.write(value);
      }
      return bytes.toByteArray();
    } catch (java.io.IOException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private ObjectNode configuration(String reviewerSubject) {
    var scope = new BusinessOperationScope(TENANT, WORKSPACE, REQUESTER_PRINCIPAL);
    ObjectNode policy =
        OrganizationOperatorProtocol.JSON
            .createObjectNode()
            .put("schemaVersion", "1.0")
            .put("id", "synthetic-github-draft-pr")
            .put("version", 1)
            .put("name", "Synthetic")
            .put("description", "Synthetic only")
            .put("enabled", true);
    policy
        .putObject("defaultDecision")
        .put("outcome", "block")
        .put("reasonCode", "SYNTHETIC_DENY");
    policy
        .putArray("rules")
        .addObject()
        .put("id", "exact-draft-pr")
        .put("priority", 1)
        .put("description", "Synthetic")
        .putObject("match")
        .putArray("toolNames")
        .add("github.pull_request.create");
    ObjectNode rule = (ObjectNode) policy.path("rules").get(0);
    rule
        .putObject("decision")
        .put("outcome", "require_approval")
        .put("reasonCode", "SYNTHETIC_REVIEW")
        .put("approvalGroup", REVIEWER_GROUP);
    String policyHash = CanonicalJson.sha256(policy);
    var target =
        new GitHubOrganizationTargetBinding(
            61,
            71,
            101,
            201,
            "example-sandbox/tracepermit-change-control",
            "tracepermit/fixture-change",
            "main",
            "a".repeat(40),
            "b".repeat(40),
            GitHubOrganizationTargetBinding.requiredPermissions());
    Map<String, String> artifacts = Map.of("policy-bundle", HASH);
    var expected =
        new GitHubOrganizationOperationBinding.ExpectedProfile(
            GitHubOrganizationOperationBinding.ADAPTER_ID,
            "test",
            1,
            new GitHubBusinessOperationBinding.PolicyRelease(
                "synthetic-github-draft-pr", 1, policyHash),
            artifacts,
            target);
    var roster =
        new OktaOperationReviewerRoster(
            List.of(
                new OktaOperationReviewerRoster.Entry(
                    ISSUER + "#" + reviewerSubject,
                    REVIEWER_CLIENT,
                    TENANT,
                    WORKSPACE,
                    REVIEWER_GROUP,
                    true)));
    var artifactList = OrganizationOperatorProtocol.JSON.createArrayNode();
    artifactList.addObject().put("kind", "policy-bundle").put("digest", HASH);

    ObjectNode root = OrganizationOperatorProtocol.JSON.createObjectNode();
    root.put("schemaVersion", "1.0").put("profile", OrganizationOperatorProtocol.PROFILE);
    database(root.putObject("replay"), 54_321, "tp_org_replay", "replay-secret");
    database(root.putObject("operations"), 54_322, "tp_org_operations", "operation-secret");
    root.put("replayCapacity", 64).put("replayDmlMillis", 2_000);
    root.putObject("scope")
        .put("tenantId", TENANT)
        .put("workspaceId", WORKSPACE)
        .put("requesterPrincipalId", REQUESTER_PRINCIPAL);
    ObjectNode configuredTarget = root.putObject("organizationTarget");
    configuredTarget
        .put("adapterId", expected.adapterId())
        .put("environment", expected.environment())
        .put("recoveryGeneration", expected.recoveryGeneration())
        .put("policyId", expected.policyRelease().policyId())
        .put("policyVersion", expected.policyRelease().version())
        .put("policyContentHash", expected.policyRelease().contentHash())
        .put("appId", target.appId())
        .put("installationId", target.installationId())
        .put("repositoryId", target.repositoryId())
        .put("ownerId", target.ownerId())
        .put("repository", target.repository())
        .put("head", target.head())
        .put("base", target.base())
        .put("headSha", target.headSha())
        .put("baseSha", target.baseSha());
    configuredTarget.putObject("artifactDigests").put("policy-bundle", HASH);
    target.permissions().forEach(configuredTarget.putObject("permissions")::put);
    ObjectNode authority = root.putObject("authority");
    authority
        .put("adapterId", expected.adapterId())
        .put("recoveryGeneration", expected.recoveryGeneration())
        .set("policy", policy.deepCopy());
    authority
        .put("artifactDigestsHash", CanonicalJson.sha256(artifactList))
        .put("resourceBindingHash", CanonicalJson.sha256(expected.resourceBinding()))
        .put("reviewerGroupId", REVIEWER_GROUP)
        .put("membershipSourceFingerprint", roster.fingerprint())
        .put("approvalLifetimeSeconds", 300)
        .put("permitIssuer", "https://tracepermit.synthetic.invalid/permit")
        .put("permitKeyId", "synthetic-permit-key")
        .put("permitAudience", "tracepermit-synthetic-delivery")
        .put(
            "permitPublicKeySpki",
            Base64.getEncoder().encodeToString(permitKey.getPublic().getEncoded()))
        .put("permitLifetimeSeconds", 60);
    provider(
        root.putObject("requesterProvider"),
        requesterKey,
        "requester-key",
        REQUESTER_CLIENT,
        REQUESTER_GROUP,
        List.of("operations.evaluate", "operations.execute", "operations.register"));
    provider(
        root.putObject("reviewerProvider"),
        reviewerKey,
        "reviewer-key",
        REVIEWER_CLIENT,
        REVIEWER_GROUP,
        List.of("operations.decide"));
    root.putObject("requesterEnrollment")
        .put("issuer", ISSUER)
        .put("verifiedSubjectId", ISSUER + "#" + REQUESTER_SUBJECT)
        .put("clientId", REQUESTER_CLIENT)
        .put("tenantId", TENANT)
        .put("workspaceId", WORKSPACE)
        .put("requesterPrincipalId", REQUESTER_PRINCIPAL)
        .put("retentionPolicyId", "synthetic-retention")
        .put("retentionSeconds", 3_600);
    root.putArray("reviewerMemberships")
        .addObject()
        .put("verifiedSubjectId", ISSUER + "#" + reviewerSubject)
        .put("clientId", REVIEWER_CLIENT)
        .put("tenantId", TENANT)
        .put("workspaceId", WORKSPACE)
        .put("groupId", REVIEWER_GROUP)
        .put("human", true);
    return root;
  }

  private static void database(
      ObjectNode database, int port, String catalog, String password) {
    database
        .put("engine", "POSTGRESQL_16_4")
        .put("host", "127.0.0.1")
        .put("port", port)
        .put("catalog", catalog)
        .put("user", "tp_org_runtime")
        .put("password", password);
  }

  private static void provider(
      ObjectNode provider,
      KeyPair key,
      String keyId,
      String client,
      String roleGroup,
      List<String> scopes) {
    provider
        .put("issuer", ISSUER)
        .put("audience", AUDIENCE)
        .put("keyId", keyId)
        .put("algorithm", "RS256")
        .put("jwtType", "JWT")
        .put("publicKeySpki", Base64.getEncoder().encodeToString(key.getPublic().getEncoded()))
        .put("tenantClaim", "tracepermit_tenant")
        .put("tenantValue", EXTERNAL_TENANT)
        .put("tenantId", TENANT)
        .put("clientId", client)
        .put("maximumTokenLifetimeSeconds", 900)
        .put("keyCacheSeconds", 300)
        .put("refreshCooldownSeconds", 30)
        .put("jwksUri", ISSUER + "/v1/keys")
        .put("metadataUri", AUDIENCE + "/.well-known/oauth-protected-resource")
        .put("origin", "CLI")
        .put("groupClaim", "groups")
        .put("roleGroup", roleGroup);
    scopes.forEach(value -> provider.withArray("supportedScopes").add(value));
  }

  private String token(
      KeyPair key,
      String keyId,
      String client,
      String subject,
      String group,
      Set<String> scopes,
      Duration lifetime) {
    try {
      Instant now = Instant.now();
      ObjectNode header =
          OrganizationOperatorProtocol.JSON
              .createObjectNode()
              .put("alg", "RS256")
              .put("typ", "JWT")
              .put("kid", keyId);
      ObjectNode claims =
          OrganizationOperatorProtocol.JSON
              .createObjectNode()
              .put("iss", ISSUER)
              .put("aud", AUDIENCE)
              .put("sub", subject)
              .put("cid", client)
              .put("tracepermit_tenant", EXTERNAL_TENANT)
              .put("iat", now.minusSeconds(1).getEpochSecond())
              .put("exp", now.plus(lifetime).getEpochSecond())
              .put("jti", UUID.randomUUID().toString());
      scopes.stream().sorted().forEach(value -> claims.withArray("scp").add(value));
      claims.putArray("groups").add(group);
      Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
      String body =
          encoder.encodeToString(OrganizationOperatorProtocol.JSON.writeValueAsBytes(header))
              + "."
              + encoder.encodeToString(
                  OrganizationOperatorProtocol.JSON.writeValueAsBytes(claims));
      Signature signature = Signature.getInstance("SHA256withRSA");
      signature.initSign(key.getPrivate());
      signature.update(body.getBytes(StandardCharsets.US_ASCII));
      return body + "." + encoder.encodeToString(signature.sign());
    } catch (Exception failure) {
      throw new AssertionError("ORGANIZATION_OPERATOR_TOKEN_SIGNING_FAILED", failure);
    }
  }

  private static KeyPair rsa() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2_048);
      return generator.generateKeyPair();
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  private static KeyPair ed25519() {
    try {
      return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }
}
