package io.tracepermit.identity;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
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
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.regex.Pattern;

public final class OidcAccessTokenVerifier implements ConnectorAuthenticator {
  public static final String AUTHENTICATION_PROFILE = "oauth-access-token-jwt-rs256-v1";
  static final int MAXIMUM_TOKEN_LENGTH = 16_384;
  private static final int FUTURE_CLOCK_SKEW_SECONDS = 30;
  private static final BigInteger MAX_SAFE_INTEGER = BigInteger.valueOf(9_007_199_254_740_991L);
  private static final Pattern BEARER =
      Pattern.compile("^Bearer ([A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+)$");
  private static final Pattern SAFE_IDENTIFIER =
      Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:@/+\\-=]{0,255}$");
  private static final Pattern SAFE_SUBJECT_IDENTIFIER =
      Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:@/+\\-=|]{0,255}$");
  private static final ObjectMapper STRICT_MAPPER =
      new ObjectMapper(
              JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private final OidcProviderRegistry providers;
  private final OidcVerificationKeyResolver keys;
  private final Clock clock;

  public OidcAccessTokenVerifier(
      OidcProviderRegistry providers, OidcVerificationKeyResolver keys, Clock clock) {
    this.providers = Objects.requireNonNull(providers);
    this.keys = Objects.requireNonNull(keys);
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public AuthenticatedConnector authenticate(String authorizationHeader) {
    var match = BEARER.matcher(authorizationHeader == null ? "" : authorizationHeader);
    if (!match.matches()) {
      throw error("MISSING_BEARER_TOKEN", "A valid Bearer authorization header is required.");
    }
    String token = match.group(1);
    if (token.length() > MAXIMUM_TOKEN_LENGTH) throw malformed();
    String[] segments = token.split("\\.", -1);
    if (segments.length != 3) throw malformed();

    JsonNode header = parseSegment(segments[0]);
    JsonNode claims = parseSegment(segments[1]);
    decodeCanonical(segments[2]);
    if (!header.isObject() || !claims.isObject()) throw malformed();

    String algorithm = requiredText(header, "alg", 16);
    String type = requiredText(header, "typ", 32);
    String keyId = requiredIdentifier(header, "kid");
    if (header.size() != 3 || !"RS256".equals(algorithm) || !"at+jwt".equals(type)) {
      throw invalidClaims("HEADER_PROFILE");
    }

    String issuer = requiredText(claims, "iss", 512);
    OidcProviderConfiguration provider;
    try {
      provider = providers.resolve(issuer).orElseThrow(OidcAccessTokenVerifier::invalidClaims);
    } catch (AuthenticationException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw unavailableConfiguration(exception);
    }

    Instant verifiedAt = clock.instant();
    var verificationKey = keys.resolve(provider, keyId, algorithm, verifiedAt);
    verifySignature(token, verificationKey);

    ParsedClaims parsed = parseClaims(claims, provider);
    validateClaims(parsed, provider, verifiedAt);
    return new AuthenticatedConnector(
        provider.issuer() + "#" + parsed.subject(),
        parsed.displayName(),
        parsed.tenantId(),
        provider.origin(),
        parsed.clientId(),
        parsed.clientName(),
        issuer,
        verifiedAt,
        AUTHENTICATION_PROFILE,
        keyId,
        fingerprint(token),
        parsed.scopes(),
        Instant.ofEpochSecond(parsed.expiresAt()),
        senderConstraint(claims));
  }

  private static Optional<TokenSenderConstraint> senderConstraint(JsonNode claims) {
    JsonNode confirmation = claims.get("cnf");
    if (confirmation == null) return Optional.empty();
    if (!confirmation.isObject()
        || confirmation.size() != 1
        || !confirmation.has("x5t#S256")) {
      throw invalidClaims();
    }
    JsonNode thumbprint = confirmation.get("x5t#S256");
    if (!thumbprint.isTextual() || thumbprint.textValue().isEmpty()) throw invalidClaims();
    byte[] decoded;
    try {
      decoded = Base64.getUrlDecoder().decode(thumbprint.textValue());
    } catch (IllegalArgumentException exception) {
      throw invalidClaims();
    }
    if (decoded.length != 32
        || !Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(decoded)
            .equals(thumbprint.textValue())) {
      throw invalidClaims();
    }
    return Optional.of(
        new TokenSenderConstraint(
            TokenSenderConstraint.MTLS_X5T_S256_V1,
            new CredentialFingerprint("sha256:" + HexFormat.of().formatHex(decoded))));
  }

  private static ParsedClaims parseClaims(
      JsonNode claims, OidcProviderConfiguration provider) {
    String issuer = requiredText(claims, "iss", 512);
    Set<String> audiences = parseAudiences(claims.get("aud"));
    String subject = requiredSubjectIdentifier(claims);
    String clientId = requiredIdentifier(claims, "client_id");
    String clientName = provider.clientName(clientId);
    if (clientName == null) throw invalidClaims("CLIENT_MAPPING");
    String externalTenant = requiredText(claims, provider.tenantClaim(), 256);
    String tenantId = provider.tenantId(externalTenant);
    if (tenantId == null) throw invalidClaims("TENANT_MAPPING");
    String tokenId = requiredIdentifier(claims, "jti");
    String displayName = optionalDisplayName(claims, subject);
    List<ConnectorScope> scopes = parseScopes(claims.path("scope"), provider.supportedScopes());
    return new ParsedClaims(
        issuer,
        audiences,
        subject,
        displayName,
        clientId,
        clientName,
        tenantId,
        tokenId,
        scopes,
        requiredSafeInteger(claims, "iat"),
        optionalSafeInteger(claims, "nbf"),
        requiredSafeInteger(claims, "exp"));
  }

  private static void validateClaims(
      ParsedClaims claims, OidcProviderConfiguration provider, Instant verifiedAt) {
    if (!provider.issuer().toString().equals(claims.issuer())
        || !claims.audiences().contains(provider.resourceAudience())) {
      throw invalidClaims("ISSUER_OR_AUDIENCE");
    }
    long current = verifiedAt.getEpochSecond();
    if (claims.issuedAt() > current + FUTURE_CLOCK_SKEW_SECONDS) {
      throw invalidClaims("ISSUED_AT");
    }
    if (claims.notBefore().isPresent()
        && (claims.notBefore().getAsLong() > current + FUTURE_CLOCK_SKEW_SECONDS
            || claims.notBefore().getAsLong()
                < claims.issuedAt() - FUTURE_CLOCK_SKEW_SECONDS)) {
      throw invalidClaims("NOT_BEFORE");
    }
    if (claims.expiresAt() <= current) {
      throw error("EXPIRED_ACCESS_TOKEN", "The bearer token has expired.");
    }
    long maximumLifetime = provider.maximumTokenLifetime().toSeconds();
    if (claims.expiresAt() <= claims.issuedAt()
        || claims.expiresAt() - claims.issuedAt() > maximumLifetime) {
      throw invalidClaims("TOKEN_LIFETIME");
    }
  }

  private static Set<String> parseAudiences(JsonNode value) {
    if (value == null) throw invalidClaims("AUDIENCE_SHAPE");
    if (value.isTextual()) return Set.of(boundedText(value, 512));
    if (!value.isArray() || value.isEmpty() || value.size() > 4) {
      throw invalidClaims("AUDIENCE_SHAPE");
    }
    Set<String> audiences = new HashSet<>();
    for (JsonNode item : value) {
      if (!audiences.add(boundedText(item, 512))) throw invalidClaims("AUDIENCE_SHAPE");
    }
    return Set.copyOf(audiences);
  }

  private static List<ConnectorScope> parseScopes(JsonNode value, Set<ConnectorScope> allowed) {
    if (!value.isTextual() || value.textValue().isEmpty() || value.textValue().startsWith(" ") || value.textValue().endsWith(" ") || value.textValue().contains("  ")) {
      throw invalidClaims("SCOPE_SHAPE");
    }
    List<ConnectorScope> result = new ArrayList<>();
    Set<ConnectorScope> unique = new HashSet<>();
    for (String item : value.textValue().split(" ")) {
      if (!SAFE_IDENTIFIER.matcher(item).matches()) throw invalidClaims("SCOPE_SHAPE");
      ConnectorScope scope;
      try {
        scope = ConnectorScope.of(item);
      } catch (RuntimeException exception) {
        throw invalidClaims("SCOPE_SHAPE");
      }
      if (!allowed.contains(scope) || !unique.add(scope)) {
        throw invalidClaims("SCOPE_ALLOWLIST");
      }
      result.add(scope);
    }
    return List.copyOf(result);
  }

  private static String optionalDisplayName(JsonNode claims, String fallback) {
    JsonNode value = claims.get("name");
    if (value == null) return fallback;
    if (!value.isTextual()
        || value.textValue().isEmpty()
        || value.textValue().length() > 128
        || value.textValue().chars().anyMatch(Character::isISOControl)) {
      throw invalidClaims();
    }
    return value.textValue();
  }

  private static void verifySignature(String token, java.security.interfaces.RSAPublicKey key) {
    try {
      SignedJWT parsed = SignedJWT.parse(token);
      if (!JWSAlgorithm.RS256.equals(parsed.getHeader().getAlgorithm())
          || !new JOSEObjectType("at+jwt").equals(parsed.getHeader().getType())
          || !parsed.verify(new RSASSAVerifier(key))) {
        throw error("INVALID_ACCESS_TOKEN", "The bearer token signature is invalid.");
      }
    } catch (java.text.ParseException exception) {
      throw malformed();
    } catch (JOSEException exception) {
      throw error("INVALID_ACCESS_TOKEN", "The bearer token signature is invalid.");
    }
  }

  private static JsonNode parseSegment(String segment) {
    try {
      JsonNode parsed = STRICT_MAPPER.readTree(decodeCanonical(segment));
      if (parsed == null) throw malformed();
      return parsed;
    } catch (IOException exception) {
      throw malformed();
    }
  }

  private static byte[] decodeCanonical(String segment) {
    if (segment.isEmpty()) throw malformed();
    byte[] decoded;
    try {
      decoded = Base64.getUrlDecoder().decode(segment);
    } catch (IllegalArgumentException exception) {
      throw malformed();
    }
    if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(segment)) {
      throw error("INVALID_ACCESS_TOKEN", "The bearer token encoding is invalid.");
    }
    return decoded;
  }

  private static String requiredIdentifier(JsonNode value, String field) {
    String result = requiredText(value, field, 256);
    if (!SAFE_IDENTIFIER.matcher(result).matches()) throw invalidClaims("IDENTIFIER_SHAPE");
    return result;
  }

  private static String requiredSubjectIdentifier(JsonNode claims) {
    String subject = requiredText(claims, "sub", 256);
    if (!SAFE_SUBJECT_IDENTIFIER.matcher(subject).matches()) {
      throw invalidClaims("SUBJECT_SHAPE");
    }
    return subject;
  }

  private static String requiredText(JsonNode value, String field, int maximumLength) {
    JsonNode candidate = value.get(field);
    if (candidate == null) throw invalidClaims("REQUIRED_CLAIM");
    return boundedText(candidate, maximumLength);
  }

  private static String boundedText(JsonNode candidate, int maximumLength) {
    if (!candidate.isTextual()
        || candidate.textValue().isEmpty()
        || candidate.textValue().length() > maximumLength
        || candidate.textValue().chars().anyMatch(Character::isISOControl)) {
      throw invalidClaims("CLAIM_SHAPE");
    }
    return candidate.textValue();
  }

  private static long requiredSafeInteger(JsonNode value, String field) {
    JsonNode candidate = value.get(field);
    if (candidate == null || !candidate.isNumber()) throw invalidClaims("NUMERIC_DATE");
    BigDecimal decimal = candidate.decimalValue().stripTrailingZeros();
    if (decimal.scale() > 0) throw invalidClaims("NUMERIC_DATE");
    try {
      BigInteger integer = decimal.toBigIntegerExact();
      if (integer.signum() < 0 || integer.compareTo(MAX_SAFE_INTEGER) > 0) {
        throw invalidClaims("NUMERIC_DATE");
      }
      return integer.longValueExact();
    } catch (ArithmeticException exception) {
      throw invalidClaims("NUMERIC_DATE");
    }
  }

  private static OptionalLong optionalSafeInteger(JsonNode value, String field) {
    return value.has(field)
        ? OptionalLong.of(requiredSafeInteger(value, field))
        : OptionalLong.empty();
  }

  private static CredentialFingerprint fingerprint(String token) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
      return new CredentialFingerprint("sha256:" + HexFormat.of().formatHex(digest));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("SHA-256 is unavailable.", exception);
    }
  }

  private static AuthenticationException malformed() {
    return error("MALFORMED_ACCESS_TOKEN", "The bearer token is malformed.");
  }

  private static AuthenticationException invalidClaims() {
    return invalidClaims("UNSPECIFIED_CLAIMS");
  }

  private static AuthenticationException invalidClaims(String diagnosticCode) {
    return error(
        "INVALID_ACCESS_TOKEN", "The bearer token contains invalid claims.", diagnosticCode);
  }

  private static AuthenticationException unavailableConfiguration(Throwable cause) {
    Objects.requireNonNull(cause);
    return error(
        "OIDC_CONFIGURATION_UNAVAILABLE",
        "The configured OIDC provider trust information is unavailable.");
  }

  private static AuthenticationException error(String code, String message) {
    return new AuthenticationException(code, message);
  }

  private static AuthenticationException error(
      String code, String message, String diagnosticCode) {
    return new AuthenticationException(code, message, diagnosticCode);
  }

  private record ParsedClaims(
      String issuer,
      Set<String> audiences,
      String subject,
      String displayName,
      String clientId,
      String clientName,
      String tenantId,
      String tokenId,
      List<ConnectorScope> scopes,
      long issuedAt,
      OptionalLong notBefore,
      long expiresAt) {}
}
