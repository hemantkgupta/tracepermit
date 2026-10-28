package io.tracepermit.adapters.remote.agentcore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.adapters.remote.github.GitHubOrganizationOperationBinding;
import io.tracepermit.application.BusinessOperationAuthorityProfile;
import io.tracepermit.application.BusinessOperationReviewSettings;
import io.tracepermit.application.BusinessOperationScope;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.identity.ConnectorOrigin;
import io.tracepermit.identity.ConnectorScope;
import io.tracepermit.identity.OidcAccessTokenVerifier;
import io.tracepermit.identity.OidcProviderConfiguration;
import io.tracepermit.identity.OidcTokenProfile;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Generated-token fixtures only. No Okta discovery, login, administration or network calls. */
final class OktaOperationAuthorityTest {
  private static final Instant NOW = Instant.parse("2026-10-28T10:00:00Z");
  private static final String ISSUER = "https://synthetic.okta.invalid/oauth2/sandbox";
  private static final String AUDIENCE = "https://tracepermit.synthetic.invalid";
  private static final String TENANT = "tenant:synthetic-org";
  private static final String EXTERNAL_TENANT = "synthetic-org";
  private static final String WORKSPACE = "github-sandbox";
  private static final String REQUESTER_CLIENT = "tracepermit-requester";
  private static final String REVIEWER_CLIENT = "tracepermit-reviewer";
  private static final String REQUESTER_SUBJECT = "00u-requester";
  private static final String REVIEWER_SUBJECT = "00u-reviewer";
  private static final String GROUP = "operation-reviewers";
  private static final String HASH = "sha256:" + "a".repeat(64);
  private static final KeyPair KEY = rsa();
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final BusinessOperationScope SCOPE =
      new BusinessOperationScope(TENANT, WORKSPACE, "principal:requester-owner");

  @Test
  void verifiedMappedRolesOnlyAttenuateExactRequesterAndIndependentReviewerEnrollment() {
    OktaOperationReviewerRoster roster = roster(REVIEWER_SUBJECT);
    OktaOperationAuthority authority = authority(SCOPE, roster);

    var requester =
        authority.requester(
            token(
                REQUESTER_CLIENT,
                REQUESTER_SUBJECT,
                "pilot-requesters",
                Set.of("operations.evaluate"),
                NOW.plusSeconds(600)),
            "operations.evaluate");
    var reviewer =
        authority.reviewer(
            token(
                REVIEWER_CLIENT,
                REVIEWER_SUBJECT,
                "pilot-reviewers",
                Set.of("operations.decide"),
                NOW.plusSeconds(600)));

    assertThat(requester.requesterSubjectId()).isEqualTo(SCOPE.requesterPrincipalId());
    assertThat(requester.roles()).containsExactly("operation_requester");
    assertThat(requester.scopes()).containsExactly("operations.evaluate");
    assertThat(requester.authenticationMethod()).isEqualTo(OidcTokenProfile.OKTA);
    assertThat(reviewer.reviewer().identity().roles()).containsExactly("operation_reviewer");
    assertThat(reviewer.reviewer().identity().scopes()).containsExactly("operations.decide");
    assertThat(reviewer.membership().sourceFingerprint()).isEqualTo(roster.fingerprint());
    assertThat(reviewer.membership().expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
    assertThat(reviewer.reviewer().identity().reviewerSubjectId())
        .isNotEqualTo(SCOPE.requesterPrincipalId());
  }

  @Test
  void tokenRoleCannotSupplyMissingScopeOrSwapRequesterAndReviewerAuthority() {
    OktaOperationAuthority authority = authority(SCOPE, roster(REVIEWER_SUBJECT));
    String requesterWithReviewerRole =
        token(
            REQUESTER_CLIENT,
            REQUESTER_SUBJECT,
            "pilot-reviewers",
            Set.of("operations.evaluate"),
            NOW.plusSeconds(600));
    String reviewerWithRequesterRole =
        token(
            REVIEWER_CLIENT,
            REVIEWER_SUBJECT,
            "pilot-requesters",
            Set.of("operations.decide"),
            NOW.plusSeconds(600));
    String requesterMissingScope =
        token(
            REQUESTER_CLIENT,
            REQUESTER_SUBJECT,
            "pilot-requesters",
            Set.of("operations.execute"),
            NOW.plusSeconds(600));

    rejected(() -> authority.requester(requesterWithReviewerRole, "operations.evaluate"));
    rejected(() -> authority.reviewer(reviewerWithRequesterRole));
    rejected(() -> authority.requester(requesterMissingScope, "operations.evaluate"));
    rejected(() -> authority.requester(requesterMissingScope, "operations.cancel"));
  }

  @Test
  void exactRosterClientTenantWorkspaceAndSubjectRemainIndependentOfVerifiedGroupClaims() {
    OktaOperationAuthority authority = authority(SCOPE, roster(REVIEWER_SUBJECT));
    String unknownReviewer =
        token(
            REVIEWER_CLIENT,
            "00u-unenrolled",
            "pilot-reviewers",
            Set.of("operations.decide"),
            NOW.plusSeconds(600));
    String requesterClient =
        token(
            REQUESTER_CLIENT,
            REVIEWER_SUBJECT,
            "pilot-reviewers",
            Set.of("operations.decide"),
            NOW.plusSeconds(600));

    rejected(() -> authority.reviewer(unknownReviewer));
    rejected(() -> authority.reviewer(requesterClient));
  }

  @Test
  void offlineRosterFingerprintAndFiveMinuteCapAreDeterministic() {
    var first = roster(REVIEWER_SUBJECT);
    var second = roster(REVIEWER_SUBJECT);
    var changed = roster("00u-other-reviewer");

    assertThat(first.fingerprint()).isEqualTo(second.fingerprint()).matches("sha256:[a-f0-9]{64}");
    assertThat(first.fingerprint()).isNotEqualTo(changed.fingerprint());
    assertThat(first.toString()).doesNotContain(REVIEWER_SUBJECT, REVIEWER_CLIENT, TENANT, GROUP);
  }

  @Test
  void requesterAndReviewerVerificationMustRemainSeparateTrustedInputs() {
    OktaOperationReviewerRoster roster = roster(REVIEWER_SUBJECT);
    OidcAccessTokenVerifier shared =
        verifier(
            REQUESTER_CLIENT,
            Set.of(
                "operations.register",
                "operations.evaluate",
                "operations.execute",
                "operations.decide"));
    var enrollment =
        new OktaOperationAuthority.RequesterEnrollment(
            ISSUER,
            ISSUER + "#" + REQUESTER_SUBJECT,
            REQUESTER_CLIENT,
            TENANT,
            WORKSPACE,
            SCOPE.requesterPrincipalId());

    assertThatThrownBy(
            () ->
                new OktaOperationAuthority(
                    shared,
                    shared,
                    SCOPE,
                    enrollment,
                    roster,
                    authorityProfile(SCOPE, roster),
                    REVIEWER_CLIENT,
                    CLOCK))
        .isInstanceOf(OktaOperationAuthorityException.class)
        .hasMessage("OKTA_AUTHORITY_CONFIGURATION_INVALID")
        .hasNoCause()
        .satisfies(failure -> assertThat(failure.getStackTrace()).isEmpty());
  }

  @Test
  void requesterCannotReviewItsOwnOperationEvenWithAValidReviewerTokenAndRosterEntry() {
    OktaOperationReviewerRoster roster = roster(REVIEWER_SUBJECT);
    String reviewerPrincipal =
        new OktaOperationReviewerRoster.Entry(
                ISSUER + "#" + REVIEWER_SUBJECT,
                REVIEWER_CLIENT,
                TENANT,
                WORKSPACE,
                GROUP,
                true)
            .principalId();
    BusinessOperationScope selfScope =
        new BusinessOperationScope(TENANT, WORKSPACE, reviewerPrincipal);
    OktaOperationAuthority authority = authority(selfScope, roster, reviewerPrincipal);

    rejected(
        () ->
            authority.reviewer(
                token(
                    REVIEWER_CLIENT,
                    REVIEWER_SUBJECT,
                    "pilot-reviewers",
                    Set.of("operations.decide"),
                    NOW.plusSeconds(600))));
  }

  @Test
  void requesterSubjectCannotReenterThroughTheIndependentReviewerClient() {
    OktaOperationAuthority authority = authority(SCOPE, roster(REQUESTER_SUBJECT));

    rejected(
        () ->
            authority.reviewer(
                token(
                    REVIEWER_CLIENT,
                    REQUESTER_SUBJECT,
                    "pilot-reviewers",
                    Set.of("operations.decide"),
                    NOW.plusSeconds(600))));
  }

  private static OktaOperationAuthority authority(
      BusinessOperationScope scope, OktaOperationReviewerRoster roster) {
    return authority(scope, roster, scope.requesterPrincipalId());
  }

  private static OktaOperationAuthority authority(
      BusinessOperationScope scope,
      OktaOperationReviewerRoster roster,
      String requesterPrincipalId) {
    var enrollment =
        new OktaOperationAuthority.RequesterEnrollment(
            ISSUER,
            ISSUER + "#" + REQUESTER_SUBJECT,
            REQUESTER_CLIENT,
            TENANT,
            WORKSPACE,
            requesterPrincipalId);
    return new OktaOperationAuthority(
        verifier(REQUESTER_CLIENT, Set.of("operations.register", "operations.evaluate", "operations.execute")),
        verifier(REVIEWER_CLIENT, Set.of("operations.decide")),
        scope,
        enrollment,
        roster,
        authorityProfile(scope, roster),
        REVIEWER_CLIENT,
        CLOCK);
  }

  private static OktaOperationReviewerRoster roster(String subject) {
    return new OktaOperationReviewerRoster(
        List.of(
            new OktaOperationReviewerRoster.Entry(
                ISSUER + "#" + subject,
                REVIEWER_CLIENT,
                TENANT,
                WORKSPACE,
                GROUP,
                true)));
  }

  private static BusinessOperationAuthorityProfile authorityProfile(
      BusinessOperationScope scope, OktaOperationReviewerRoster roster) {
    ObjectNode policy;
    try {
      policy =
          (ObjectNode)
              JsonSupport.MAPPER.readTree(
                  """
                  {"schemaVersion":"1.0","id":"synthetic-github-draft-pr","version":1,
                   "name":"Synthetic","description":"No target authority","enabled":true,
                   "defaultDecision":{"outcome":"block","reasonCode":"SYNTHETIC_DENY"},
                   "rules":[{"id":"exact-draft-pr","priority":1,"description":"Synthetic",
                   "match":{"toolNames":["github.pull_request.create"]},
                   "decision":{"outcome":"require_approval","reasonCode":"SYNTHETIC_REVIEW",
                   "approvalGroup":"operation-reviewers"}}]}
                  """);
    } catch (Exception impossible) {
      throw new AssertionError(impossible);
    }
    return new BusinessOperationAuthorityProfile(
        scope,
        GitHubOrganizationOperationBinding.ADAPTER_ID,
        1,
        policy,
        HASH,
        HASH,
        new BusinessOperationReviewSettings(GROUP, roster.fingerprint(), Duration.ofMinutes(1)));
  }

  private static OidcAccessTokenVerifier verifier(String client, Set<String> scopes) {
    OidcProviderConfiguration provider = provider(client, scopes);
    return new OidcAccessTokenVerifier(
        issuer -> issuer.equals(ISSUER) ? java.util.Optional.of(provider) : java.util.Optional.empty(),
        (configured, keyId, algorithm, observed) -> {
          if (configured != provider || !"synthetic-key".equals(keyId) || !"RS256".equals(algorithm)) {
            throw new IllegalArgumentException("SYNTHETIC_KEY_REJECTED");
          }
          return (RSAPublicKey) KEY.getPublic();
        },
        CLOCK);
  }

  private static OidcProviderConfiguration provider(String client, Set<String> scopes) {
    return new OidcProviderConfiguration(
        URI.create(ISSUER),
        AUDIENCE,
        URI.create(ISSUER + "/v1/keys"),
        URI.create(AUDIENCE + "/.well-known/oauth-protected-resource"),
        List.of(URI.create(ISSUER)),
        "Synthetic TracePermit",
        Map.of(client, "Synthetic client"),
        "tracepermit_tenant",
        Map.of(EXTERNAL_TENANT, TENANT),
        scopes.stream().map(ConnectorScope::of).collect(java.util.stream.Collectors.toUnmodifiableSet()),
        ConnectorOrigin.CLI,
        Duration.ofMinutes(15),
        Duration.ofMinutes(5),
        Duration.ofSeconds(30),
        OidcTokenProfile.okta(
            "groups",
            Map.of(
                "pilot-requesters", "REQUESTER",
                "pilot-reviewers", "REVIEWER")));
  }

  private static String token(
      String client, String subject, String group, Set<String> scopes, Instant expiry) {
    try {
      ObjectNode header =
          JsonSupport.MAPPER
              .createObjectNode()
              .put("alg", "RS256")
              .put("typ", "JWT")
              .put("kid", "synthetic-key");
      ObjectNode claims =
          JsonSupport.MAPPER
              .createObjectNode()
              .put("iss", ISSUER)
              .put("aud", AUDIENCE)
              .put("sub", subject)
              .put("cid", client)
              .put("tracepermit_tenant", EXTERNAL_TENANT)
              .put("iat", NOW.minusSeconds(5).getEpochSecond())
              .put("exp", expiry.getEpochSecond())
              .put("jti", UUID.randomUUID().toString());
      scopes.stream().sorted().forEach(value -> claims.withArray("scp").add(value));
      claims.putArray("groups").add(group);
      Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
      String body =
          encoder.encodeToString(JsonSupport.MAPPER.writeValueAsBytes(header))
              + "."
              + encoder.encodeToString(JsonSupport.MAPPER.writeValueAsBytes(claims));
      Signature signature = Signature.getInstance("SHA256withRSA");
      signature.initSign(KEY.getPrivate());
      signature.update(body.getBytes(StandardCharsets.US_ASCII));
      return "Bearer " + body + "." + encoder.encodeToString(signature.sign());
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  private static KeyPair rsa() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (Exception failure) {
      throw new ExceptionInInitializerError(failure);
    }
  }

  private static void rejected(Runnable call) {
    assertThatThrownBy(call::run)
        .isInstanceOf(OktaOperationAuthorityException.class)
        .hasMessage("OKTA_AUTHORITY_REJECTED")
        .hasNoCause()
        .satisfies(failure -> assertThat(failure.getStackTrace()).isEmpty());
  }
}
