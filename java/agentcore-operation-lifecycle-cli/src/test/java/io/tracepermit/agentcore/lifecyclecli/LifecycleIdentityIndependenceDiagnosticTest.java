package io.tracepermit.agentcore.lifecyclecli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.adapters.remote.github.GitHubBusinessOperationBinding;
import io.tracepermit.adapters.remote.github.GitHubPilotTargetBinding;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Permission;
import java.security.Signature;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Offline regression for requester-alias self-review reproduced at published main
 * 35a791ffeba1a6b0b10e3e072e97f1085d80eed3. Valid signed tokens for one issuer-qualified
 * subject must not become independent through aliases or clients. No runtime, repository,
 * listener, worker or container is started; accepted contexts are not committed approvals.
 */
@SuppressWarnings("removal")
@Isolated("Temporarily traps network, process creation and DriverManager connections")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(20)
final class LifecycleIdentityIndependenceDiagnosticTest {
  private static final Instant NOW = Instant.parse("2030-01-02T03:04:05Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final String ISSUER = "https://identity.example.invalid";
  private static final String OTHER_ISSUER = "https://other-identity.example.invalid";
  private static final String AUDIENCE = "https://lifecycle.example.invalid";
  private static final String TENANT = "synthetic-tenant";
  private static final String WORKSPACE = "synthetic-workspace";
  private static final String GROUP = "synthetic-reviewers";
  private static final String REQUESTER_SCOPES = "operations.evaluate operations.execute";
  private final OfflineBoundary boundary = new OfflineBoundary();
  private final JdbcTrap jdbc = new JdbcTrap();
  private List<Driver> savedDrivers = List.of();
  private KeyPair issuerKey;
  private KeyPair otherIssuerKey;
  private KeyPair permitKey;

  @BeforeAll
  void installOfflineBoundaryAndGenerateSigningMaterial() throws Exception {
    // Refuse to replace an existing security policy. The module already enables this Java 21 seam.
    assertThat(System.getSecurityManager()).isNull();
    System.setSecurityManager(boundary);
    savedDrivers = Collections.list(DriverManager.getDrivers());
    for (Driver driver : savedDrivers) DriverManager.deregisterDriver(driver);
    DriverManager.registerDriver(jdbc);
    var rsa = KeyPairGenerator.getInstance("RSA");
    rsa.initialize(2048);
    issuerKey = rsa.generateKeyPair();
    otherIssuerKey = rsa.generateKeyPair();
    permitKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
  }

  @AfterEach
  void noNetworkProcessOrJdbcConnectionWasAttempted() {
    assertThat(boundary.attempts.get()).as("network/process attempts").isZero();
    assertThat(jdbc.attempts.get()).as("DriverManager connection attempts").isZero();
  }

  @AfterAll
  void restoreProcessWideTestState() throws Exception {
    try {
      if (System.getSecurityManager() == boundary) System.setSecurityManager(null);
    } finally {
      DriverManager.deregisterDriver(jdbc);
      for (Driver driver : savedDrivers) DriverManager.registerDriver(driver);
    }
  }

  @Test
  void sameVerifiedSubjectWithDifferentRequesterAliasRejectsReviewerBeforeJdbc()
      throws Exception {
    Fixture fixture = fixture(ISSUER, "same-human", "same-client", ISSUER, "same-human", "same-client", true);
    assertThat(fixture.requesterSubject()).isEqualTo(fixture.reviewerSubject());
    assertThat(fixture.requesterPrincipal()).isNotEqualTo(principal(fixture.reviewerSubject()));
    assertSameSubjectReviewerRejected(fixture);
  }

  @Test
  void sameVerifiedSubjectAcrossDifferentAuthorizedClientsRejectsReviewerBeforeJdbc()
      throws Exception {
    Fixture fixture = fixture(ISSUER, "same-human", "requester-client", ISSUER, "same-human", "reviewer-client", true);
    assertThat(fixture.requesterClient()).isNotEqualTo(fixture.reviewerClient());
    assertThat(fixture.requesterSubject()).isEqualTo(fixture.reviewerSubject());
    assertSameSubjectReviewerRejected(fixture);
  }

  @Test
  void distinctSubjectsWithDerivedRequesterPrincipalAreAccepted() throws Exception {
    Fixture fixture = distinct();
    assertThat(fixture.requesterSubject()).isNotEqualTo(fixture.reviewerSubject());
    assertThat(fixture.requesterPrincipal()).isEqualTo(principal(fixture.requesterSubject()));
    assertBothContextsAccepted(fixture);
  }

  @Test
  void differentIssuersSharingRawSubjectRemainDifferentVerifiedSubjects() throws Exception {
    Fixture fixture = fixture(ISSUER, "same-raw-sub", "requester-client", OTHER_ISSUER, "same-raw-sub", "reviewer-client", false);
    assertThat(fixture.requesterSubject()).isEqualTo(ISSUER + "#same-raw-sub");
    assertThat(fixture.reviewerSubject()).isEqualTo(OTHER_ISSUER + "#same-raw-sub");
    assertThat(fixture.requesterSubject()).isNotEqualTo(fixture.reviewerSubject());
    assertBothContextsAccepted(fixture);
  }

  @Test
  void sameVerifiedSubjectWithoutAliasIsAlsoRejected()
      throws Exception {
    Fixture fixture = fixture(ISSUER, "same-human", "requester-client", ISSUER, "same-human", "reviewer-client", false);
    var adapter = adapter(fixture.configuration());
    assertThat(adapter.requester(fixture.requesterToken(), "operations.execute").authenticated())
        .isTrue();
    rejected(() -> adapter.reviewer(fixture.reviewerToken()), "REJECTED");
  }

  @Test
  void distinctVerifiedSubjectsCannotReuseTheSamePrincipalId() throws Exception {
    Fixture fixture = distinct();
    String sharedPrincipal = principal(fixture.reviewerSubject());
    object(fixture.configuration(), "scope").put("requesterPrincipalId", sharedPrincipal);
    object(fixture.configuration(), "requesterEnrollment")
        .put("requesterPrincipalId", sharedPrincipal);
    assertThat(fixture.requesterSubject()).isNotEqualTo(fixture.reviewerSubject());
    var adapter = adapter(fixture.configuration());
    assertThat(adapter.requester(fixture.requesterToken(), "operations.execute").requesterSubjectId())
        .isEqualTo(sharedPrincipal);
    rejected(() -> adapter.reviewer(fixture.reviewerToken()), "REJECTED");
  }

  @Test
  void missingExecuteScopeDoesNotBorrowSeparateEvaluationPermission() throws Exception {
    Fixture fixture = distinct();
    var adapter = adapter(fixture.configuration());
    String token = token(issuerKey, ISSUER, "requester", fixture.requesterClient(), "operations.evaluate");
    assertThat(adapter.evaluator(token)).isNotNull();
    rejected(() -> adapter.requester(token, "operations.execute"), "REJECTED");
    assertThat(adapter.reviewer(fixture.reviewerToken())).isNotNull();
  }

  @Test
  void signedReviewerTokenWithWrongScopeIsRejected() throws Exception {
    Fixture fixture = distinct();
    var adapter = adapter(fixture.configuration());
    String token = token(issuerKey, ISSUER, "reviewer", fixture.reviewerClient(), "operations.execute");
    rejected(() -> adapter.reviewer(token), "REJECTED");
    assertThat(adapter.requester(fixture.requesterToken(), "operations.execute")).isNotNull();
  }

  @Test
  void validClaimsSignedByAnUntrustedKeyAreRejectedBeforeJdbc() throws Exception {
    Fixture fixture = distinct();
    var adapter = adapter(fixture.configuration());
    String token = token(otherIssuerKey, ISSUER, "reviewer", fixture.reviewerClient(), "operations.decide");
    rejected(() -> adapter.reviewer(token), "REJECTED");
    assertThat(adapter.reviewer(fixture.reviewerToken())).isNotNull();
  }

  @Test
  void validSignatureDoesNotReplaceExactRequesterSubjectEnrollment() throws Exception {
    Fixture fixture = distinct();
    object(fixture.configuration(), "requesterEnrollment")
        .put("verifiedSubjectId", ISSUER + "#someone-else");
    var adapter = adapter(fixture.configuration());
    rejected(() -> adapter.requester(fixture.requesterToken(), "operations.execute"), "REJECTED");
    assertThat(adapter.reviewer(fixture.reviewerToken())).isNotNull();
  }

  @Test
  void correctlyFingerprintedRosterForAnotherSubjectStillRejectsReviewer() throws Exception {
    Fixture fixture = distinct();
    ((ObjectNode) fixture.configuration().path("reviewerMemberships").get(0))
        .put("verifiedSubjectId", ISSUER + "#another-reviewer");
    refreshRosterFingerprint(fixture.configuration());
    var adapter = adapter(fixture.configuration());
    rejected(() -> adapter.reviewer(fixture.reviewerToken()), "REJECTED");
    assertThat(adapter.requester(fixture.requesterToken(), "operations.execute")).isNotNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"clientId", "tenantId", "workspaceId", "groupId", "human", "fingerprint"})
  void inconsistentRosterConfigurationIsRejectedOffline(String field) throws Exception {
    Fixture fixture = distinct();
    ObjectNode row = (ObjectNode) fixture.configuration().path("reviewerMemberships").get(0);
    if (field.equals("human")) row.put("human", false);
    else if (!field.equals("fingerprint")) row.put(field, "different");
    if (field.equals("fingerprint")) {
      object(fixture.configuration(), "authority").put("membershipSourceFingerprint", "sha256:" + "f".repeat(64));
    } else if (!field.equals("human")) {
      refreshRosterFingerprint(fixture.configuration());
    }
    rejected(() -> LifecycleOperatorConfig.parse(fixture.configuration()), "CONFIG_INVALID");
  }

  private Fixture distinct() throws Exception {
    return fixture(ISSUER, "requester", "requester-client", ISSUER, "reviewer", "reviewer-client", false);
  }

  private static LifecycleIdentityAdapter adapter(ObjectNode configuration) {
    return new LifecycleIdentityAdapter(LifecycleOperatorConfig.parse(configuration), CLOCK);
  }

  private static void assertSameSubjectReviewerRejected(Fixture fixture) {
    LifecycleOperatorConfig configuration = LifecycleOperatorConfig.parse(fixture.configuration());
    var requesterToken = configuration.requesterProvider().verifier(CLOCK)
        .authenticate("Bearer " + fixture.requesterToken());
    var reviewerToken = configuration.reviewerProvider().verifier(CLOCK)
        .authenticate("Bearer " + fixture.reviewerToken());
    assertThat(requesterToken.subjectId()).isEqualTo(fixture.requesterSubject());
    assertThat(reviewerToken.subjectId()).isEqualTo(requesterToken.subjectId());
    assertThat(requesterToken.clientId()).isEqualTo(fixture.requesterClient());
    assertThat(reviewerToken.clientId()).isEqualTo(fixture.reviewerClient());
    // The reviewer token has a valid enrolled roster entry; refusal must not borrow a bad fixture.
    var membership = configuration.memberships().resolve(reviewerToken, WORKSPACE);
    assertThat(membership.entry().human()).isTrue();
    assertThat(membership.entry().principalId()).isNotEqualTo(fixture.requesterPrincipal());
    assertThat(configuration.memberships().fingerprint())
        .isEqualTo(configuration.authority().reviewSettings().membershipSourceFingerprint());
    var adapter = new LifecycleIdentityAdapter(configuration, CLOCK);
    assertThat(adapter.requester(fixture.requesterToken(), "operations.execute").requesterSubjectId())
        .isEqualTo(fixture.requesterPrincipal());
    assertThat(adapter.evaluator(fixture.requesterToken())).isNotNull();
    rejected(() -> adapter.reviewer(fixture.reviewerToken()), "REJECTED");
  }

  private static void assertBothContextsAccepted(Fixture fixture) {
    LifecycleOperatorConfig configuration = LifecycleOperatorConfig.parse(fixture.configuration());
    // Independently establish the verified issuer-qualified subjects using the real verifier too.
    var requesterToken = configuration.requesterProvider().verifier(CLOCK)
        .authenticate("Bearer " + fixture.requesterToken());
    var reviewerToken = configuration.reviewerProvider().verifier(CLOCK)
        .authenticate("Bearer " + fixture.reviewerToken());
    assertThat(requesterToken.subjectId()).isEqualTo(fixture.requesterSubject());
    assertThat(reviewerToken.subjectId()).isEqualTo(fixture.reviewerSubject());
    assertThat(requesterToken.clientId()).isEqualTo(fixture.requesterClient());
    assertThat(reviewerToken.clientId()).isEqualTo(fixture.reviewerClient());
    var adapter = new LifecycleIdentityAdapter(configuration, CLOCK);
    var requester = adapter.requester(fixture.requesterToken(), "operations.execute");
    var review = adapter.reviewer(fixture.reviewerToken());
    assertThat(adapter.evaluator(fixture.requesterToken())).isNotNull();
    assertThat(requester.authenticated()).isTrue();
    assertThat(requester.requesterSubjectId()).isEqualTo(fixture.requesterPrincipal());
    assertThat(requester.tenant().value()).isEqualTo(TENANT);
    assertThat(requester.roles()).containsExactly("operation_requester");
    assertThat(requester.scopes()).contains("operations.execute");
    assertThat(review.reviewer().identity().authenticated()).isTrue();
    assertThat(review.reviewer().identity().principalType()).isEqualTo("human");
    assertThat(review.reviewer().identity().tenant().value()).isEqualTo(TENANT);
    assertThat(review.reviewer().workspaceId()).isEqualTo(WORKSPACE);
    assertThat(review.reviewer().identity().roles()).containsExactly("operation_reviewer");
    assertThat(review.reviewer().identity().reviewerSubjectId())
        .isEqualTo(principal(fixture.reviewerSubject()))
        .isNotEqualTo(requester.requesterSubjectId());
    assertThat(review.reviewer().identity().scopes()).containsExactly("operations.decide");
    assertThat(review.membership().principalId())
        .isEqualTo(review.reviewer().identity().reviewerSubjectId());
    assertThat(review.membership().sourceFingerprint())
        .isEqualTo(configuration.authority().reviewSettings().membershipSourceFingerprint());
    assertThat(review.membership().observedAt()).isEqualTo(NOW);
    assertThat(review.membership().expiresAt()).isEqualTo(NOW.plusSeconds(240));
  }

  private Fixture fixture(String requesterIssuer, String requesterSub, String requesterClient,
      String reviewerIssuer, String reviewerSub, String reviewerClient, boolean alias) throws Exception {
    String requesterSubject = requesterIssuer + "#" + requesterSub;
    String reviewerSubject = reviewerIssuer + "#" + reviewerSub;
    String requesterPrincipal = principal(alias ? ISSUER + "#unrelated-owner-alias" : requesterSubject);
    KeyPair reviewerKey = reviewerIssuer.equals(requesterIssuer) ? issuerKey : otherIssuerKey;
    ObjectNode policy = (ObjectNode) JsonSupport.MAPPER.readTree("""
        {"schemaVersion":"1.0","id":"synthetic-independence-policy","version":1,
         "name":"Offline diagnostic","description":"Synthetic only","enabled":true,
         "defaultDecision":{"outcome":"block","reasonCode":"SYNTHETIC_DENY"},
         "rules":[{"id":"draft","priority":1,"description":"Synthetic",
         "match":{"toolNames":["github.pull_request.create"]},
         "decision":{"outcome":"require_approval","reasonCode":"SYNTHETIC_REVIEW",
         "approvalGroup":"synthetic-reviewers"}}]}
        """);
    String digest = "sha256:" + "a".repeat(64);
    var expected = new GitHubBusinessOperationBinding.ExpectedProfile("github-draft-pr-v1", "test", 1,
        new GitHubBusinessOperationBinding.PolicyRelease("synthetic-independence-policy", 1, CanonicalJson.sha256(policy)),
        Map.of("adapter_release", digest), new GitHubPilotTargetBinding(71, 101, 201,
            "tracepermit/agentcore-pr-pilot", "synthetic-change", "main", "a".repeat(40), "b".repeat(40)));
    ObjectNode root = JsonSupport.MAPPER.createObjectNode();
    root.put("schemaVersion", "1.0").put("profile", LifecycleProtocol.PROFILE);
    root.putObject("operations").put("engine", "POSTGRESQL_16_4").put("host", "127.0.0.1")
        .put("port", 1).put("catalog", "synthetic_offline").put("user", "synthetic_user")
        .put("password", "synthetic-offline-only-not-a-real-credential");
    root.putObject("scope").put("tenantId", TENANT).put("workspaceId", WORKSPACE)
        .put("requesterPrincipalId", requesterPrincipal);
    root.putObject("expectedTarget").put("adapterId", expected.adapterId()).put("environment", "test")
        .put("recoveryGeneration", 1).put("policyId", policy.path("id").asText()).put("policyVersion", 1)
        .put("policyContentHash", CanonicalJson.sha256(policy)).put("installationId", 71)
        .put("repositoryId", 101).put("ownerId", 201).put("repository", "tracepermit/agentcore-pr-pilot")
        .put("head", "synthetic-change").put("base", "main").put("headSha", "a".repeat(40))
        .put("baseSha", "b".repeat(40)).putObject("artifactDigests").put("adapter_release", digest);
    var artifacts = JsonSupport.MAPPER.createArrayNode();
    artifacts.addObject().put("kind", "adapter_release").put("digest", digest);
    ObjectNode authority = root.putObject("authority");
    authority.put("adapterId", expected.adapterId()).put("recoveryGeneration", 1)
        .put("artifactDigestsHash", CanonicalJson.sha256(artifacts))
        .put("resourceBindingHash", CanonicalJson.sha256(expected.resourceBinding()))
        .put("reviewerGroupId", GROUP).put("approvalLifetimeSeconds", 120)
        .put("permitIssuer", "https://permit.example.invalid").put("permitKeyId", "synthetic-permit")
        .put("permitAudience", "synthetic-operation-admission")
        .put("permitPublicKeySpki", Base64.getEncoder().encodeToString(permitKey.getPublic().getEncoded()))
        .put("permitLifetimeSeconds", 60);
    authority.set("policy", policy);
    root.set("requesterProvider", provider(issuerKey, requesterIssuer, requesterClient, false));
    root.set("reviewerProvider", provider(reviewerKey, reviewerIssuer, reviewerClient, true));
    root.putObject("requesterEnrollment").put("verifiedSubjectId", requesterSubject)
        .put("clientId", requesterClient).put("tenantId", TENANT).put("workspaceId", WORKSPACE)
        .put("requesterPrincipalId", requesterPrincipal);
    root.putArray("reviewerMemberships").addObject().put("verifiedSubjectId", reviewerSubject)
        .put("clientId", reviewerClient).put("tenantId", TENANT).put("workspaceId", WORKSPACE)
        .put("groupId", GROUP).put("human", true);
    refreshRosterFingerprint(root);
    return new Fixture(root, requesterSubject, reviewerSubject, requesterClient, reviewerClient,
        requesterPrincipal, token(issuerKey, requesterIssuer, requesterSub, requesterClient, REQUESTER_SCOPES),
        token(reviewerKey, reviewerIssuer, reviewerSub, reviewerClient, "operations.decide"));
  }

  private static ObjectNode provider(KeyPair key, String issuer, String client, boolean reviewer) {
    ObjectNode value = JsonSupport.MAPPER.createObjectNode();
    value.put("issuer", issuer).put("audience", AUDIENCE).put("keyId", "synthetic-rsa-key")
        .put("algorithm", "RS256").put("jwtType", "at+jwt")
        .put("publicKeySpki", Base64.getEncoder().encodeToString(key.getPublic().getEncoded()))
        .put("tenantClaim", "org_id").put("tenantValue", "synthetic-organization").put("tenantId", TENANT)
        .put("clientId", client).put("maximumTokenLifetimeSeconds", 600).put("keyCacheSeconds", 60)
        .put("refreshCooldownSeconds", 30).put("jwksUri", issuer + "/jwks")
        .put("metadataUri", issuer + "/metadata").put("origin", "CLI");
    var scopes = value.putArray("supportedScopes");
    if (reviewer) scopes.add("operations.decide");
    else scopes.add("operations.evaluate").add("operations.execute");
    return value;
  }

  private static String token(KeyPair key, String issuer, String subject, String client, String scope)
      throws Exception {
    ObjectNode header = JsonSupport.MAPPER.createObjectNode().put("alg", "RS256")
        .put("typ", "at+jwt").put("kid", "synthetic-rsa-key");
    ObjectNode claims = JsonSupport.MAPPER.createObjectNode().put("iss", issuer).put("aud", AUDIENCE)
        .put("sub", subject).put("client_id", client).put("org_id", "synthetic-organization")
        .put("scope", scope).put("iat", NOW.minusSeconds(5).getEpochSecond())
        .put("exp", NOW.plusSeconds(240).getEpochSecond()).put("jti", UUID.randomUUID().toString());
    Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    String input = encoder.encodeToString(header.toString().getBytes(StandardCharsets.UTF_8)) + "."
        + encoder.encodeToString(claims.toString().getBytes(StandardCharsets.UTF_8));
    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initSign(key.getPrivate());
    signer.update(input.getBytes(StandardCharsets.US_ASCII));
    return input + "." + encoder.encodeToString(signer.sign());
  }

  private static String principal(String verifiedSubject) {
    return "principal:" + CanonicalJson.sha256(JsonSupport.MAPPER.getNodeFactory().textNode(verifiedSubject))
        .substring("sha256:".length());
  }

  private static ObjectNode object(ObjectNode root, String name) {
    return (ObjectNode) root.get(name);
  }

  private static void refreshRosterFingerprint(ObjectNode root) {
    object(root, "authority").put("membershipSourceFingerprint",
        LifecycleMemberships.parse(root.get("reviewerMemberships")).fingerprint());
  }

  private static void rejected(Runnable action, String code) {
    assertThatThrownBy(action::run).isInstanceOfSatisfying(LifecycleFailure.class,
        failure -> assertThat(failure.code).isEqualTo(code));
  }

  private record Fixture(ObjectNode configuration, String requesterSubject, String reviewerSubject,
      String requesterClient, String reviewerClient, String requesterPrincipal,
      String requesterToken, String reviewerToken) {
    @Override public String toString() { return "IdentityIndependenceFixture[redacted]"; }
  }

  private static final class OfflineBoundary extends SecurityManager {
    private final AtomicInteger attempts = new AtomicInteger();
    @Override public void checkPermission(Permission permission) {}
    @Override public void checkConnect(String host, int port) { deny(); }
    @Override public void checkConnect(String host, int port, Object context) { deny(); }
    @Override public void checkListen(int port) { deny(); }
    @Override public void checkAccept(String host, int port) { deny(); }
    @Override public void checkMulticast(java.net.InetAddress address) { deny(); }
    @Override public void checkExec(String command) { deny(); }
    private void deny() {
      attempts.incrementAndGet();
      throw new SecurityException("OFFLINE_DIAGNOSTIC_IO_FORBIDDEN");
    }
  }

  private static final class JdbcTrap implements Driver {
    private final AtomicInteger attempts = new AtomicInteger();
    @Override public Connection connect(String url, Properties properties) throws SQLException {
      attempts.incrementAndGet();
      throw new SQLException("OFFLINE_DIAGNOSTIC_JDBC_FORBIDDEN");
    }
    @Override public boolean acceptsURL(String url) { return true; }
    @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties properties) {
      return new DriverPropertyInfo[0];
    }
    @Override public int getMajorVersion() { return 1; }
    @Override public int getMinorVersion() { return 0; }
    @Override public boolean jdbcCompliant() { return false; }
    @Override public Logger getParentLogger() { return Logger.getGlobal(); }
  }
}
