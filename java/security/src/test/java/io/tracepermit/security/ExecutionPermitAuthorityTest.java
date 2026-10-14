package io.tracepermit.security;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.contracts.JsonSupport;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class ExecutionPermitAuthorityTest {
  private static final Path ROOT = Path.of(System.getProperty("tracepermit.repositoryRoot"));
  private static final Instant NOW = Instant.parse("2026-10-08T10:15:31Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void asymmetricEd25519PermitIssuesAndVerifiesWithPublicKeySeparation() throws Exception {
    JsonNode vector = JsonSupport.read(ROOT.resolve("contracts/vectors/execution-permit-v1.json"));
    JsonNode event = JsonSupport.read(ROOT.resolve(vector.path("fixture").asText()));
    JsonNode evaluation = vector.path("evaluation");
    String audience = vector.path("audience").asText();

    KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
    KeyPair keyPair = generator.generateKeyPair();

    AtomicInteger ids = new AtomicInteger();
    ExecutionPermitAuthority authority =
        new ExecutionPermitAuthority(
            ExecutionPermitAuthority.DEFAULT_ISSUER,
            "ed25519-key-01",
            keyPair,
            30,
            CLOCK,
            () -> "permit-" + ids.incrementAndGet(),
            new InMemoryPermitConsumptionStore());

    ExecutionPermitAuthority verifierOnly =
        new ExecutionPermitAuthority(
            ExecutionPermitAuthority.DEFAULT_ISSUER,
            "ed25519-key-01",
            keyPair.getPublic(),
            30,
            CLOCK,
            new InMemoryPermitConsumptionStore());

    String permit = authority.issue(event, evaluation, audience);
    assertThat(permit).isNotNull();

    // The header must declare EdDSA algorithm
    String[] segments = permit.split("\\.");
    assertThat(segments).hasSize(3);
    JsonNode header = JsonSupport.MAPPER.readTree(java.util.Base64.getUrlDecoder().decode(segments[0]));
    assertThat(header.path("alg").asText()).isEqualTo("EdDSA");
    assertThat(header.path("kid").asText()).isEqualTo("ed25519-key-01");

    // Verifier-only executor holding only the public key validates and consumes
    JsonNode claims = verifierOnly.verify(permit, event, audience);
    assertThat(claims.path("permitId").asText()).isEqualTo("permit-1");
    assertThat(claims.path("eventId").asText()).isEqualTo(event.path("eventId").asText());

    JsonNode consumed = verifierOnly.verifyAndConsume(permit, event, audience);
    assertThat(consumed.path("permitId").asText()).isEqualTo("permit-1");

    // Single use: replay is rejected
    assertThatThrownBy(() -> verifierOnly.verifyAndConsume(permit, event, audience))
        .isInstanceOf(ExecutionPermitException.class)
        .extracting(e -> ((ExecutionPermitException) e).code())
        .isEqualTo("EXECUTION_PERMIT_REPLAYED");

    // Verifier-only executor cannot forge/issue permits
    assertThatThrownBy(() -> verifierOnly.issue(event, evaluation, audience))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("verifier-only mode");
  }

  @Test
  void algorithmConfusionAndTamperingAreRejected() throws Exception {
    JsonNode vector = JsonSupport.read(ROOT.resolve("contracts/vectors/execution-permit-v1.json"));
    JsonNode event = JsonSupport.read(ROOT.resolve(vector.path("fixture").asText()));
    JsonNode evaluation = vector.path("evaluation");
    String audience = vector.path("audience").asText();

    KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
    KeyPair keyPair = generator.generateKeyPair();

    ExecutionPermitAuthority asymmetric =
        new ExecutionPermitAuthority(
            ExecutionPermitAuthority.DEFAULT_ISSUER,
            "key-01",
            keyPair,
            30,
            CLOCK,
            () -> "permit-1",
            new InMemoryPermitConsumptionStore());

    ExecutionPermitAuthority symmetric =
        new ExecutionPermitAuthority(
            ExecutionPermitAuthority.DEFAULT_ISSUER,
            "key-01",
            new byte[32],
            30,
            CLOCK,
            () -> "permit-1",
            new InMemoryPermitConsumptionStore());

    String asymmetricPermit = asymmetric.issue(event, evaluation, audience);
    String symmetricPermit = symmetric.issue(event, evaluation, audience);

    // Presenting HS256 permit to an EdDSA verifier must fail algorithm check
    assertThatThrownBy(() -> asymmetric.verify(symmetricPermit, event, audience))
        .isInstanceOf(ExecutionPermitException.class)
        .extracting(e -> ((ExecutionPermitException) e).code())
        .isEqualTo("INVALID_EXECUTION_PERMIT");

    // Presenting EdDSA permit to an HS256 verifier must fail algorithm check
    assertThatThrownBy(() -> symmetric.verify(asymmetricPermit, event, audience))
        .isInstanceOf(ExecutionPermitException.class)
        .extracting(e -> ((ExecutionPermitException) e).code())
        .isEqualTo("INVALID_EXECUTION_PERMIT");

    // Tampered payload fails verification
    String[] parts = asymmetricPermit.split("\\.");
    String tamperedClaims = java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString("{\"tampered\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    String tamperedPermit = parts[0] + "." + tamperedClaims + "." + parts[2];
    assertThatThrownBy(() -> asymmetric.verify(tamperedPermit, event, audience))
        .isInstanceOf(ExecutionPermitException.class)
        .extracting(e -> ((ExecutionPermitException) e).code())
        .isEqualTo("INVALID_EXECUTION_PERMIT");
  }

  @Test
  void constructorValidationsAndAccessors() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
    KeyPair keyPair = generator.generateKeyPair();

    // Short key
    assertThatThrownBy(
            () ->
                new ExecutionPermitAuthority(
                    ExecutionPermitAuthority.DEFAULT_ISSUER,
                    "short-key",
                    new byte[16],
                    30,
                    CLOCK,
                    () -> "id-1",
                    new InMemoryPermitConsumptionStore()))
        .isInstanceOf(IllegalArgumentException.class);

    // Invalid lifetime
    assertThatThrownBy(
            () ->
                new ExecutionPermitAuthority(
                    ExecutionPermitAuthority.DEFAULT_ISSUER,
                    "key-1",
                    new byte[32],
                    0,
                    CLOCK,
                    () -> "id-1",
                    new InMemoryPermitConsumptionStore()))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () ->
                new ExecutionPermitAuthority(
                    ExecutionPermitAuthority.DEFAULT_ISSUER,
                    "key-1",
                    new byte[32],
                    61,
                    CLOCK,
                    () -> "id-1",
                    new InMemoryPermitConsumptionStore()))
        .isInstanceOf(IllegalArgumentException.class);

    // Accessors
    ExecutionPermitAuthority authority =
        new ExecutionPermitAuthority(
            ExecutionPermitAuthority.DEFAULT_ISSUER,
            "ed25519-accessors",
            keyPair,
            30,
            CLOCK,
            () -> "permit-1",
            new InMemoryPermitConsumptionStore());
    assertThat(authority.keyId()).isEqualTo("ed25519-accessors");
    assertThat(authority.algorithm()).isEqualTo("EdDSA");
    assertThat(authority.isVerifierOnly()).isFalse();

    ExecutionPermitAuthority verifierOnly =
        new ExecutionPermitAuthority(
            ExecutionPermitAuthority.DEFAULT_ISSUER,
            "ed25519-accessors",
            keyPair.getPublic(),
            30,
            CLOCK,
            new InMemoryPermitConsumptionStore());
    assertThat(verifierOnly.keyId()).isEqualTo("ed25519-accessors");
    assertThat(verifierOnly.algorithm()).isEqualTo("EdDSA");
    assertThat(verifierOnly.isVerifierOnly()).isTrue();
  }

  @Test
  void malformedPermitsAndValidationEdgeCases() throws Exception {
    JsonNode vector = JsonSupport.read(ROOT.resolve("contracts/vectors/execution-permit-v1.json"));
    JsonNode event = JsonSupport.read(ROOT.resolve(vector.path("fixture").asText()));
    JsonNode evaluation = vector.path("evaluation");
    String audience = vector.path("audience").asText();

    KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
    KeyPair keyPair = generator.generateKeyPair();

    ExecutionPermitAuthority authority =
        new ExecutionPermitAuthority(
            ExecutionPermitAuthority.DEFAULT_ISSUER,
            "ed25519-key-01",
            keyPair,
            30,
            CLOCK,
            () -> "permit-1",
            new InMemoryPermitConsumptionStore());

    // Null or empty permit
    assertThatThrownBy(() -> authority.verify(null, event, audience))
        .isInstanceOf(ExecutionPermitException.class)
        .extracting(e -> ((ExecutionPermitException) e).code())
        .isEqualTo("MALFORMED_EXECUTION_PERMIT");

    assertThatThrownBy(() -> authority.verify("", event, audience))
        .isInstanceOf(ExecutionPermitException.class)
        .extracting(e -> ((ExecutionPermitException) e).code())
        .isEqualTo("MALFORMED_EXECUTION_PERMIT");

    // Invalid segment counts
    assertThatThrownBy(() -> authority.verify("part1.part2", event, audience))
        .isInstanceOf(ExecutionPermitException.class)
        .extracting(e -> ((ExecutionPermitException) e).code())
        .isEqualTo("MALFORMED_EXECUTION_PERMIT");

    assertThatThrownBy(() -> authority.verify("part1.part2.part3.part4", event, audience))
        .isInstanceOf(ExecutionPermitException.class)
        .extracting(e -> ((ExecutionPermitException) e).code())
        .isEqualTo("MALFORMED_EXECUTION_PERMIT");

    // Audience mismatch
    String permit = authority.issue(event, evaluation, audience);
    assertThatThrownBy(() -> authority.verify(permit, event, "urn:other:audience"))
        .isInstanceOf(ExecutionPermitException.class)
        .extracting(e -> ((ExecutionPermitException) e).code())
        .isEqualTo("WRONG_PERMIT_AUDIENCE");

    // Expired permit check
    ExecutionPermitAuthority expiredVerifier =
        new ExecutionPermitAuthority(
            ExecutionPermitAuthority.DEFAULT_ISSUER,
            "ed25519-key-01",
            keyPair.getPublic(),
            30,
            Clock.fixed(NOW.plusSeconds(300), ZoneOffset.UTC),
            new InMemoryPermitConsumptionStore());
    assertThatThrownBy(() -> expiredVerifier.verify(permit, event, audience))
        .isInstanceOf(ExecutionPermitException.class)
        .extracting(e -> ((ExecutionPermitException) e).code())
        .isEqualTo("EXPIRED_EXECUTION_PERMIT");
  }
}
