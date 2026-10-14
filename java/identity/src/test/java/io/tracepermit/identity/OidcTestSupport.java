package io.tracepermit.identity;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import io.tracepermit.contracts.JsonSupport;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

final class OidcTestSupport {
  static final Instant NOW = Instant.parse("2030-01-01T00:00:30Z");
  static final String ISSUER = "https://identity.example.invalid";
  static final String AUDIENCE = "https://mcp.tracepermit.example.invalid";
  static final String KEY_ID = "oidc-key-001";

  private OidcTestSupport() {}

  static KeyPair rsaKeyPair() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException(exception);
    }
  }

  static OidcProviderConfiguration provider() {
    return new OidcProviderConfiguration(
        URI.create(ISSUER),
        AUDIENCE,
        URI.create(ISSUER + "/.well-known/jwks.json"),
        URI.create(AUDIENCE + "/.well-known/oauth-protected-resource"),
        List.of(URI.create(ISSUER)),
        "TracePermit MCP",
        Map.of("chatgpt-connector", "ChatGPT Web"),
        "org_id",
        Map.of("enterprise-acme", "tenant:acme"),
        Set.of(ConnectorScope.of("aws.logs.discover"), ConnectorScope.of("aws.logs.read")),
        ConnectorOrigin.CHATGPT_WEB,
        Duration.ofMinutes(5),
        Duration.ofMinutes(1),
        Duration.ofSeconds(30));
  }

  static String publicJwks(KeyPair... pairs) {
    List<JWK> keys =
        java.util.stream.IntStream.range(0, pairs.length)
            .mapToObj(
                index ->
                    (JWK)
                        new RSAKey.Builder((RSAPublicKey) pairs[index].getPublic())
                        .keyID(index == 0 ? KEY_ID : "oidc-key-" + String.format("%03d", index + 1))
                        .keyUse(KeyUse.SIGNATURE)
                        .algorithm(JWSAlgorithm.RS256)
                        .build())
            .toList();
    return new JWKSet(keys).toString(false);
  }

  static String token(KeyPair keyPair) {
    return token(keyPair, header -> {}, claims -> {});
  }

  static String token(
      KeyPair keyPair, Consumer<ObjectNode> headerMutation, Consumer<ObjectNode> claimMutation) {
    ObjectNode header = JsonSupport.MAPPER.createObjectNode();
    header.put("alg", "RS256");
    header.put("typ", "at+jwt");
    header.put("kid", KEY_ID);
    headerMutation.accept(header);

    ObjectNode claims = JsonSupport.MAPPER.createObjectNode();
    claims.put("iss", ISSUER);
    claims.put("aud", AUDIENCE);
    claims.put("sub", "user:alice@example.invalid");
    claims.put("name", "Alice Example");
    claims.put("client_id", "chatgpt-connector");
    claims.put("org_id", "enterprise-acme");
    claims.put("scope", "aws.logs.discover aws.logs.read");
    claims.put("iat", NOW.minusSeconds(30).getEpochSecond());
    claims.put("nbf", NOW.minusSeconds(30).getEpochSecond());
    claims.put("exp", NOW.plusSeconds(270).getEpochSecond());
    claims.put("jti", "018f47a2-f5ce-7a4b-8f54-80e5d3eaf090");
    claimMutation.accept(claims);
    return sign(keyPair, encode(header) + "." + encode(claims));
  }

  static String rawToken(KeyPair keyPair, String rawHeader, String rawClaims) {
    return sign(
        keyPair,
        Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(rawHeader.getBytes(StandardCharsets.UTF_8))
            + "."
            + Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(rawClaims.getBytes(StandardCharsets.UTF_8)));
  }

  private static String encode(ObjectNode value) {
    try {
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(JsonSupport.MAPPER.writeValueAsBytes(value));
    } catch (java.io.IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static String sign(KeyPair keyPair, String input) {
    try {
      Signature signature = Signature.getInstance("SHA256withRSA");
      signature.initSign(keyPair.getPrivate());
      signature.update(input.getBytes(StandardCharsets.UTF_8));
      return input
          + "."
          + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
