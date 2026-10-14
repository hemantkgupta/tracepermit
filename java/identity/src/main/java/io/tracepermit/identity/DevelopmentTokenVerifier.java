package io.tracepermit.identity;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class DevelopmentTokenVerifier implements ConnectorAuthenticator {
  public static final String DEFAULT_ISSUER = "tracepermit-development-issuer";
  public static final String DEFAULT_AUDIENCE = "tracepermit-mcp";
  public static final String DEFAULT_KEY_ID = "ephemeral-development-key";
  public static final int MAX_TOKEN_LIFETIME_SECONDS = 600;

  private static final int MAX_TOKEN_LENGTH = 8192;
  private static final BigInteger MAX_SAFE_INTEGER = BigInteger.valueOf(9_007_199_254_740_991L);
  private static final Pattern BEARER =
      Pattern.compile("^Bearer ([A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+)$");
  private static final Pattern UUID =
      Pattern.compile(
          "^([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}|00000000-0000-0000-0000-000000000000|ffffffff-ffff-ffff-ffff-ffffffffffff)$");
  private static final Set<String> HEADER_FIELDS = Set.of("alg", "typ", "kid");
  private static final Set<String> PAYLOAD_FIELDS =
      Set.of(
          "iss",
          "aud",
          "sub",
          "name",
          "org",
          "origin",
          "client_id",
          "client_name",
          "scope",
          "iat",
          "exp",
          "jti");
  private static final ObjectMapper STRICT_MAPPER =
      new ObjectMapper(
              JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private final byte[] signingKey;
  private final Clock clock;
  private final String issuer;
  private final String audience;
  private final String keyId;

  public DevelopmentTokenVerifier(byte[] signingKey, Clock clock) {
    this(signingKey, clock, DEFAULT_ISSUER, DEFAULT_AUDIENCE, DEFAULT_KEY_ID);
  }

  public DevelopmentTokenVerifier(
      byte[] signingKey, Clock clock, String issuer, String audience, String keyId) {
    this.signingKey = Objects.requireNonNull(signingKey).clone();
    this.clock = Objects.requireNonNull(clock);
    this.issuer = nonEmpty(issuer);
    this.audience = nonEmpty(audience);
    this.keyId = nonEmpty(keyId);
    if (this.signingKey.length < 32) {
      throw new IllegalArgumentException("Development signing keys must contain at least 32 bytes.");
    }
  }

  @Override
  public AuthenticatedConnector authenticate(String authorizationHeader) {
    var match = BEARER.matcher(authorizationHeader == null ? "" : authorizationHeader);
    if (!match.matches()) {
      throw error("MISSING_BEARER_TOKEN", "A valid Bearer authorization header is required.");
    }
    String accessToken = match.group(1);
    if (accessToken.length() > MAX_TOKEN_LENGTH) {
      throw error("MALFORMED_ACCESS_TOKEN", "The bearer token is malformed.");
    }
    String[] segments = accessToken.split("\\.", -1);
    if (segments.length != 3
        || segments[0].isEmpty()
        || segments[1].isEmpty()
        || segments[2].isEmpty()) {
      throw error("MALFORMED_ACCESS_TOKEN", "The bearer token is malformed.");
    }

    byte[] headerBytes = decodeCanonical(segments[0]);
    byte[] payloadBytes = decodeCanonical(segments[1]);
    byte[] presentedSignature = decodeCanonical(segments[2]);
    JsonNode header = parseJson(headerBytes);
    JsonNode payload = parseJson(payloadBytes);
    requireExactFields(header, HEADER_FIELDS);
    requireExactFields(payload, PAYLOAD_FIELDS);
    if (!"HS256".equals(requiredText(header, "alg"))
        || !"JWT".equals(requiredText(header, "typ"))
        || !keyId.equals(requiredText(header, "kid"))) {
      throw invalidClaims();
    }
    ParsedPayload claims = parsePayload(payload);

    byte[] expectedSignature = sign(segments[0] + "." + segments[1]);
    if (!MessageDigest.isEqual(expectedSignature, presentedSignature)) {
      throw error("INVALID_ACCESS_TOKEN", "The bearer token signature is invalid.");
    }
    if (!issuer.equals(claims.issuer()) || !audience.equals(claims.audience())) {
      throw invalidClaims();
    }

    Instant verifiedAt = clock.instant();
    long currentTime = verifiedAt.getEpochSecond();
    if (claims.issuedAt() > currentTime + 30) {
      throw error("INVALID_ACCESS_TOKEN", "The bearer token was issued in the future.");
    }
    if (claims.expiresAt() <= currentTime) {
      throw error("EXPIRED_ACCESS_TOKEN", "The bearer token has expired.");
    }
    if (claims.expiresAt() <= claims.issuedAt()
        || claims.expiresAt() - claims.issuedAt() > MAX_TOKEN_LIFETIME_SECONDS) {
      throw error("INVALID_ACCESS_TOKEN", "The bearer token lifetime is invalid.");
    }

    List<ConnectorScope> scopes =
        claims.scopes().stream().map(ConnectorScope::new).toList();
    return new AuthenticatedConnector(
        claims.subjectId(),
        claims.displayName(),
        claims.organizationId(),
        claims.origin(),
        claims.clientId(),
        claims.clientName(),
        claims.issuer(),
        verifiedAt,
        "tracepermit-development-hs256-v1",
        keyId,
        fingerprint(accessToken),
        scopes,
        Instant.ofEpochSecond(claims.expiresAt()));
  }

  private static ParsedPayload parsePayload(JsonNode payload) {
    String tokenId = requiredText(payload, "jti");
    if (!UUID.matcher(tokenId).matches()) throw invalidClaims();
    JsonNode scopeNode = payload.get("scope");
    if (scopeNode == null || !scopeNode.isArray()) throw invalidClaims();
    List<String> scopes = new ArrayList<>();
    for (JsonNode value : scopeNode) {
      if (!value.isTextual() || value.textValue().isEmpty()) throw invalidClaims();
      scopes.add(value.textValue());
    }
    long issuedAt = requiredSafeInteger(payload, "iat", false);
    long expiresAt = requiredSafeInteger(payload, "exp", true);
    return new ParsedPayload(
        requiredText(payload, "iss"),
        requiredText(payload, "aud"),
        requiredText(payload, "sub"),
        requiredText(payload, "name"),
        requiredText(payload, "org"),
        ConnectorOrigin.parse(requiredText(payload, "origin")),
        requiredText(payload, "client_id"),
        requiredText(payload, "client_name"),
        List.copyOf(scopes),
        issuedAt,
        expiresAt);
  }

  private static long requiredSafeInteger(JsonNode value, String field, boolean positive) {
    JsonNode candidate = value.get(field);
    if (candidate == null || !candidate.isNumber()) throw invalidClaims();
    BigDecimal decimal = candidate.decimalValue().stripTrailingZeros();
    if (decimal.scale() > 0) throw invalidClaims();
    BigInteger integer;
    try {
      integer = decimal.toBigIntegerExact();
    } catch (ArithmeticException exception) {
      throw invalidClaims();
    }
    if (integer.signum() < 0
        || (positive && integer.signum() == 0)
        || integer.compareTo(MAX_SAFE_INTEGER) > 0) {
      throw invalidClaims();
    }
    return integer.longValueExact();
  }

  private static byte[] decodeCanonical(String segment) {
    byte[] decoded;
    try {
      decoded = Base64.getUrlDecoder().decode(segment);
    } catch (IllegalArgumentException exception) {
      throw error("MALFORMED_ACCESS_TOKEN", "The bearer token is malformed.");
    }
    if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(segment)) {
      throw error("INVALID_ACCESS_TOKEN", "The bearer token encoding is invalid.");
    }
    return decoded;
  }

  private static JsonNode parseJson(byte[] value) {
    try {
      JsonNode parsed = STRICT_MAPPER.readTree(value);
      if (parsed == null) throw error("MALFORMED_ACCESS_TOKEN", "The bearer token is malformed.");
      return parsed;
    } catch (IOException exception) {
      throw error("MALFORMED_ACCESS_TOKEN", "The bearer token is malformed.");
    }
  }

  private static void requireExactFields(JsonNode value, Set<String> expected) {
    if (!value.isObject() || value.size() != expected.size()) throw invalidClaims();
    for (String field : expected) if (!value.has(field)) throw invalidClaims();
  }

  private static String requiredText(JsonNode value, String field) {
    JsonNode candidate = value.get(field);
    if (candidate == null || !candidate.isTextual() || candidate.textValue().isEmpty()) {
      throw invalidClaims();
    }
    return candidate.textValue();
  }

  private CredentialFingerprint fingerprint(String token) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
      return new CredentialFingerprint("sha256:" + HexFormat.of().formatHex(digest));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("SHA-256 is unavailable.", exception);
    }
  }

  private byte[] sign(String input) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
      return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("HMAC-SHA256 is unavailable.", exception);
    }
  }

  private static String nonEmpty(String value) {
    Objects.requireNonNull(value);
    if (value.isEmpty()) throw new IllegalArgumentException("Development token configuration must not be empty.");
    return value;
  }

  private static AuthenticationException invalidClaims() {
    return error("INVALID_ACCESS_TOKEN", "The bearer token contains invalid claims.");
  }

  private static AuthenticationException error(String code, String message) {
    return new AuthenticationException(code, message);
  }

  private record ParsedPayload(
      String issuer,
      String audience,
      String subjectId,
      String displayName,
      String organizationId,
      ConnectorOrigin origin,
      String clientId,
      String clientName,
      List<String> scopes,
      long issuedAt,
      long expiresAt) {}
}
