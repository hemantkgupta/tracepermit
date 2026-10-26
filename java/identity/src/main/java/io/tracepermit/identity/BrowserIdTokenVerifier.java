package io.tracepermit.identity;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;

/** Narrow RS256 OIDC code-flow profile. An ID token never substitutes for API authorization. */
public final class BrowserIdTokenVerifier {
  private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private final OidcProviderConfiguration provider;
  private final OidcVerificationKeyResolver keys;
  private final Clock clock;
  private final String organization;

  public BrowserIdTokenVerifier(OidcProviderConfiguration provider,
      OidcVerificationKeyResolver keys, Clock clock) {
    this(provider, keys, clock, null);
  }

  public BrowserIdTokenVerifier(OidcProviderConfiguration provider,
      OidcVerificationKeyResolver keys, Clock clock, String organization) {
    this.provider = java.util.Objects.requireNonNull(provider);
    this.keys = java.util.Objects.requireNonNull(keys);
    this.clock = java.util.Objects.requireNonNull(clock);
    if (organization != null && (!"org_id".equals(provider.tenantClaim())
        || provider.tenantId(organization) == null)) {
      throw new IllegalArgumentException("INVALID_BROWSER_ORGANIZATION");
    }
    this.organization = organization;
  }

  public java.time.Instant verify(String token, String clientId, String nonce, String accessToken,
      AuthenticatedConnector connector) {
    try {
      if (token == null || token.length() > 16_384) throw new IllegalArgumentException();
      String[] parts = token.split("\\.", -1);
      if (parts.length != 3) throw new IllegalArgumentException();
      JsonNode header = segment(parts[0]);
      JsonNode claims = segment(parts[1]);
      if (!header.isObject() || !claims.isObject() || !"RS256".equals(header.path("alg").asText())
          || !header.path("kid").isTextual() || !header.path("kid").asText().matches("[A-Za-z0-9_-]{1,128}")
          || header.size() != (header.has("typ") ? 3 : 2)
          || (header.has("typ") && !"JWT".equals(header.path("typ").asText()))) {
        throw new IllegalArgumentException();
      }
      var jwt = SignedJWT.parse(token);
      if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())
          || !jwt.verify(new RSASSAVerifier(keys.resolve(provider, header.path("kid").asText(),
              "RS256", clock.instant())))) throw new IllegalArgumentException();
      long now = clock.instant().getEpochSecond();
      long issued = integer(claims, "iat");
      long expires = integer(claims, "exp");
      JsonNode audience = claims.path("aud");
      // Deliberately single-audience: multi-audience IdP profiles require separate qualification.
      if (!audience.isTextual() || !clientId.equals(audience.textValue())
          || !provider.issuer().toString().equals(claims.path("iss").asText())
          || !connector.subjectId().equals(provider.issuer() + "#" + text(claims, "sub"))
          || !clientId.equals(connector.clientId())
          || !nonce.equals(text(claims, "nonce"))
          || (organization != null && !organization.equals(text(claims, "org_id")))
          || (claims.has("azp") && !clientId.equals(text(claims, "azp")))
          || issued > now + 30 || expires <= now || expires <= issued || expires - issued > 900
          || (claims.has("nbf") && integer(claims, "nbf") > now + 30)) {
        throw new IllegalArgumentException();
      }
      if (claims.has("at_hash")) {
        byte[] hash = MessageDigest.getInstance("SHA-256")
            .digest(accessToken.getBytes(StandardCharsets.US_ASCII));
        String expected = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(java.util.Arrays.copyOf(hash, hash.length / 2));
        if (!expected.equals(text(claims, "at_hash"))) throw new IllegalArgumentException();
      }
      return java.time.Instant.ofEpochSecond(expires);
    } catch (Exception failure) {
      throw new SecurityException("INVALID_BROWSER_ID_TOKEN");
    }
  }

  private static JsonNode segment(String encoded) throws Exception {
    byte[] decoded = Base64.getUrlDecoder().decode(encoded);
    if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(encoded)) {
      throw new IllegalArgumentException();
    }
    return JSON.readTree(decoded);
  }

  private static long integer(JsonNode claims, String name) {
    JsonNode value = claims.path(name);
    if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0
        || value.longValue() > 9_007_199_254_740_991L) throw new IllegalArgumentException();
    return value.longValue();
  }

  private static String text(JsonNode claims, String name) {
    JsonNode value = claims.path(name);
    if (!value.isTextual() || value.textValue().isEmpty()) throw new IllegalArgumentException();
    return value.textValue();
  }
}
