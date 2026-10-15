package io.tracepermit.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class BrowserIdTokenVerifierTest {
  private static final KeyPair KEY = OidcTestSupport.rsaKeyPair();
  private final Clock clock = Clock.fixed(OidcTestSupport.NOW, ZoneOffset.UTC);
  private final OidcVerificationKeyResolver resolver = (p, kid, algorithm, now) -> (RSAPublicKey) KEY.getPublic();
  private final OidcAccessTokenVerifier access = new OidcAccessTokenVerifier(
      new StaticOidcProviderRegistry(java.util.List.of(OidcTestSupport.provider())), resolver, clock);
  private final BrowserIdTokenVerifier verifier = new BrowserIdTokenVerifier(OidcTestSupport.provider(), resolver, clock);

  @Test void verifiesSignedIdTokenAndOptionalAccessHashWithoutTreatingItAsApiToken() throws Exception {
    String bearer = OidcTestSupport.token(KEY);
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(bearer.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    String hash = Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOf(digest, 16));
    String id = id(header -> header.remove("typ"), claims -> claims.put("azp", "chatgpt-connector").put("at_hash", hash));
    assertThat(verifier.verify(id, "chatgpt-connector", "nonce", bearer, access.authenticate("Bearer " + bearer)))
        .isEqualTo(OidcTestSupport.NOW.plusSeconds(270));
    assertThatThrownBy(() -> access.authenticate("Bearer " + id)).isInstanceOf(AuthenticationException.class);
  }

  @ParameterizedTest @ValueSource(strings = {"alg", "kid", "typ", "jku", "crit"})
  void rejectsUnqualifiedHeaders(String name) {
    reject(id(header -> header.put(name, "kid".equals(name) ? "invalid key id" : "untrusted"), claims -> {}));
  }

  @ParameterizedTest @ValueSource(strings = {"", "not-a-jwt", "a.b.c", "a.b", "null", "[]", "oversize"})
  void rejectsMalformedTokens(String value) { reject("oversize".equals(value) ? "x".repeat(16_385) : value); }

  @Test void rejectsBadSignatureTypesMissingAndNonCanonicalClaims() {
    String other = OidcTestSupport.token(OidcTestSupport.rsaKeyPair(), h -> h.put("typ", "JWT"), c -> {});
    reject(other);
    reject(id(h -> {}, c -> c.remove("nonce")));
    reject(id(h -> {}, c -> c.putArray("aud").add("chatgpt-connector")));
    reject(id(h -> {}, c -> c.put("iat", "123")));
    reject(id(h -> {}, c -> c.put("exp", -1)));
    reject(id(h -> {}, c -> c.put("exp", Long.MAX_VALUE)));
    reject(id(h -> {}, c -> c.put("exp", OidcTestSupport.NOW.plusSeconds(901).getEpochSecond())));
    reject(id(h -> {}, c -> c.put("sub", "")));
    reject(null);
    String token = id(h -> {}, c -> {});
    String[] parts = token.split("\\.");
    reject(parts[0] + "=." + parts[1] + "." + parts[2]);
    reject(OidcTestSupport.rawToken(KEY, "{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"oidc-key-001\"}", "{\"sub\":\"one\",\"sub\":\"two\"}"));
  }

  private static String id(java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode> header,
      java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode> claims) {
    return OidcTestSupport.token(KEY, h -> { h.put("typ", "JWT"); header.accept(h); }, c -> {
      c.put("aud", "chatgpt-connector").put("nonce", "nonce"); claims.accept(c);
    });
  }
  private void reject(String token) {
    String bearer = OidcTestSupport.token(KEY);
    assertThatThrownBy(() -> verifier.verify(token, "chatgpt-connector", "nonce", bearer,
        access.authenticate("Bearer " + bearer))).isInstanceOf(SecurityException.class)
        .hasMessage("INVALID_BROWSER_ID_TOKEN").hasNoCause();
  }
}
