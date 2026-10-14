package io.tracepermit.identity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.JsonSupport;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.function.Supplier;

/** Deterministic local-test issuer. This class must never be wired into a non-loopback runtime. */
public final class DevelopmentTestTokenIssuer {
  private final byte[] signingKey;
  private final Clock clock;
  private final Supplier<String> createId;
  private final int tokenLifetimeSeconds;

  public DevelopmentTestTokenIssuer(
      byte[] signingKey, Clock clock, Supplier<String> createId, int tokenLifetimeSeconds) {
    this.signingKey = Objects.requireNonNull(signingKey).clone();
    this.clock = Objects.requireNonNull(clock);
    this.createId = Objects.requireNonNull(createId);
    this.tokenLifetimeSeconds = tokenLifetimeSeconds;
    if (this.signingKey.length < 32) {
      throw new IllegalArgumentException("Development signing keys must contain at least 32 bytes.");
    }
    if (tokenLifetimeSeconds <= 0
        || tokenLifetimeSeconds > DevelopmentTokenVerifier.MAX_TOKEN_LIFETIME_SECONDS) {
      throw new IllegalArgumentException(
          "Development token lifetime must be between 1 and 600 seconds.");
    }
  }

  public String issueToken(DevelopmentProfile profile) {
    Objects.requireNonNull(profile);
    Instant now = clock.instant();
    long issuedAt = now.getEpochSecond();
    ObjectNode header = JsonSupport.MAPPER.createObjectNode();
    header.put("alg", "HS256");
    header.put("typ", "JWT");
    header.put("kid", DevelopmentTokenVerifier.DEFAULT_KEY_ID);
    ObjectNode payload = JsonSupport.MAPPER.createObjectNode();
    payload.put("iss", DevelopmentTokenVerifier.DEFAULT_ISSUER);
    payload.put("aud", DevelopmentTokenVerifier.DEFAULT_AUDIENCE);
    payload.put("sub", profile.subjectId());
    payload.put("name", profile.displayName());
    payload.put("org", DevelopmentProfile.ORGANIZATION_ID);
    payload.put("origin", profile.origin().value());
    payload.put("client_id", profile.clientId());
    payload.put("client_name", profile.clientName());
    ArrayNode scopes = payload.putArray("scope");
    profile.scopes().forEach(scopes::add);
    payload.put("iat", issuedAt);
    payload.put("exp", issuedAt + tokenLifetimeSeconds);
    payload.put("jti", createId.get());
    String signingInput = encode(header) + "." + encode(payload);
    return signingInput + "." + encode(sign(signingInput));
  }

  private static String encode(ObjectNode value) {
    try {
      return encode(JsonSupport.MAPPER.writeValueAsBytes(value));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Cannot encode a development token.", exception);
    }
  }

  private static String encode(byte[] value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
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
}
