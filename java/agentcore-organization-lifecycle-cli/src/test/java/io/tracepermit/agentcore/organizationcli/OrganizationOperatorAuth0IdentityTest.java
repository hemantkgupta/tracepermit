package io.tracepermit.agentcore.organizationcli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.adapters.remote.agentcore.Auth0OperationAuthorityException;
import io.tracepermit.identity.OidcTokenProfile;
import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Offline signed identities; no live Auth0, GitHub, AWS or database credentials. */
final class OrganizationOperatorAuth0IdentityTest {
  @Test
  void explicitAuth0PairDerivesExactOwnerAndIndependentReviewer() {
    var fixture = OrganizationOperatorFixture.auth0();
    var config = OrganizationOperatorConfig.parse(fixture.configuration);
    var authority = config.authority(Clock.systemUTC());
    assertThat(config.usesAuth0()).isTrue();
    var requester = authority.requester(bearer(fixture.requesterToken(
        fixture.requesterSubject(), Set.of("operations.execute"))), "operations.execute");
    var reviewer = authority.reviewer(bearer(fixture.reviewerToken(fixture.reviewerSubject())));
    assertThat(requester.authenticationMethod()).isEqualTo(OidcTokenProfile.AUTH0);
    assertThat(requester.requesterSubjectId()).isEqualTo(OrganizationOperatorFixture.REQUESTER_PRINCIPAL);
    assertThat(requester.roles()).containsExactly("operation_requester");
    assertThat(requester.scopes()).containsExactly("operations.execute");
    assertThat(reviewer.reviewer().identity().roles()).containsExactly("operation_reviewer");
    assertThat(reviewer.reviewer().identity().scopes()).containsExactly("operations.decide");
    assertThat(reviewer.reviewer().identity().reviewerSubjectId())
        .isNotEqualTo(requester.requesterSubjectId());
    assertThat(reviewer.membership().sourceFingerprint())
        .isEqualTo(config.authorityProfile().reviewSettings().membershipSourceFingerprint());
    assertThat(reviewer.membership().expiresAt())
        .isBeforeOrEqualTo(reviewer.membership().observedAt().plusSeconds(300));
  }

  @Test
  void existingConfigurationRemainsOktaAndExplicitProviderSelectionDoesNotFallback() {
    var fixture = new OrganizationOperatorFixture();
    assertThat(OrganizationOperatorConfig.parse(fixture.configuration).usesAuth0()).isFalse();
    fixture.configuration.put("identityProfile", OidcTokenProfile.OKTA);
    assertThat(OrganizationOperatorConfig.parse(fixture.configuration).usesAuth0()).isFalse();
    fixture.configuration.put("identityProfile", OidcTokenProfile.AUTH0);
    rejectedConfig(fixture.configuration);
    var auth0 = OrganizationOperatorFixture.auth0();
    auth0.configuration.remove("identityProfile");
    rejectedConfig(auth0.configuration);
  }

  @ParameterizedTest
  @ValueSource(strings = {"unknown-profile", "null-profile", "jwt-type", "lifetime", "role-field",
      "okta-field", "same-client", "machine-enrollment", "reviewer-issuer", "reviewer-audience",
      "reviewer-tenant", "reviewer-role-claim", "register-scope", "decide-scope", "roster-hash",
      "roster-human", "retired-engine", "unknown-engine"})
  void ambiguousOrBroadenedConfigurationFailsOffline(String mutation) {
    var f = OrganizationOperatorFixture.auth0();
    ObjectNode c = f.configuration;
    ObjectNode requester = (ObjectNode)c.path("requesterProvider");
    ObjectNode reviewer = (ObjectNode)c.path("reviewerProvider");
    switch (mutation) {
      case "unknown-profile" -> c.put("identityProfile", "auto-detect");
      case "null-profile" -> c.putNull("identityProfile");
      case "jwt-type" -> requester.put("jwtType", "JWT");
      case "lifetime" -> requester.put("maximumTokenLifetimeSeconds", 301);
      case "role-field" -> requester.put("roleClaim", "roles");
      case "okta-field" -> requester.put("groupClaim", "groups");
      case "same-client" -> reviewer.put("clientId", OrganizationOperatorFixture.REQUESTER_CLIENT);
      case "machine-enrollment" -> ((ObjectNode)c.path("requesterEnrollment"))
          .put("verifiedSubjectId", f.issuer() + "#auth0|human");
      case "reviewer-issuer" -> reviewer.put("issuer", "https://other.example.invalid/");
      case "reviewer-audience" -> reviewer.put("audience", "https://other.example.invalid/api");
      case "reviewer-tenant" -> reviewer.put("tenantValue", "another-tenant");
      case "reviewer-role-claim" -> reviewer.put("roleClaim", "https://other.example.invalid/roles");
      case "register-scope" -> requester.withArray("supportedScopes").add("operations.decide");
      case "decide-scope" -> reviewer.withArray("supportedScopes").add("operations.execute");
      case "roster-hash" -> ((ObjectNode)c.path("authority"))
          .put("membershipSourceFingerprint", "sha256:" + "b".repeat(64));
      case "roster-human" -> ((ObjectNode)c.path("reviewerMemberships").get(0)).put("human", false);
      case "retired-engine" -> ((ObjectNode)c.path("operations")).put("engine", "MARIADB_10_6_25");
      case "unknown-engine" -> ((ObjectNode)c.path("operations")).put("engine", "MARIADB_ANY");
      default -> throw new AssertionError();
    }
    rejectedConfig(c);
  }

  @ParameterizedTest
  @ValueSource(strings = {"sub", "client_id", "role", "tenant", "scope", "expired", "cid", "scp"})
  void signedRequesterSubstitutionsCannotDeriveAuthority(String mutation) {
    var f = OrganizationOperatorFixture.auth0();
    byte[] token = f.requesterTokenWithClaims(c -> {
      switch (mutation) {
        case "sub" -> c.put("sub", "auth0|human");
        case "client_id" -> c.put("client_id", OrganizationOperatorFixture.REVIEWER_CLIENT);
        case "role" -> c.putArray(OrganizationOperatorFixture.ROLE_CLAIM).add("REVIEWER");
        case "tenant" -> c.put(OrganizationOperatorFixture.TENANT_CLAIM, "unmapped");
        case "scope" -> c.put("scope", "operations.decide");
        case "expired" -> c.put("exp", Instant.now().minusSeconds(1).getEpochSecond());
        case "cid" -> c.put("cid", OrganizationOperatorFixture.REQUESTER_CLIENT);
        case "scp" -> c.putArray("scp").add("operations.execute");
        default -> throw new AssertionError();
      }
    });
    assertThatThrownBy(() -> OrganizationOperatorConfig.parse(f.configuration)
        .authority(Clock.systemUTC()).requester(bearer(token), "operations.execute"))
        .isInstanceOf(Auth0OperationAuthorityException.class).hasMessage("AUTH0_AUTHORITY_REJECTED");
  }

  @Test
  void requesterAndUnenrolledHumanCannotReviewAndReviewerCannotExecute() {
    var f = OrganizationOperatorFixture.auth0();
    var authority = OrganizationOperatorConfig.parse(f.configuration).authority(Clock.systemUTC());
    assertThatThrownBy(() -> authority.reviewer(bearer(f.requesterToken(
        f.requesterSubject(), Set.of("operations.execute")))))
        .isInstanceOf(Auth0OperationAuthorityException.class);
    assertThatThrownBy(() -> authority.reviewer(bearer(f.reviewerToken("auth0|unenrolled"))))
        .isInstanceOf(Auth0OperationAuthorityException.class);
    assertThatThrownBy(() -> authority.requester(bearer(f.reviewerToken(f.reviewerSubject())),
        "operations.execute"))
        .isInstanceOf(Auth0OperationAuthorityException.class);
  }

  @Test
  void offlineMariaConfigAndRejectedCommandsDoNotTouchAnApparentlyReadyDatabase() throws Exception {
    var f = OrganizationOperatorFixture.auth0();
    try (var sentinel = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
      sentinel.setSoTimeout(200);
      for (String name : List.of("replay", "operations")) {
        ((ObjectNode)f.configuration.path(name)).put("engine", "MARIADB_11_4_13")
            .put("port", sentinel.getLocalPort());
      }
      assertThat(execute(f, "check-config").outcome()).isEqualTo("CONFIG_VALID");
      byte[] invalid = f.requesterTokenWithClaims(c -> c.put("sub", "auth0|human"));
      for (String name : List.of("register", "inspect", "evaluate", "review", "deliver-synthetic", "reconcile")) {
        byte[] command = name.equals("register") ? f.registrationBytes("refused")
            : f.commandBytes(UUID.randomUUID().toString(), name,
                name.equals("review") ? "APPROVE" : "CONFIRMED_SUCCESS");
        var receipt = name.equals("deliver-synthetic")
            ? execute(f, name, invalid, f.privateKey(), command)
            : execute(f, name, invalid, command);
        assertThat(receipt.outcome()).isEqualTo("REJECTED");
        assertThat(receipt.exitCode()).isEqualTo(1);
      }
      assertThatThrownBy(sentinel::accept).isInstanceOf(java.net.SocketTimeoutException.class);
    }
  }

  private static OrganizationOperatorReceipt execute(OrganizationOperatorFixture f, String command,
      byte[]... values) {
    byte[][] frames = new byte[values.length + 1][];
    frames[0] = f.configBytes();
    System.arraycopy(values, 0, frames, 1, values.length);
    return OrganizationOperatorWorker.execute(new ByteArrayInputStream(
        OrganizationOperatorFixture.frames(frames)), command);
  }

  private static String bearer(byte[] value) {
    return "Bearer " + new String(value, StandardCharsets.US_ASCII);
  }

  private static void rejectedConfig(ObjectNode configuration) {
    assertThatThrownBy(() -> OrganizationOperatorConfig.parse(configuration))
        .isInstanceOf(OrganizationFailure.class).hasMessage("CONFIG_INVALID");
  }
}
