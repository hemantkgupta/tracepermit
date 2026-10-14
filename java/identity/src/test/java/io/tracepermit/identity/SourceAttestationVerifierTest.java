package io.tracepermit.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class SourceAttestationVerifierTest {
  private static final Instant NOW = Instant.parse("2030-01-01T00:00:30Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final String CREDENTIAL_FINGERPRINT = "sha256:" + "1".repeat(64);

  @Test
  void runtimeGeneratedEd25519ProofReturnsOnlyBoundedSourceEvidence() {
    Fixture fixture = fixture();
    String compact = fixture.signer().sign(claims(request(), UUID.randomUUID().toString()));

    VerifiedSourceEvidence verified =
        fixture.verifier().verify(compact, request(), CREDENTIAL_FINGERPRINT);

    assertThat(verified.issuer()).isEqualTo("tracepermit-endpoint:device-simulated-001");
    assertThat(verified.keyId()).isEqualTo("endpoint-key-001");
    assertThat(verified.keyFingerprint()).matches("^sha256:[a-f0-9]{64}$");
    assertThat(verified.device().deviceId()).isEqualTo("device-simulated-001");
    assertThat(verified.process().executableName()).isEqualTo("codex");
    assertThat(verified.triggerEvidence().requestedMode()).isEqualTo("interactive");
    assertThat(verified.toString())
        .doesNotContain(compact, "tokenId", "requestHash", "connectorCredentialFingerprint");
  }

  @Test
  void malformedNonCanonicalTamperedUnknownFormatAndUnknownKeyProofsFailClosed()
      throws Exception {
    Fixture fixture = fixture();
    String valid = fixture.signer().sign(claims(request(), UUID.randomUUID().toString()));
    String[] segments = valid.split("\\.");
    char replacement = segments[2].charAt(0) == 'A' ? 'B' : 'A';
    String tampered =
        segments[0] + "." + segments[1] + "." + replacement + segments[2].substring(1);
    String nonCanonicalHeader =
        encode(
            "{\"typ\":\"tracepermit-source-attestation+jws\",\"kid\":\"endpoint-key-001\",\"alg\":\"EdDSA\"}"
                .getBytes(StandardCharsets.UTF_8));
    ObjectNode unknownAlgorithmHeader = JsonSupport.MAPPER.createObjectNode();
    unknownAlgorithmHeader.put("alg", "none");
    unknownAlgorithmHeader.put("kid", "endpoint-key-001");
    unknownAlgorithmHeader.put("typ", "tracepermit-source-attestation+jws");
    String algorithmHeader =
        encode(
            CanonicalJson.canonicalize(unknownAlgorithmHeader)
                .getBytes(StandardCharsets.UTF_8));
    ObjectNode unknownVersionPayload =
        (ObjectNode)
            JsonSupport.MAPPER.readTree(Base64.getUrlDecoder().decode(segments[1]));
    unknownVersionPayload.put("version", "2.0");
    String versionPayload =
        encode(
            CanonicalJson.canonicalize(unknownVersionPayload)
                .getBytes(StandardCharsets.UTF_8));
    ObjectNode unknownKeyClaims = claims(request(), UUID.randomUUID().toString());
    unknownKeyClaims.put("keyId", "endpoint-key-unknown");
    String unknownKey = fixture.signer().sign(unknownKeyClaims);

    assertCode(() -> fixture.verifier().verify("not-a-jws", request(), CREDENTIAL_FINGERPRINT), "MALFORMED_SOURCE_ATTESTATION");
    assertCode(
        () ->
            fixture
                .verifier()
                .verify(
                    nonCanonicalHeader + "." + segments[1] + "." + segments[2],
                    request(),
                    CREDENTIAL_FINGERPRINT),
        "MALFORMED_SOURCE_ATTESTATION");
    assertCode(
        () -> fixture.verifier().verify(tampered, request(), CREDENTIAL_FINGERPRINT),
        "INVALID_SOURCE_ATTESTATION");
    assertCode(
        () ->
            fixture
                .verifier()
                .verify(
                    algorithmHeader + "." + segments[1] + "." + segments[2],
                    request(),
                    CREDENTIAL_FINGERPRINT),
        "INVALID_SOURCE_ATTESTATION");
    assertCode(
        () ->
            fixture
                .verifier()
                .verify(
                    segments[0] + "." + versionPayload + "." + segments[2],
                    request(),
                    CREDENTIAL_FINGERPRINT),
        "INVALID_SOURCE_ATTESTATION");
    assertCode(
        () -> fixture.verifier().verify(unknownKey, request(), CREDENTIAL_FINGERPRINT),
        "INVALID_SOURCE_ATTESTATION");
  }

  @Test
  void trustContextRequestCredentialAndTimeSubstitutionFailClosed() {
    assertInvalidMutation(claims -> claims.put("audience", "another-gateway"), request(), CREDENTIAL_FINGERPRINT, "INVALID_SOURCE_ATTESTATION");
    assertInvalidMutation(claims -> claims.put("issuer", "tracepermit-endpoint:unknown"), request(), CREDENTIAL_FINGERPRINT, "INVALID_SOURCE_ATTESTATION");
    assertInvalidMutation(
        claims -> ((ObjectNode) claims.path("device")).put("deviceId", "device-simulated-002"),
        request(),
        CREDENTIAL_FINGERPRINT,
        "INVALID_SOURCE_ATTESTATION");

    ObjectNode changedRequest = request();
    changedRequest.put("method", "tools/call");
    assertInvalidMutation(claims -> {}, changedRequest, CREDENTIAL_FINGERPRINT, "INVALID_SOURCE_ATTESTATION");
    assertInvalidMutation(claims -> {}, request(), "sha256:" + "3".repeat(64), "INVALID_SOURCE_ATTESTATION");
    assertInvalidMutation(
        claims -> {
          claims.put("issuedAt", "2030-01-01T00:00:36Z");
          claims.put("expiresAt", "2030-01-01T00:00:56Z");
        },
        request(),
        CREDENTIAL_FINGERPRINT,
        "INVALID_SOURCE_ATTESTATION");
    assertInvalidMutation(
        claims -> {
          claims.put("issuedAt", "2029-12-31T23:59:30Z");
          claims.put("expiresAt", "2030-01-01T00:00:30Z");
          ((ObjectNode) claims.path("process"))
              .put("startedAt", "2029-12-31T23:59:00Z");
        },
        request(),
        CREDENTIAL_FINGERPRINT,
        "EXPIRED_SOURCE_ATTESTATION");
  }

  @Test
  void exactlyOneOfThirtyTwoConcurrentClaimsSucceeds() throws Exception {
    Fixture fixture = fixture();
    String compact = fixture.signer().sign(claims(request(), UUID.randomUUID().toString()));
    List<Callable<String>> operations = new ArrayList<>();
    for (int index = 0; index < 32; index++) {
      operations.add(
          () -> {
            try {
              fixture.verifier().verify(compact, request(), CREDENTIAL_FINGERPRINT);
              return "accepted";
            } catch (SourceAttestationException exception) {
              return exception.code();
            }
          });
    }

    List<String> results;
    try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
      results = executor.invokeAll(operations).stream().map(future -> {
        try {
          return future.get();
        } catch (Exception exception) {
          throw new IllegalStateException(exception);
        }
      }).toList();
    }

    assertThat(results).filteredOn("accepted"::equals).hasSize(1);
    assertThat(results).filteredOn("SOURCE_ATTESTATION_REPLAYED"::equals).hasSize(31);
  }

  @Test
  void strictClaimsAndSignerConfigurationRejectUnsupportedInput() throws Exception {
    Fixture fixture = fixture();
    ObjectNode unexpected = claims(request(), UUID.randomUUID().toString());
    unexpected.put("publicKey", "request-controlled-key");
    ObjectNode longLifetime = claims(request(), UUID.randomUUID().toString());
    longLifetime.put("expiresAt", "2030-01-01T00:01:01Z");
    KeyPair rsa = KeyPairGenerator.getInstance("RSA").generateKeyPair();

    assertThatThrownBy(() -> fixture.signer().sign(unexpected))
        .isInstanceOf(SourceAttestationException.class)
        .extracting(error -> ((SourceAttestationException) error).code())
        .isEqualTo("INVALID_SOURCE_ATTESTATION");
    assertThatThrownBy(() -> fixture.signer().sign(longLifetime))
        .isInstanceOf(SourceAttestationException.class)
        .extracting(error -> ((SourceAttestationException) error).code())
        .isEqualTo("INVALID_SOURCE_ATTESTATION");
    assertThatThrownBy(() -> new SourceAttestationSigner(rsa.getPrivate()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Ed25519");
  }

  private static void assertInvalidMutation(
      java.util.function.Consumer<ObjectNode> mutation,
      JsonNode presentedRequest,
      String fingerprint,
      String code) {
    Fixture fixture = fixture();
    ObjectNode claims = claims(request(), UUID.randomUUID().toString());
    mutation.accept(claims);
    String compact = fixture.signer().sign(claims);
    assertCode(() -> fixture.verifier().verify(compact, presentedRequest, fingerprint), code);
  }

  private static ObjectNode request() {
    ObjectNode request = JsonSupport.MAPPER.createObjectNode();
    request.put("jsonrpc", "2.0");
    request.put("id", 99);
    request.put("method", "tools/list");
    request.set("params", JsonSupport.MAPPER.createObjectNode());
    return request;
  }

  private static ObjectNode claims(JsonNode boundRequest, String tokenId) {
    ObjectNode claims = JsonSupport.MAPPER.createObjectNode();
    claims.put("version", "1.0");
    claims.put("type", "tracepermit-source-attestation+jws");
    claims.put("canonicalizationProfile", CanonicalJson.PROFILE);
    claims.put("requestBindingProfile", "tracepermit-mcp-method-params-v1");
    claims.put("issuer", "tracepermit-endpoint:device-simulated-001");
    claims.put("keyId", "endpoint-key-001");
    claims.put("audience", SourceAttestationVerifier.DEFAULT_AUDIENCE);
    claims.put("tokenId", tokenId);
    claims.put("issuedAt", "2030-01-01T00:00:00Z");
    claims.put("expiresAt", "2030-01-01T00:01:00Z");
    claims.put("connectorCredentialFingerprint", CREDENTIAL_FINGERPRINT);
    claims.put("requestHash", SourceAttestationVerifier.hashRequest(boundRequest));
    ObjectNode device = claims.putObject("device");
    device.put("deviceId", "device-simulated-001");
    device.put("platform", "macos");
    device.put("assurance", "development");
    ObjectNode process = claims.putObject("process");
    process.put("executableName", "codex");
    process.put("executableHash", "sha256:" + "2".repeat(64));
    process.put("parentExecutableName", "terminal");
    process.put("startedAt", "2030-01-01T00:00:00Z");
    ObjectNode trigger = claims.putObject("triggerEvidence");
    trigger.put("requestedMode", "interactive");
    trigger.put("terminalAttached", true);
    return claims;
  }

  private static Fixture fixture() {
    KeyPair keyPair = ed25519();
    StaticEndpointKeyRegistry registry =
        new StaticEndpointKeyRegistry(
            List.of(
                new EndpointKeyRegistry.EnrolledEndpointKey(
                    "tracepermit-endpoint:device-simulated-001",
                    "endpoint-key-001",
                    "device-simulated-001",
                    keyPair.getPublic())));
    return new Fixture(
        new SourceAttestationSigner(keyPair.getPrivate()),
        new SourceAttestationVerifier(
            registry, new InMemorySourceAttestationReplayStore(), CLOCK));
  }

  private static KeyPair ed25519() {
    try {
      return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static void assertCode(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable operation, String expected) {
    assertThatThrownBy(operation)
        .isInstanceOf(SourceAttestationException.class)
        .extracting(error -> ((SourceAttestationException) error).code())
        .isEqualTo(expected);
  }

  private static String encode(byte[] value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  private record Fixture(
      SourceAttestationSigner signer, SourceAttestationVerifier verifier) {}
}
