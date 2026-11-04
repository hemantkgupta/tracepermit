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
public final class OrganizationOperatorFixture {
  public static final String ISSUER = "https://synthetic.okta.invalid/oauth2/org-operator";
  public static final String AUDIENCE = "https://tracepermit.synthetic.invalid/org-operator";
  public static final String EXTERNAL_TENANT = "synthetic-org";
  public static final String TENANT = "tenant:synthetic-org";
  public static final String WORKSPACE = "github-org-sandbox";
  public static final String REQUESTER_CLIENT = "tracepermit-org-requester";
  public static final String REVIEWER_CLIENT = "tracepermit-org-reviewer";
  public static final String REQUESTER_SUBJECT = "00u-synthetic-requester";
  public static final String REVIEWER_SUBJECT = "00u-synthetic-reviewer";
  public static final String REQUESTER_GROUP = "org-sandbox-requesters";
  public static final String REVIEWER_GROUP = "org-sandbox-reviewers";
  public static final String REQUESTER_PRINCIPAL = "principal:synthetic-requester-owner";
  public static final String HASH = "sha256:" + "a".repeat(64);

  public final KeyPair requesterKey = rsa();
  public final KeyPair reviewerKey = rsa();
  public final KeyPair permitKey = ed25519();
  public final ObjectNode configuration;
  private final boolean auth0;
  public static final String AUTH0_ISSUER = "https://synthetic.au.auth0.com/";
  public static final String AUTH0_REVIEWER = "auth0|synthetic-reviewer";
  public static final String ROLE_CLAIM = "https://tracepermit.example.invalid/claims/roles";
  public static final String TENANT_CLAIM = "https://tracepermit.example.invalid/claims/tenant_id";

  public OrganizationOperatorFixture() {
    this(REVIEWER_SUBJECT);
  }

  public OrganizationOperatorFixture(String reviewerSubject) {
    this(reviewerSubject, false);
  }

  private OrganizationOperatorFixture(String reviewerSubject, boolean auth0) {
    this.auth0 = auth0;
    configuration = configuration(reviewerSubject);
  }

  public static OrganizationOperatorFixture auth0() {
    return new OrganizationOperatorFixture(AUTH0_REVIEWER, true);
  }

  public String issuer() {
    return auth0 ? AUTH0_ISSUER : ISSUER;
  }

  public String requesterSubject() {
    return auth0 ? REQUESTER_CLIENT + "@clients" : REQUESTER_SUBJECT;
  }

  public String reviewerSubject() {
    return auth0 ? AUTH0_REVIEWER : REVIEWER_SUBJECT;
  }

  public byte[] requesterTokenWithClaims(java.util.function.Consumer<ObjectNode> mutation) {
    return token(requesterKey, "requester-key", REQUESTER_CLIENT, requesterSubject(),
        REQUESTER_GROUP, Set.of("operations.execute"), Duration.ofMinutes(5), mutation)
        .getBytes(StandardCharsets.US_ASCII);
  }

  public byte[] reviewerTokenWithClaims(java.util.function.Consumer<ObjectNode> mutation) {
    return token(reviewerKey, "reviewer-key", REVIEWER_CLIENT, reviewerSubject(),
        REVIEWER_GROUP, Set.of("operations.decide"), Duration.ofMinutes(5), mutation)
        .getBytes(StandardCharsets.US_ASCII);
  }

  /** Same synthetic profile with the fixed PKCE helper's exact claim names and tenant mapping. */
  public void useReviewHelperClaims() {
    for (String name : List.of("requesterProvider", "reviewerProvider")) {
      ((ObjectNode)configuration.path(name))
          .put("tenantClaim", "https://tracepermit.org/claims/tenant_id")
          .put("roleClaim", "https://tracepermit.org/claims/roles")
          .put("tenantValue", TENANT);
    }
  }

  public ObjectNode reviewerConfiguration() {
    return OrganizationOperatorProtocol.JSON.createObjectNode()
        .put("schemaVersion", "1.0").put("issuer", issuer()).put("audience", AUDIENCE)
        .put("clientId", REVIEWER_CLIENT).put("expectedSubject", reviewerSubject())
        .put("tenant", TENANT).put("keyId", "reviewer-key")
        .put("publicKeySpki", Base64.getEncoder().encodeToString(reviewerKey.getPublic().getEncoded()))
        .put("callbackUri", "http://127.0.0.1:41839/reviewer/callback");
  }

  public ObjectNode reviewCommand(String operationId, String hash, String decision) {
    var parsed = OrganizationOperatorConfig.parse(configuration);
    return OrganizationOperatorProtocol.JSON.createObjectNode()
        .put("schemaVersion", "1.0").put("profile", Auth0OrganizationReview.PROFILE)
        .put("operationId", operationId).put("expectedSnapshotHash", hash).put("decision", decision)
        .put("authorityProfileFingerprint", parsed.authorityProfile().fingerprint())
        .put("expectedProfileFingerprint", parsed.expectedProfile().fingerprint());
  }

  public byte[] configBytes() {
    return configuration.toString().getBytes(StandardCharsets.UTF_8);
  }

  public byte[] registrationBytes(String key) {
    return OrganizationOperatorProtocol.JSON
        .createObjectNode()
        .put("schemaVersion", "1.0")
        .put("clientRequestKey", key)
        .put("title", "Synthetic organization change")
        .put("body", "No live GitHub provider is reachable from this fixture.")
        .toString()
        .getBytes(StandardCharsets.UTF_8);
  }

  public byte[] commandBytes(String operationId, String command, String value) {
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

  public byte[] requesterToken(String subject, Set<String> scopes) {
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

  public byte[] reviewerToken(String subject) {
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

  public byte[] privateKey() {
    return permitKey.getPrivate().getEncoded();
  }

  public static byte[] frames(byte[]... values) {
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
                    issuer() + "#" + reviewerSubject,
                    REVIEWER_CLIENT,
                    TENANT,
                    WORKSPACE,
                    REVIEWER_GROUP,
                    true)));
    var artifactList = OrganizationOperatorProtocol.JSON.createArrayNode();
    artifactList.addObject().put("kind", "policy-bundle").put("digest", HASH);

    ObjectNode root = OrganizationOperatorProtocol.JSON.createObjectNode();
    root.put("schemaVersion", "1.0").put("profile", OrganizationOperatorProtocol.PROFILE);
    if (auth0) root.put("identityProfile", io.tracepermit.identity.OidcTokenProfile.AUTH0);
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
        .put("issuer", issuer())
        .put("verifiedSubjectId", issuer() + "#" + requesterSubject())
        .put("clientId", REQUESTER_CLIENT)
        .put("tenantId", TENANT)
        .put("workspaceId", WORKSPACE)
        .put("requesterPrincipalId", REQUESTER_PRINCIPAL)
        .put("retentionPolicyId", "synthetic-retention")
        .put("retentionSeconds", 3_600);
    root.putArray("reviewerMemberships")
        .addObject()
        .put("verifiedSubjectId", issuer() + "#" + reviewerSubject)
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

  private void provider(
      ObjectNode provider,
      KeyPair key,
      String keyId,
      String client,
      String roleGroup,
      List<String> scopes) {
    provider
        .put("issuer", issuer())
        .put("audience", AUDIENCE)
        .put("keyId", keyId)
        .put("algorithm", "RS256")
        .put("jwtType", auth0 ? "at+jwt" : "JWT")
        .put("publicKeySpki", Base64.getEncoder().encodeToString(key.getPublic().getEncoded()))
        .put("tenantClaim", auth0 ? TENANT_CLAIM : "tracepermit_tenant")
        .put("tenantValue", EXTERNAL_TENANT)
        .put("tenantId", TENANT)
        .put("clientId", client)
        .put("maximumTokenLifetimeSeconds", auth0 ? 300 : 900)
        .put("keyCacheSeconds", 300)
        .put("refreshCooldownSeconds", 30)
        .put("jwksUri", issuer() + (auth0 ? ".well-known/jwks.json" : "/v1/keys"))
        .put("metadataUri", AUDIENCE + "/.well-known/oauth-protected-resource")
        .put("origin", "CLI")
        .put("groupClaim", "groups")
        .put("roleGroup", roleGroup);
    if (auth0) {
      provider.remove(List.of("groupClaim", "roleGroup"));
      provider.put("roleClaim", ROLE_CLAIM);
    }
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
    return token(key, keyId, client, subject, group, scopes, lifetime, claims -> {});
  }

  private String token(KeyPair key, String keyId, String client, String subject, String group,
      Set<String> scopes, Duration lifetime,
      java.util.function.Consumer<ObjectNode> mutation) {
    try {
      Instant now = Instant.now();
      ObjectNode header =
          OrganizationOperatorProtocol.JSON
              .createObjectNode()
              .put("alg", "RS256")
              .put("typ", auth0 ? "at+jwt" : "JWT")
              .put("kid", keyId);
      ObjectNode claims =
          OrganizationOperatorProtocol.JSON
              .createObjectNode()
              .put("iss", issuer())
              .put("aud", AUDIENCE)
              .put("sub", subject)
              .put("cid", client)
              .put("tracepermit_tenant", EXTERNAL_TENANT)
              .put("iat", now.minusSeconds(1).getEpochSecond())
              .put("exp", now.plus(lifetime).minusSeconds(auth0 ? 1 : 0).getEpochSecond())
              .put("jti", UUID.randomUUID().toString());
      scopes.stream().sorted().forEach(value -> claims.withArray("scp").add(value));
      claims.putArray("groups").add(group);
      if (auth0) {
        claims.remove(List.of("cid", "tracepermit_tenant", "scp", "groups"));
        String providerName = client.equals(REQUESTER_CLIENT) ? "requesterProvider" : "reviewerProvider";
        var configured = configuration.path(providerName);
        claims.put("client_id", client).put(configured.path("tenantClaim").asText(),
                configured.path("tenantValue").asText())
            .put("scope", scopes.stream().sorted().collect(java.util.stream.Collectors.joining(" ")));
        claims.putArray(configured.path("roleClaim").asText())
            .add(client.equals(REQUESTER_CLIENT) ? "REQUESTER" : "REVIEWER");
      }
      mutation.accept(claims);
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
