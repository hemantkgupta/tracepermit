package io.tracepermit.agentcore.organizationcli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tracepermit.adapters.remote.agentcore.OktaOperationAuthorityException;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OrganizationOperatorIdentityTest {
  @Test
  void actualOktaAuthorityPinsEnrollmentRoleAndIndependentReviewer() {
    var fixture = new OrganizationOperatorFixture();
    var config = OrganizationOperatorConfig.parse(fixture.configuration);
    var authority = config.authority(Clock.systemUTC());

    assertThat(
            authority
                .requester(
                    "Bearer "
                        + new String(
                            fixture.requesterToken(
                                OrganizationOperatorFixture.REQUESTER_SUBJECT,
                                Set.of("operations.execute")),
                            java.nio.charset.StandardCharsets.US_ASCII),
                    "operations.execute")
                .requesterSubjectId())
        .isEqualTo(OrganizationOperatorFixture.REQUESTER_PRINCIPAL);
    assertThat(
            authority
                .reviewer(
                    "Bearer "
                        + new String(
                            fixture.reviewerToken(OrganizationOperatorFixture.REVIEWER_SUBJECT),
                            java.nio.charset.StandardCharsets.US_ASCII))
                .membership()
                .groupId())
        .isEqualTo(OrganizationOperatorFixture.REVIEWER_GROUP);

    assertThatThrownBy(
            () ->
                authority.requester(
                    "Bearer "
                        + new String(
                            fixture.requesterToken(
                                "00u-substituted", Set.of("operations.execute")),
                            java.nio.charset.StandardCharsets.US_ASCII),
                    "operations.execute"))
        .isInstanceOf(OktaOperationAuthorityException.class)
        .hasMessage("OKTA_AUTHORITY_REJECTED");
  }

  @Test
  void crossClientSameVerifiedSubjectCannotSelfReview() {
    var fixture = new OrganizationOperatorFixture(OrganizationOperatorFixture.REQUESTER_SUBJECT);
    var authority =
        OrganizationOperatorConfig.parse(fixture.configuration).authority(Clock.systemUTC());
    assertThatThrownBy(
            () ->
                authority.reviewer(
                    "Bearer "
                        + new String(
                            fixture.reviewerToken(OrganizationOperatorFixture.REQUESTER_SUBJECT),
                            java.nio.charset.StandardCharsets.US_ASCII)))
        .isInstanceOf(OktaOperationAuthorityException.class)
        .hasMessage("OKTA_AUTHORITY_REJECTED");
  }

  @Test
  void requesterSignatureCannotCrossTheConfiguredProviderKey() {
    var fixture = new OrganizationOperatorFixture();
    var substituted = fixture.configuration.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) substituted.path("requesterProvider"))
        .put(
            "publicKeySpki",
            Base64.getEncoder().encodeToString(fixture.reviewerKey.getPublic().getEncoded()));
    var authority = OrganizationOperatorConfig.parse(substituted).authority(Clock.systemUTC());
    assertThatThrownBy(
            () ->
                authority.requester(
                    "Bearer "
                        + new String(
                            fixture.requesterToken(
                                OrganizationOperatorFixture.REQUESTER_SUBJECT,
                                Set.of("operations.execute")),
                            java.nio.charset.StandardCharsets.US_ASCII),
                    "operations.execute"))
        .isInstanceOf(OktaOperationAuthorityException.class)
        .hasMessage("OKTA_AUTHORITY_REJECTED");
  }

  @Test
  void deliveryPrivateKeyMustMatchPinnedEd25519PublicKeyBeforeJdbc() throws Exception {
    var fixture = new OrganizationOperatorFixture();
    var runtime =
        new OrganizationOperatorRuntime(
            OrganizationOperatorConfig.parse(fixture.configuration));
    assertThat(runtime.privateKey(fixture.privateKey()))
        .isInstanceOf(java.security.interfaces.EdECPrivateKey.class);

    byte[] substituted =
        KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate().getEncoded();
    assertThatThrownBy(() -> runtime.privateKey(substituted))
        .isInstanceOf(OrganizationFailure.class)
        .hasMessage("REJECTED");
  }
}
