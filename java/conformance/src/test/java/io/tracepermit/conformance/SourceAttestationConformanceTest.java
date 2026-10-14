package io.tracepermit.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.identity.EndpointKeyRegistry;
import io.tracepermit.identity.InMemorySourceAttestationReplayStore;
import io.tracepermit.identity.SourceAttestationVerifier;
import io.tracepermit.identity.StaticEndpointKeyRegistry;
import io.tracepermit.identity.VerifiedSourceEvidence;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

final class SourceAttestationConformanceTest {
  private static final Path ROOT = Path.of(System.getProperty("tracepermit.repositoryRoot"));
  private static final JsonNode VECTOR =
      JsonSupport.read(ROOT.resolve("contracts/vectors/source-attestation-v1.json"));

  @Test
  void javaVerifiesTheSourceAttestationVector() {
    JsonNode claims = VECTOR.path("claims");
    SourceAttestationVerifier verifier =
        new SourceAttestationVerifier(
            new StaticEndpointKeyRegistry(
                List.of(
                    new EndpointKeyRegistry.EnrolledEndpointKey(
                        claims.path("issuer").asText(),
                        claims.path("keyId").asText(),
                        claims.path("device").path("deviceId").asText(),
                        publicKey()))),
            new InMemorySourceAttestationReplayStore(),
            Clock.fixed(Instant.parse(VECTOR.path("verifiedAt").asText()), ZoneOffset.UTC));

    assertThat(SourceAttestationVerifier.hashRequest(VECTOR.path("request")))
        .isEqualTo(claims.path("requestHash").asText());
    VerifiedSourceEvidence verified =
        verifier.verify(
            VECTOR.path("expectedCompactAttestation").asText(),
            VECTOR.path("request"),
            claims.path("connectorCredentialFingerprint").asText());

    assertThat(verified.issuer()).isEqualTo(claims.path("issuer").asText());
    assertThat(verified.keyId()).isEqualTo(claims.path("keyId").asText());
    assertThat(verified.keyFingerprint()).matches("^sha256:[a-f0-9]{64}$");
    assertThat(verified.device().deviceId())
        .isEqualTo(claims.path("device").path("deviceId").asText());
    assertThat(verified.process().executableHash())
        .isEqualTo(claims.path("process").path("executableHash").asText());
  }

  private static PublicKey publicKey() {
    try {
      return KeyFactory.getInstance("Ed25519")
          .generatePublic(
              new X509EncodedKeySpec(
                  Base64.getDecoder()
                      .decode(VECTOR.path("publicKeySpkiBase64").asText())));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
