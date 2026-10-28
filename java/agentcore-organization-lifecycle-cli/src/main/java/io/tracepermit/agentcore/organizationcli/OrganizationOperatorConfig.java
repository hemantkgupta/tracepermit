package io.tracepermit.agentcore.organizationcli;

import static io.tracepermit.agentcore.organizationcli.OrganizationOperatorProtocol.fields;
import static io.tracepermit.agentcore.organizationcli.OrganizationOperatorProtocol.number;
import static io.tracepermit.agentcore.organizationcli.OrganizationOperatorProtocol.text;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.adapters.remote.agentcore.AgentCoreRegistrationIngress;
import io.tracepermit.adapters.remote.agentcore.OktaOperationAuthority;
import io.tracepermit.adapters.remote.agentcore.OktaOperationReviewerRoster;
import io.tracepermit.adapters.remote.github.GitHubBusinessOperationBinding;
import io.tracepermit.adapters.remote.github.GitHubOrganizationOperationBinding;
import io.tracepermit.adapters.remote.github.GitHubOrganizationTargetBinding;
import io.tracepermit.agentcore.cli.InstalledOperationsRuntime;
import io.tracepermit.application.BusinessOperationAdmissionSettings;
import io.tracepermit.application.BusinessOperationAuthorityProfile;
import io.tracepermit.application.BusinessOperationReviewSettings;
import io.tracepermit.application.BusinessOperationScope;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.identity.ConnectorOrigin;
import io.tracepermit.identity.ConnectorScope;
import io.tracepermit.identity.OidcAccessTokenVerifier;
import io.tracepermit.identity.OidcProviderConfiguration;
import io.tracepermit.identity.OidcTokenProfile;
import java.net.URI;
import java.security.KeyFactory;
import java.security.interfaces.EdECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Closed organization operator configuration. Parsing is offline and opens no connection. */
final class OrganizationOperatorConfig {
  private static final long MAXIMUM_SAFE_INTEGER = 9_007_199_254_740_991L;
  private static final long REPLAY_CAPACITY = 64;
  private static final Duration REPLAY_DML_BUDGET = Duration.ofSeconds(2);

  record Provider(
      OidcProviderConfiguration configuration,
      RSAPublicKey key,
      String keyId,
      String clientId) {
    OidcAccessTokenVerifier verifier(Clock clock) {
      return new OidcAccessTokenVerifier(
          issuer ->
              configuration.issuer().toString().equals(issuer)
                  ? Optional.of(configuration)
                  : Optional.empty(),
          (candidate, requestedKey, algorithm, observed) -> {
            if (candidate != configuration
                || !keyId.equals(requestedKey)
                || !"RS256".equals(algorithm)) {
              throw new OrganizationFailure("REJECTED");
            }
            return key;
          },
          clock);
    }

    @Override
    public String toString() {
      return "OrganizationOperatorProvider[redacted]";
    }
  }

  private record Expected(
      GitHubOrganizationOperationBinding.ExpectedProfile profile,
      String artifactDigestsHash,
      String resourceBindingHash,
      String policyId,
      long policyVersion,
      String policyContentHash) {}

  private final InstalledOperationsRuntime.Plan replay;
  private final InstalledOperationsRuntime.Plan operations;
  private final BusinessOperationScope scope;
  private final GitHubOrganizationOperationBinding.ExpectedProfile expectedProfile;
  private final BusinessOperationAuthorityProfile authorityProfile;
  private final Provider requesterProvider;
  private final Provider reviewerProvider;
  private final AgentCoreRegistrationIngress.OrganizationEnrollment registrationEnrollment;
  private final OktaOperationAuthority.RequesterEnrollment requesterEnrollment;
  private final OktaOperationReviewerRoster reviewerRoster;
  private final String reviewerClientId;

  private OrganizationOperatorConfig(
      InstalledOperationsRuntime.Plan replay,
      InstalledOperationsRuntime.Plan operations,
      BusinessOperationScope scope,
      GitHubOrganizationOperationBinding.ExpectedProfile expectedProfile,
      BusinessOperationAuthorityProfile authorityProfile,
      Provider requesterProvider,
      Provider reviewerProvider,
      AgentCoreRegistrationIngress.OrganizationEnrollment registrationEnrollment,
      OktaOperationAuthority.RequesterEnrollment requesterEnrollment,
      OktaOperationReviewerRoster reviewerRoster,
      String reviewerClientId) {
    this.replay = replay;
    this.operations = operations;
    this.scope = scope;
    this.expectedProfile = expectedProfile;
    this.authorityProfile = authorityProfile;
    this.requesterProvider = requesterProvider;
    this.reviewerProvider = reviewerProvider;
    this.registrationEnrollment = registrationEnrollment;
    this.requesterEnrollment = requesterEnrollment;
    this.reviewerRoster = reviewerRoster;
    this.reviewerClientId = reviewerClientId;
  }

  static OrganizationOperatorConfig parse(JsonNode root) {
    try {
      fields(
          root,
          Set.of(
              "schemaVersion",
              "profile",
              "replay",
              "operations",
              "replayCapacity",
              "replayDmlMillis",
              "scope",
              "organizationTarget",
              "authority",
              "requesterProvider",
              "reviewerProvider",
              "requesterEnrollment",
              "reviewerMemberships"),
          Set.of());
      require(
          "1.0".equals(text(root, "schemaVersion", 3))
              && OrganizationOperatorProtocol.PROFILE.equals(text(root, "profile", 96))
              && number(root, "replayCapacity", REPLAY_CAPACITY, REPLAY_CAPACITY)
                  == REPLAY_CAPACITY
              && number(root, "replayDmlMillis", 2_000, 2_000) == 2_000);

      Database replayDatabase = database(root.get("replay"));
      Database operationDatabase = database(root.get("operations"));
      require(
          replayDatabase.port() != operationDatabase.port()
              || !replayDatabase.catalog().equals(operationDatabase.catalog()));
      InstalledOperationsRuntime.Plan replay = InstalledOperationsRuntime.parse(root.get("replay"));
      InstalledOperationsRuntime.Plan operations =
          InstalledOperationsRuntime.parse(root.get("operations"));
      BusinessOperationScope scope = scope(root.get("scope"));
      Expected expected = expected(root.get("organizationTarget"));
      OktaOperationReviewerRoster roster = roster(root.get("reviewerMemberships"));
      BusinessOperationAuthorityProfile authority =
          authority(root.get("authority"), scope, expected, roster);
      Provider requester =
          provider(
              root.get("requesterProvider"),
              scope.tenantId(),
              Set.of("operations.register", "operations.evaluate", "operations.execute"),
              "REQUESTER");
      Provider reviewer =
          provider(
              root.get("reviewerProvider"),
              scope.tenantId(),
              Set.of("operations.decide"),
              "REVIEWER");
      require(!requester.clientId().equals(reviewer.clientId()));
      Enrollment enrollment =
          enrollment(root.get("requesterEnrollment"), scope, expected.profile(), requester);
      // Constructor validation pins the complete profile, independent roster and enrollment.
      new OktaOperationAuthority(
          requester.verifier(Clock.systemUTC()),
          reviewer.verifier(Clock.systemUTC()),
          scope,
          enrollment.authority(),
          roster,
          authority,
          reviewer.clientId(),
          Clock.systemUTC());
      return new OrganizationOperatorConfig(
          replay,
          operations,
          scope,
          expected.profile(),
          authority,
          requester,
          reviewer,
          enrollment.registration(),
          enrollment.authority(),
          roster,
          reviewer.clientId());
    } catch (OrganizationFailure failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new OrganizationFailure("CONFIG_INVALID");
    }
  }

  private record Database(int port, String catalog) {}

  private static Database database(JsonNode value) {
    fields(value, Set.of("engine", "host", "port", "catalog", "user", "password"), Set.of());
    require(
        "POSTGRESQL_16_4".equals(text(value, "engine", 32))
            && "127.0.0.1".equals(text(value, "host", 16)));
    String catalog = text(value, "catalog", 63);
    String user = text(value, "user", 63);
    require(
        catalog.matches("[A-Za-z][A-Za-z0-9_]{0,62}")
            && user.matches("[A-Za-z][A-Za-z0-9_]{0,62}"));
    text(value, "password", 256);
    return new Database((int) number(value, "port", 1, 65_535), catalog);
  }

  private static BusinessOperationScope scope(JsonNode value) {
    fields(value, Set.of("tenantId", "workspaceId", "requesterPrincipalId"), Set.of());
    return new BusinessOperationScope(
        text(value, "tenantId", 128),
        text(value, "workspaceId", 128),
        text(value, "requesterPrincipalId", 256));
  }

  private static Expected expected(JsonNode value) {
    fields(
        value,
        Set.of(
            "adapterId",
            "environment",
            "recoveryGeneration",
            "policyId",
            "policyVersion",
            "policyContentHash",
            "artifactDigests",
            "appId",
            "installationId",
            "repositoryId",
            "ownerId",
            "repository",
            "head",
            "base",
            "headSha",
            "baseSha",
            "permissions"),
        Set.of());
    Map<String, String> digests = stringMap(value.get("artifactDigests"), 16, 128, 71);
    digests.forEach((kind, digest) -> require(hash(digest)));
    Map<String, String> permissions = stringMap(value.get("permissions"), 3, 32, 16);
    require(permissions.equals(GitHubOrganizationTargetBinding.requiredPermissions()));
    String policyId = text(value, "policyId", 256);
    long policyVersion = number(value, "policyVersion", 1, MAXIMUM_SAFE_INTEGER);
    String policyHash = text(value, "policyContentHash", 71);
    require(hash(policyHash));
    var target =
        new GitHubOrganizationTargetBinding(
            number(value, "appId", 1, MAXIMUM_SAFE_INTEGER),
            number(value, "installationId", 1, MAXIMUM_SAFE_INTEGER),
            number(value, "repositoryId", 1, MAXIMUM_SAFE_INTEGER),
            number(value, "ownerId", 1, MAXIMUM_SAFE_INTEGER),
            text(value, "repository", 140),
            text(value, "head", 255),
            text(value, "base", 255),
            text(value, "headSha", 40),
            text(value, "baseSha", 40),
            permissions);
    var profile =
        new GitHubOrganizationOperationBinding.ExpectedProfile(
            text(value, "adapterId", 128),
            text(value, "environment", 16),
            number(value, "recoveryGeneration", 1, MAXIMUM_SAFE_INTEGER),
            new GitHubBusinessOperationBinding.PolicyRelease(
                policyId, policyVersion, policyHash),
            digests,
            target);
    var artifactList = OrganizationOperatorProtocol.JSON.createArrayNode();
    new TreeMap<>(digests)
        .forEach(
            (kind, digest) ->
                artifactList.addObject().put("kind", kind).put("digest", digest));
    return new Expected(
        profile,
        CanonicalJson.sha256(artifactList),
        CanonicalJson.sha256(profile.resourceBinding()),
        policyId,
        policyVersion,
        policyHash);
  }

  private static BusinessOperationAuthorityProfile authority(
      JsonNode value,
      BusinessOperationScope scope,
      Expected expected,
      OktaOperationReviewerRoster roster) {
    fields(
        value,
        Set.of(
            "adapterId",
            "recoveryGeneration",
            "policy",
            "artifactDigestsHash",
            "resourceBindingHash",
            "reviewerGroupId",
            "membershipSourceFingerprint",
            "approvalLifetimeSeconds",
            "permitIssuer",
            "permitKeyId",
            "permitAudience",
            "permitPublicKeySpki",
            "permitLifetimeSeconds"),
        Set.of());
    JsonNode policy = value.get("policy");
    require(policy != null && policy.isObject());
    String artifactHash = text(value, "artifactDigestsHash", 71);
    String resourceHash = text(value, "resourceBindingHash", 71);
    String membershipHash = text(value, "membershipSourceFingerprint", 71);
    require(
        hash(artifactHash)
            && hash(resourceHash)
            && hash(membershipHash)
            && artifactHash.equals(expected.artifactDigestsHash())
            && resourceHash.equals(expected.resourceBindingHash())
            && membershipHash.equals(roster.fingerprint())
            && text(value, "adapterId", 128).equals(expected.profile().adapterId())
            && number(value, "recoveryGeneration", 1, MAXIMUM_SAFE_INTEGER)
                == expected.profile().recoveryGeneration()
            && expected.policyId().equals(policy.path("id").asText())
            && expected.policyVersion() == policy.path("version").asLong()
            && expected.policyContentHash().equals(CanonicalJson.sha256(policy)));
    byte[] encoded = canonicalBase64(value, "permitPublicKeySpki", 128);
    var publicKey = ed25519(encoded);
    require("Ed25519".equals(publicKey.getParams().getName()));
    var review =
        new BusinessOperationReviewSettings(
            text(value, "reviewerGroupId", 256),
            membershipHash,
            Duration.ofSeconds(number(value, "approvalLifetimeSeconds", 1, 300)));
    var admission =
        new BusinessOperationAdmissionSettings(
            text(value, "permitIssuer", 512),
            text(value, "permitKeyId", 128),
            text(value, "permitAudience", 256),
            publicKey,
            Duration.ofSeconds(number(value, "permitLifetimeSeconds", 1, 60)));
    return new BusinessOperationAuthorityProfile(
        scope,
        text(value, "adapterId", 128),
        number(value, "recoveryGeneration", 1, MAXIMUM_SAFE_INTEGER),
        policy,
        artifactHash,
        resourceHash,
        review,
        admission);
  }

  private static Provider provider(
      JsonNode value, String tenantId, Set<String> exactScopes, String role) {
    fields(
        value,
        Set.of(
            "issuer",
            "audience",
            "keyId",
            "algorithm",
            "jwtType",
            "publicKeySpki",
            "tenantClaim",
            "tenantValue",
            "tenantId",
            "clientId",
            "maximumTokenLifetimeSeconds",
            "keyCacheSeconds",
            "refreshCooldownSeconds",
            "jwksUri",
            "metadataUri",
            "origin",
            "groupClaim",
            "roleGroup",
            "supportedScopes"),
        Set.of());
    require(
        "RS256".equals(text(value, "algorithm", 8))
            && "JWT".equals(text(value, "jwtType", 16))
            && "CLI".equals(text(value, "origin", 8))
            && tenantId.equals(text(value, "tenantId", 128)));
    byte[] encoded = canonicalBase64(value, "publicKeySpki", 4_096);
    var key = rsa(encoded);
    require(key.getModulus().bitLength() >= 2_048 && key.getModulus().bitLength() <= 4_096);
    String keyId = text(value, "keyId", 256);
    require(keyId.matches("[A-Za-z0-9][A-Za-z0-9._:@/+=-]{0,255}"));
    Set<ConnectorScope> scopes = scopes(value.get("supportedScopes"));
    require(
        scopes.stream()
            .map(ConnectorScope::value)
            .collect(Collectors.toSet())
            .equals(exactScopes));
    String issuer = text(value, "issuer", 512);
    String clientId = text(value, "clientId", 256);
    String groupClaim = text(value, "groupClaim", 64);
    String roleGroup = text(value, "roleGroup", 128);
    var configuration =
        new OidcProviderConfiguration(
            URI.create(issuer),
            text(value, "audience", 512),
            URI.create(text(value, "jwksUri", 512)),
            URI.create(text(value, "metadataUri", 512)),
            List.of(URI.create(issuer)),
            "Installed organization synthetic operator",
            Map.of(clientId, "Installed organization operator client"),
            text(value, "tenantClaim", 256),
            Map.of(text(value, "tenantValue", 256), tenantId),
            scopes,
            ConnectorOrigin.CLI,
            Duration.ofSeconds(number(value, "maximumTokenLifetimeSeconds", 1, 900)),
            Duration.ofSeconds(number(value, "keyCacheSeconds", 1, 900)),
            Duration.ofSeconds(number(value, "refreshCooldownSeconds", 1, 900)),
            OidcTokenProfile.okta(groupClaim, Map.of(roleGroup, role)));
    return new Provider(configuration, key, keyId, clientId);
  }

  private record Enrollment(
      AgentCoreRegistrationIngress.OrganizationEnrollment registration,
      OktaOperationAuthority.RequesterEnrollment authority) {}

  private static Enrollment enrollment(
      JsonNode value,
      BusinessOperationScope scope,
      GitHubOrganizationOperationBinding.ExpectedProfile profile,
      Provider requester) {
    fields(
        value,
        Set.of(
            "issuer",
            "verifiedSubjectId",
            "clientId",
            "tenantId",
            "workspaceId",
            "requesterPrincipalId",
            "retentionPolicyId",
            "retentionSeconds"),
        Set.of());
    String issuer = text(value, "issuer", 512);
    String subject = text(value, "verifiedSubjectId", 1_024);
    String client = text(value, "clientId", 256);
    require(
        issuer.equals(requester.configuration().issuer().toString())
            && client.equals(requester.clientId())
            && text(value, "tenantId", 128).equals(scope.tenantId())
            && text(value, "workspaceId", 128).equals(scope.workspaceId())
            && text(value, "requesterPrincipalId", 256).equals(scope.requesterPrincipalId()));
    var registration =
        new AgentCoreRegistrationIngress.OrganizationEnrollment(
            issuer,
            subject,
            client,
            scope,
            profile,
            text(value, "retentionPolicyId", 256),
            Duration.ofSeconds(number(value, "retentionSeconds", 1, 31_536_000)));
    var authority =
        new OktaOperationAuthority.RequesterEnrollment(
            issuer,
            subject,
            client,
            scope.tenantId(),
            scope.workspaceId(),
            scope.requesterPrincipalId());
    return new Enrollment(registration, authority);
  }

  private static OktaOperationReviewerRoster roster(JsonNode value) {
    if (value == null || !value.isArray() || value.isEmpty() || value.size() > 32) {
      throw new OrganizationFailure("CONFIG_INVALID");
    }
    List<OktaOperationReviewerRoster.Entry> entries = new ArrayList<>();
    for (JsonNode row : value) {
      fields(
          row,
          Set.of(
              "verifiedSubjectId", "clientId", "tenantId", "workspaceId", "groupId", "human"),
          Set.of());
      if (!row.path("human").isBoolean() || !row.path("human").booleanValue()) {
        throw new OrganizationFailure("CONFIG_INVALID");
      }
      entries.add(
          new OktaOperationReviewerRoster.Entry(
              text(row, "verifiedSubjectId", 1_024),
              text(row, "clientId", 256),
              text(row, "tenantId", 128),
              text(row, "workspaceId", 128),
              text(row, "groupId", 256),
              true));
    }
    return new OktaOperationReviewerRoster(entries);
  }

  private static Map<String, String> stringMap(
      JsonNode value, int maximumEntries, int maximumName, int maximumValue) {
    if (value == null || !value.isObject() || value.isEmpty() || value.size() > maximumEntries) {
      throw new OrganizationFailure("CONFIG_INVALID");
    }
    Map<String, String> result = new HashMap<>();
    value.fieldNames()
        .forEachRemaining(
            name -> {
              if (!name.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0," + (maximumName - 1) + "}")) {
                throw new OrganizationFailure("CONFIG_INVALID");
              }
              require(result.putIfAbsent(name, text(value, name, maximumValue)) == null);
            });
    return Map.copyOf(result);
  }

  private static Set<ConnectorScope> scopes(JsonNode value) {
    if (value == null || !value.isArray() || value.isEmpty() || value.size() > 16) {
      throw new OrganizationFailure("CONFIG_INVALID");
    }
    Set<ConnectorScope> result = new HashSet<>();
    for (JsonNode scope : value) {
      if (!scope.isTextual()
          || !scope.textValue().matches("[a-z][a-z0-9._:-]{0,127}")
          || !result.add(ConnectorScope.of(scope.textValue()))) {
        throw new OrganizationFailure("CONFIG_INVALID");
      }
    }
    return Set.copyOf(result);
  }

  private static byte[] canonicalBase64(JsonNode value, String field, int maximum) {
    String encoded = text(value, field, maximum);
    byte[] decoded = Base64.getDecoder().decode(encoded);
    require(Base64.getEncoder().encodeToString(decoded).equals(encoded));
    return decoded;
  }

  private static EdECPublicKey ed25519(byte[] encoded) {
    try {
      return (EdECPublicKey)
          KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
    } catch (Exception invalid) {
      throw new OrganizationFailure("CONFIG_INVALID");
    }
  }

  private static RSAPublicKey rsa(byte[] encoded) {
    try {
      return (RSAPublicKey)
          KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
    } catch (Exception invalid) {
      throw new OrganizationFailure("CONFIG_INVALID");
    }
  }

  private static boolean hash(String value) {
    return value != null && value.matches("sha256:[a-f0-9]{64}");
  }

  private static void require(boolean condition) {
    if (!condition) throw new OrganizationFailure("CONFIG_INVALID");
  }

  InstalledOperationsRuntime.Plan replay() {
    return replay;
  }

  InstalledOperationsRuntime.Plan operations() {
    return operations;
  }

  long replayCapacity() {
    return REPLAY_CAPACITY;
  }

  Duration replayDmlBudget() {
    return REPLAY_DML_BUDGET;
  }

  BusinessOperationScope scope() {
    return scope;
  }

  GitHubOrganizationOperationBinding.ExpectedProfile expectedProfile() {
    return expectedProfile;
  }

  BusinessOperationAuthorityProfile authorityProfile() {
    return authorityProfile;
  }

  AgentCoreRegistrationIngress.OrganizationEnrollment registrationEnrollment() {
    return registrationEnrollment;
  }

  OidcAccessTokenVerifier requesterVerifier(Clock clock) {
    return requesterProvider.verifier(clock);
  }

  OktaOperationAuthority authority(Clock clock) {
    return new OktaOperationAuthority(
        requesterProvider.verifier(clock),
        reviewerProvider.verifier(clock),
        scope,
        requesterEnrollment,
        reviewerRoster,
        authorityProfile,
        reviewerClientId,
        clock);
  }

  @Override
  public String toString() {
    return "OrganizationOperatorConfig[redacted]";
  }
}
