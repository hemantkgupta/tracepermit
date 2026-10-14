package io.tracepermit.identity;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.contracts.JsonSupport;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class DevelopmentTokenVerifierTest {
  private static final Path ROOT = Path.of(System.getProperty("tracepermit.repositoryRoot"));
  private static final JsonNode VECTOR =
      JsonSupport.read(ROOT.resolve("contracts/vectors/development-access-token-v1.json"));
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse(VECTOR.path("now").asText()), ZoneOffset.UTC);
  private static final byte[] KEY =
      HexFormat.of().parseHex(VECTOR.path("testOnlySigningKeyHex").asText());

  @Test
  void verifiedResultIsImmutableAndContainsNoReusableCredential() {
    String token = VECTOR.path("expectedCompactToken").asText();
    AuthenticatedConnector connector =
        new DevelopmentTokenVerifier(KEY, CLOCK).authenticate("Bearer " + token);

    assertThat(connector.credentialFingerprint().value()).startsWith("sha256:");
    assertThat(connector.toString()).doesNotContain(token);
    assertThat(AuthenticatedConnector.class.getRecordComponents())
        .extracting(component -> component.getName())
        .doesNotContain("accessToken", "authorization", "bearerToken");
    assertThatThrownBy(() -> connector.scopes().add(ConnectorScope.of("aws.admin")))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void authenticationAndAuthorizationErrorsRemainBoundedAndSecretFree() {
    String token = VECTOR.path("expectedCompactToken").asText();
    String[] segments = token.split("\\.");
    char replacement = segments[2].charAt(0) == 'A' ? 'B' : 'A';
    String tampered =
        segments[0]
            + "."
            + segments[1]
            + "."
            + replacement
            + segments[2].substring(1);

    assertThatThrownBy(
            () -> new DevelopmentTokenVerifier(KEY, CLOCK).authenticate("Bearer " + tampered))
        .isInstanceOf(AuthenticationException.class)
        .hasMessage("The bearer token signature is invalid.")
        .hasMessageNotContaining(token)
        .hasMessageNotContaining(tampered);

    DevelopmentTestTokenIssuer issuer =
        new DevelopmentTestTokenIssuer(
            KEY,
            CLOCK,
            () -> "018f47a2-f5ce-7a4b-8f54-80e5d3eaf083",
            VECTOR.path("tokenLifetimeSeconds").asInt());
    AuthenticatedConnector discoveryOnly =
        new DevelopmentTokenVerifier(KEY, CLOCK)
            .authenticate("Bearer " + issuer.issueToken(DevelopmentProfile.CHATGPT_DISCOVERY_ONLY));
    assertThatThrownBy(
            () -> ScopeAuthorizer.require(discoveryOnly, ConnectorScope.of("aws.logs.read")))
        .isInstanceOf(AuthenticationException.class)
        .hasMessage("The authenticated connector is not authorized for this operation.");
  }

  @Test
  void unsafeDevelopmentConfigurationIsRejected() {
    assertThatThrownBy(() -> new DevelopmentTokenVerifier(new byte[31], CLOCK))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new DevelopmentTestTokenIssuer(
                    KEY, CLOCK, () -> "id", DevelopmentTokenVerifier.MAX_TOKEN_LIFETIME_SECONDS + 1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
