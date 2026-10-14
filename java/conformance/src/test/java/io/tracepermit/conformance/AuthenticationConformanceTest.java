package io.tracepermit.conformance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.identity.AuthenticatedConnector;
import io.tracepermit.identity.AuthenticationException;
import io.tracepermit.identity.ConnectorOrigin;
import io.tracepermit.identity.ConnectorScope;
import io.tracepermit.identity.DevelopmentProfile;
import io.tracepermit.identity.DevelopmentTestTokenIssuer;
import io.tracepermit.identity.DevelopmentTokenVerifier;
import io.tracepermit.identity.ScopeAuthorizer;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class AuthenticationConformanceTest {
  private static final Path ROOT = Path.of(System.getProperty("tracepermit.repositoryRoot"));
  private static final JsonNode VECTOR =
      JsonSupport.read(ROOT.resolve("contracts/vectors/development-access-token-v1.json"));
  private static final Instant NOW = Instant.parse(VECTOR.path("now").asText());
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final byte[] SIGNING_KEY =
      HexFormat.of().parseHex(VECTOR.path("testOnlySigningKeyHex").asText());

  @Test
  void deterministicIssuerAndVerifierMatchTheGoldenVector() {
    DevelopmentTestTokenIssuer issuer =
        new DevelopmentTestTokenIssuer(
            SIGNING_KEY,
            CLOCK,
            () -> "018f47a2-f5ce-7a4b-8f54-80e5d3eaf081",
            VECTOR.path("tokenLifetimeSeconds").asInt());
    String token = issuer.issueToken(DevelopmentProfile.CHATGPT_READONLY);
    assertThat(token).isEqualTo(VECTOR.path("expectedCompactToken").asText());

    AuthenticatedConnector connector = verifier().authenticate("Bearer " + token);
    JsonNode expected = VECTOR.path("expectedIdentity");
    assertThat(connector.subjectId()).isEqualTo(expected.path("subjectId").asText());
    assertThat(connector.displayName()).isEqualTo(expected.path("displayName").asText());
    assertThat(connector.organizationId()).isEqualTo(expected.path("organizationId").asText());
    assertThat(connector.origin()).isEqualTo(ConnectorOrigin.CHATGPT_WEB);
    assertThat(connector.clientId()).isEqualTo(expected.path("clientId").asText());
    assertThat(connector.clientName()).isEqualTo(expected.path("clientName").asText());
    assertThat(connector.issuer()).isEqualTo(expected.path("issuer").asText());
    assertThat(connector.verifiedAt()).isEqualTo(Instant.parse(expected.path("verifiedAt").asText()));
    assertThat(connector.credentialFingerprint().value())
        .isEqualTo(expected.path("credentialFingerprint").asText());
    assertThat(connector.scopes())
        .extracting(ConnectorScope::value)
        .containsExactly("aws.logs.discover", "aws.logs.read");
    assertThat(connector.expiresAt().getEpochSecond()).isEqualTo(expected.path("expiresAt").asLong());
    for (JsonNode scopeCheck : VECTOR.path("requiredScopeChecks")) {
      assertThat(
              ScopeAuthorizer.isAllowed(
                  connector, ConnectorScope.of(scopeCheck.path("scope").asText())))
          .as(scopeCheck.path("scope").asText())
          .isEqualTo(scopeCheck.path("allowed").asBoolean());
    }
  }

  @Test
  void missingAndMalformedCredentialsFailBeforeClaimValidation() {
    assertCode(() -> verifier().authenticate(null), "MISSING_BEARER_TOKEN");
    assertCode(() -> verifier().authenticate(""), "MISSING_BEARER_TOKEN");
    assertCode(() -> verifier().authenticate("Basic abc"), "MISSING_BEARER_TOKEN");
    assertCode(() -> verifier().authenticate("Bearer a.b"), "MISSING_BEARER_TOKEN");
    assertCode(
        () -> verifier().authenticate("Bearer " + "a".repeat(8191) + ".a.a"),
        "MALFORMED_ACCESS_TOKEN");

    String malformedJson =
        encode("not-json".getBytes(StandardCharsets.UTF_8))
            + "."
            + encode("{}".getBytes(StandardCharsets.UTF_8))
            + "."
            + encode(new byte[32]);
    assertCode(
        () -> verifier().authenticate("Bearer " + malformedJson), "MALFORMED_ACCESS_TOKEN");

    String duplicateHeader =
        tokenWithRawHeader(
            "{\"alg\":\"HS256\",\"alg\":\"HS256\",\"typ\":\"JWT\",\"kid\":\"ephemeral-development-key\"}");
    assertCode(
        () -> verifier().authenticate("Bearer " + duplicateHeader), "MALFORMED_ACCESS_TOKEN");
    String trailingJson =
        tokenWithRawHeader(
            "{\"alg\":\"HS256\",\"typ\":\"JWT\",\"kid\":\"ephemeral-development-key\"}{}");
    assertCode(
        () -> verifier().authenticate("Bearer " + trailingJson), "MALFORMED_ACCESS_TOKEN");
  }

  @Test
  void signatureEncodingAndStrictHeaderAreVerified() {
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
    assertCode(() -> verifier().authenticate("Bearer " + tampered), "INVALID_ACCESS_TOKEN");

    String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    int lastIndex = alphabet.indexOf(segments[2].charAt(segments[2].length() - 1));
    String nonCanonicalSignature =
        segments[2].substring(0, segments[2].length() - 1)
            + alphabet.charAt(lastIndex + 1);
    assertThat(Base64.getUrlDecoder().decode(nonCanonicalSignature))
        .isEqualTo(Base64.getUrlDecoder().decode(segments[2]));
    String nonCanonical = segments[0] + "." + segments[1] + "." + nonCanonicalSignature;
    assertCode(
        () -> verifier().authenticate("Bearer " + nonCanonical), "INVALID_ACCESS_TOKEN");

    assertInvalidHeader(token, header -> header.put("alg", "none"));
    assertInvalidHeader(token, header -> header.put("typ", "at+jwt"));
    assertInvalidHeader(token, header -> header.put("kid", "another-key"));
    assertInvalidHeader(token, header -> header.put("unexpected", true));
  }

  @Test
  void issuerAudienceAndLifetimeClaimsFailClosed() {
    String token = VECTOR.path("expectedCompactToken").asText();
    long current = NOW.getEpochSecond();
    assertInvalidPayload(token, payload -> payload.put("iss", "https://attacker.example.invalid"));
    assertInvalidPayload(token, payload -> payload.put("aud", "another-service"));
    assertInvalidPayload(token, payload -> payload.put("iat", current + 31));
    assertCode(
        () ->
            verifier()
                .authenticate(
                    "Bearer " + mutateAndSign(token, null, payload -> payload.put("exp", current))),
        "EXPIRED_ACCESS_TOKEN");
    assertInvalidPayload(
        token,
        payload -> {
          payload.put("iat", current + 10);
          payload.put("exp", current + 10);
        });
    assertInvalidPayload(
        token,
        payload -> {
          payload.put("iat", current);
          payload.put("exp", current + DevelopmentTokenVerifier.MAX_TOKEN_LIFETIME_SECONDS + 1);
        });
    assertInvalidPayload(token, payload -> payload.put("jti", "not-a-uuid"));
    assertInvalidPayload(token, payload -> payload.put("iat", 9_007_199_254_740_992L));
    assertInvalidPayload(token, payload -> payload.put("unexpected", "claim"));
  }

  @Test
  void invocationScopeIsIndependentAndAuthenticatedResultsRetainNoToken() {
    DevelopmentTestTokenIssuer issuer =
        new DevelopmentTestTokenIssuer(
            SIGNING_KEY,
            CLOCK,
            () -> "018f47a2-f5ce-7a4b-8f54-80e5d3eaf083",
            VECTOR.path("tokenLifetimeSeconds").asInt());
    String token = issuer.issueToken(DevelopmentProfile.CHATGPT_DISCOVERY_ONLY);
    AuthenticatedConnector connector = verifier().authenticate("Bearer " + token);
    ConnectorScope discover = ConnectorScope.of("aws.logs.discover");
    ConnectorScope invoke = ConnectorScope.of("aws.logs.read");
    assertThat(ScopeAuthorizer.isAllowed(connector, discover)).isTrue();
    assertThat(ScopeAuthorizer.isAllowed(connector, invoke)).isFalse();
    assertCode(() -> ScopeAuthorizer.require(connector, invoke), "AUTHORIZATION_DENIED");
    assertThat(connector.toString()).doesNotContain(token);
    assertThat(AuthenticatedConnector.class.getRecordComponents())
        .extracting(component -> component.getName())
        .doesNotContain("accessToken", "authorization", "bearerToken");
  }

  private static DevelopmentTokenVerifier verifier() {
    return new DevelopmentTokenVerifier(SIGNING_KEY, CLOCK);
  }

  private static void assertInvalidHeader(String token, Consumer<ObjectNode> mutation) {
    assertCode(
        () -> verifier().authenticate("Bearer " + mutateAndSign(token, mutation, null)),
        "INVALID_ACCESS_TOKEN");
  }

  private static void assertInvalidPayload(String token, Consumer<ObjectNode> mutation) {
    assertCode(
        () -> verifier().authenticate("Bearer " + mutateAndSign(token, null, mutation)),
        "INVALID_ACCESS_TOKEN");
  }

  private static void assertCode(ThrowingCallable operation, String expectedCode) {
    assertThatThrownBy(operation)
        .isInstanceOf(AuthenticationException.class)
        .extracting(error -> ((AuthenticationException) error).code())
        .isEqualTo(expectedCode);
  }

  private static String mutateAndSign(
      String token, Consumer<ObjectNode> headerMutation, Consumer<ObjectNode> payloadMutation) {
    String[] segments = token.split("\\.");
    ObjectNode header = parseObject(segments[0]);
    ObjectNode payload = parseObject(segments[1]);
    if (headerMutation != null) headerMutation.accept(header);
    if (payloadMutation != null) payloadMutation.accept(payload);
    String signingInput = encodeJson(header) + "." + encodeJson(payload);
    return signingInput + "." + encode(sign(signingInput));
  }

  private static String tokenWithRawHeader(String rawHeader) {
    String token = VECTOR.path("expectedCompactToken").asText();
    String payload = token.split("\\.")[1];
    String signingInput = encode(rawHeader.getBytes(StandardCharsets.UTF_8)) + "." + payload;
    return signingInput + "." + encode(sign(signingInput));
  }

  private static ObjectNode parseObject(String segment) {
    try {
      return (ObjectNode) JsonSupport.MAPPER.readTree(Base64.getUrlDecoder().decode(segment));
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static String encodeJson(JsonNode value) {
    try {
      return encode(JsonSupport.MAPPER.writeValueAsBytes(value));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static String encode(byte[] value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  private static byte[] sign(String input) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(SIGNING_KEY, "HmacSHA256"));
      return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
