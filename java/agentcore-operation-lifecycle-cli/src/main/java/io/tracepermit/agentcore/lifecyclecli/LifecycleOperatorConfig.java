package io.tracepermit.agentcore.lifecyclecli;

import static io.tracepermit.agentcore.lifecyclecli.LifecycleProtocol.fields;
import static io.tracepermit.agentcore.lifecyclecli.LifecycleProtocol.number;
import static io.tracepermit.agentcore.lifecyclecli.LifecycleProtocol.text;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.adapters.remote.github.GitHubBusinessOperationBinding;
import io.tracepermit.adapters.remote.github.GitHubPilotTargetBinding;
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
import java.net.URI;
import java.security.KeyFactory;
import java.security.interfaces.EdECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** Closed trusted operator configuration. Parsing is offline and creates no datasource. */
final class LifecycleOperatorConfig {
  record RequesterEnrollment(
      String verifiedSubjectId,
      String clientId,
      String tenantId,
      String workspaceId,
      String requesterPrincipalId) {}

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
              throw new LifecycleFailure("REJECTED");
            }
            return key;
          },
          clock);
    }

    @Override
    public String toString() {
      return "LifecycleProvider[redacted]";
    }
  }

  private static final long MAXIMUM_SAFE_INTEGER = 9_007_199_254_740_991L;
  private final InstalledOperationsRuntime.Plan operations;
  private final BusinessOperationScope scope;
  private final GitHubBusinessOperationBinding.ExpectedProfile expected;
  private final BusinessOperationAuthorityProfile authority;
  private final Provider requesterProvider;
  private final Provider reviewerProvider;
  private final RequesterEnrollment requesterEnrollment;
  private final LifecycleMemberships memberships;

  private LifecycleOperatorConfig(
      InstalledOperationsRuntime.Plan operations,
      BusinessOperationScope scope,
      GitHubBusinessOperationBinding.ExpectedProfile expected,
      BusinessOperationAuthorityProfile authority,
      Provider requesterProvider,
      Provider reviewerProvider,
      RequesterEnrollment requesterEnrollment,
      LifecycleMemberships memberships) {
    this.operations = operations;
    this.scope = scope;
    this.expected = expected;
    this.authority = authority;
    this.requesterProvider = requesterProvider;
    this.reviewerProvider = reviewerProvider;
    this.requesterEnrollment = requesterEnrollment;
    this.memberships = memberships;
  }

  static LifecycleOperatorConfig parse(JsonNode root) {
    try {
      fields(
          root,
          Set.of(
              "schemaVersion",
              "profile",
              "operations",
              "scope",
              "expectedTarget",
              "authority",
              "requesterProvider",
              "reviewerProvider",
              "requesterEnrollment",
              "reviewerMemberships"),
          Set.of());
      require(
          "1.0".equals(text(root, "schemaVersion", 3))
              && LifecycleProtocol.PROFILE.equals(text(root, "profile", 96)));

      InstalledOperationsRuntime.Plan operations =
          InstalledOperationsRuntime.parse(root.get("operations"));
      BusinessOperationScope scope = scope(root.get("scope"));
      Expected expected = expected(root.get("expectedTarget"));
      LifecycleMemberships memberships =
          LifecycleMemberships.parse(root.get("reviewerMemberships"));
      Authority authority = authority(root.get("authority"), scope, expected, memberships);
      Provider requesterProvider =
          provider(
              root.get("requesterProvider"),
              scope.tenantId(),
              Set.of("operations.evaluate", "operations.execute"));
      Provider reviewerProvider =
          provider(
              root.get("reviewerProvider"),
              scope.tenantId(),
              Set.of("operations.decide"));
      RequesterEnrollment enrollment =
          requesterEnrollment(root.get("requesterEnrollment"), scope, requesterProvider);
      memberships.requireProfile(
          scope.tenantId(),
          scope.workspaceId(),
          reviewerProvider.clientId(),
          authority.profile().reviewSettings().reviewerGroupId());
      return new LifecycleOperatorConfig(
          operations,
          scope,
          expected.profile(),
          authority.profile(),
          requesterProvider,
          reviewerProvider,
          enrollment,
          memberships);
    } catch (LifecycleFailure failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new LifecycleFailure("CONFIG_INVALID");
    }
  }

  private static BusinessOperationScope scope(JsonNode value) {
    fields(
        value,
        Set.of("tenantId", "workspaceId", "requesterPrincipalId"),
        Set.of());
    return new BusinessOperationScope(
        text(value, "tenantId", 128),
        text(value, "workspaceId", 128),
        text(value, "requesterPrincipalId", 256));
  }

  private record Expected(
      GitHubBusinessOperationBinding.ExpectedProfile profile,
      String artifactDigestsHash,
      String resourceBindingHash,
      String policyId,
      long policyVersion,
      String policyContentHash) {}

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
            "installationId",
            "repositoryId",
            "ownerId",
            "repository",
            "head",
            "base",
            "headSha",
            "baseSha"),
        Set.of());
    JsonNode artifacts = value.get("artifactDigests");
    if (artifacts == null || !artifacts.isObject() || artifacts.isEmpty() || artifacts.size() > 16) {
      throw new LifecycleFailure("CONFIG_INVALID");
    }
    Map<String, String> digests = new HashMap<>();
    artifacts.fieldNames()
        .forEachRemaining(
            name -> {
              if (!name.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
                throw new LifecycleFailure("CONFIG_INVALID");
              }
              String digest = text(artifacts, name, 71);
              require(hash(digest) && digests.putIfAbsent(name, digest) == null);
            });
    String policyId = text(value, "policyId", 256);
    long policyVersion = number(value, "policyVersion", 1, MAXIMUM_SAFE_INTEGER);
    String policyHash = text(value, "policyContentHash", 71);
    require(hash(policyHash));
    var target =
        new GitHubPilotTargetBinding(
            number(value, "installationId", 1, MAXIMUM_SAFE_INTEGER),
            number(value, "repositoryId", 1, MAXIMUM_SAFE_INTEGER),
            number(value, "ownerId", 1, MAXIMUM_SAFE_INTEGER),
            text(value, "repository", 256),
            text(value, "head", 255),
            text(value, "base", 255),
            text(value, "headSha", 40),
            text(value, "baseSha", 40));
    var profile =
        new GitHubBusinessOperationBinding.ExpectedProfile(
            text(value, "adapterId", 128),
            text(value, "environment", 16),
            number(value, "recoveryGeneration", 1, MAXIMUM_SAFE_INTEGER),
            new GitHubBusinessOperationBinding.PolicyRelease(
                policyId, policyVersion, policyHash),
            digests,
            target);
    var artifactList = LifecycleProtocol.JSON.createArrayNode();
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

  private record Authority(BusinessOperationAuthorityProfile profile) {}

  private static Authority authority(
      JsonNode value,
      BusinessOperationScope scope,
      Expected expected,
      LifecycleMemberships memberships) {
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
    if (policy == null || !policy.isObject()) throw new LifecycleFailure("CONFIG_INVALID");
    String artifactHash = text(value, "artifactDigestsHash", 71);
    String resourceHash = text(value, "resourceBindingHash", 71);
    String membershipHash = text(value, "membershipSourceFingerprint", 71);
    require(
        hash(artifactHash)
            && hash(resourceHash)
            && hash(membershipHash)
            && artifactHash.equals(expected.artifactDigestsHash())
            && resourceHash.equals(expected.resourceBindingHash())
            && membershipHash.equals(memberships.fingerprint())
            && text(value, "adapterId", 128).equals(expected.profile().adapterId())
            && number(value, "recoveryGeneration", 1, MAXIMUM_SAFE_INTEGER)
                == expected.profile().recoveryGeneration()
            && expected.policyId().equals(policy.path("id").asText())
            && expected.policyVersion() == policy.path("version").asLong()
            && expected.policyContentHash().equals(CanonicalJson.sha256(policy)));
    byte[] encoded = Base64.getDecoder().decode(text(value, "permitPublicKeySpki", 128));
    require(Base64.getEncoder().encodeToString(encoded).equals(text(value, "permitPublicKeySpki", 128)));
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
    return new Authority(
        new BusinessOperationAuthorityProfile(
            scope,
            text(value, "adapterId", 128),
            number(value, "recoveryGeneration", 1, MAXIMUM_SAFE_INTEGER),
            policy,
            artifactHash,
            resourceHash,
            review,
            admission));
  }

  private static Provider provider(
      JsonNode value, String tenantId, Set<String> exactScopes) {
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
            "supportedScopes"),
        Set.of());
    require(
        "RS256".equals(text(value, "algorithm", 8))
            && "at+jwt".equals(text(value, "jwtType", 16))
            && "CLI".equals(text(value, "origin", 8))
            && tenantId.equals(text(value, "tenantId", 128)));
    byte[] encoded = Base64.getDecoder().decode(text(value, "publicKeySpki", 4_096));
    require(Base64.getEncoder().encodeToString(encoded).equals(text(value, "publicKeySpki", 4_096)));
    var key = rsa(encoded);
    require(key.getModulus().bitLength() >= 2_048 && key.getModulus().bitLength() <= 4_096);
    String keyId = text(value, "keyId", 256);
    require(keyId.matches("[A-Za-z0-9][A-Za-z0-9._:@/+=-]{0,255}"));
    JsonNode configuredScopes = value.get("supportedScopes");
    if (configuredScopes == null
        || !configuredScopes.isArray()
        || configuredScopes.isEmpty()
        || configuredScopes.size() > 16) {
      throw new LifecycleFailure("CONFIG_INVALID");
    }
    Set<ConnectorScope> scopes = new HashSet<>();
    for (JsonNode configured : configuredScopes) {
      if (!configured.isTextual()
          || !configured.textValue().matches("[a-z][a-z0-9._:-]{0,127}")
          || !scopes.add(ConnectorScope.of(configured.textValue()))) {
        throw new LifecycleFailure("CONFIG_INVALID");
      }
    }
    require(
        scopes.stream().map(ConnectorScope::value).collect(java.util.stream.Collectors.toSet())
            .equals(exactScopes));
    String issuer = text(value, "issuer", 512);
    String clientId = text(value, "clientId", 256);
    var configuration =
        new OidcProviderConfiguration(
            URI.create(issuer),
            text(value, "audience", 512),
            URI.create(text(value, "jwksUri", 512)),
            URI.create(text(value, "metadataUri", 512)),
            List.of(URI.create(issuer)),
            "Installed synthetic operation lifecycle",
            Map.of(clientId, "Installed synthetic lifecycle client"),
            text(value, "tenantClaim", 256),
            Map.of(text(value, "tenantValue", 256), tenantId),
            scopes,
            ConnectorOrigin.CLI,
            Duration.ofSeconds(number(value, "maximumTokenLifetimeSeconds", 1, 900)),
            Duration.ofSeconds(number(value, "keyCacheSeconds", 1, 900)),
            Duration.ofSeconds(number(value, "refreshCooldownSeconds", 1, 900)));
    return new Provider(configuration, key, keyId, clientId);
  }

  private static RequesterEnrollment requesterEnrollment(
      JsonNode value, BusinessOperationScope scope, Provider provider) {
    fields(
        value,
        Set.of(
            "verifiedSubjectId", "clientId", "tenantId", "workspaceId", "requesterPrincipalId"),
        Set.of());
    var enrollment =
        new RequesterEnrollment(
            text(value, "verifiedSubjectId", 1024),
            text(value, "clientId", 256),
            text(value, "tenantId", 128),
            text(value, "workspaceId", 128),
            text(value, "requesterPrincipalId", 256));
    require(
        enrollment.clientId().equals(provider.clientId())
            && enrollment.tenantId().equals(scope.tenantId())
            && enrollment.workspaceId().equals(scope.workspaceId())
            && enrollment.requesterPrincipalId().equals(scope.requesterPrincipalId()));
    return enrollment;
  }

  InstalledOperationsRuntime.Plan operations() {
    return operations;
  }

  BusinessOperationScope scope() {
    return scope;
  }

  GitHubBusinessOperationBinding.ExpectedProfile expected() {
    return expected;
  }

  BusinessOperationAuthorityProfile authority() {
    return authority;
  }

  Provider requesterProvider() {
    return requesterProvider;
  }

  Provider reviewerProvider() {
    return reviewerProvider;
  }

  RequesterEnrollment requesterEnrollment() {
    return requesterEnrollment;
  }

  LifecycleMemberships memberships() {
    return memberships;
  }

  private static boolean hash(String value) {
    return value != null && value.matches("sha256:[a-f0-9]{64}");
  }

  private static EdECPublicKey ed25519(byte[] encoded) {
    try {
      return (EdECPublicKey)
          KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
    } catch (Exception invalid) {
      throw new LifecycleFailure("CONFIG_INVALID");
    }
  }

  private static RSAPublicKey rsa(byte[] encoded) {
    try {
      return (RSAPublicKey)
          KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
    } catch (Exception invalid) {
      throw new LifecycleFailure("CONFIG_INVALID");
    }
  }

  private static void require(boolean condition) {
    if (!condition) throw new LifecycleFailure("CONFIG_INVALID");
  }

  @Override
  public String toString() {
    return "LifecycleOperatorConfig[redacted]";
  }
}
