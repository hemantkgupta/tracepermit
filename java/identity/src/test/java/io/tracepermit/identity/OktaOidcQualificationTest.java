package io.tracepermit.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class OktaOidcQualificationTest {
  private static final KeyPair KEY = OidcTestSupport.rsaKeyPair();
  private static final String ISSUER = OidcTestSupport.ISSUER + "/oauth2/synthetic";
  private static final String CLIENT = "chatgpt-connector";
  private final Clock clock = Clock.fixed(OidcTestSupport.NOW, ZoneOffset.UTC);

  private static OidcProviderConfiguration provider() {
    var base = OidcTestSupport.provider();
    return new OidcProviderConfiguration(URI.create(ISSUER), base.resourceAudience(),
        URI.create(ISSUER + "/v1/keys"), base.resourceMetadataUri(), List.of(URI.create(ISSUER)),
        base.resourceName(), base.clientNames(), "tracepermit_tenant", Map.of("pilot-synthetic", "tenant:acme"),
        base.supportedScopes(), base.origin(), base.maximumTokenLifetime(), base.jwksCacheLifetime(),
        base.forcedRefreshCooldown(), OidcTokenProfile.okta("groups", Map.of(
            "pilot-requesters", "REQUESTER", "pilot-reviewers", "REVIEWER", "pilot-viewers", "VIEWER")));
  }

  private OidcAccessTokenVerifier verifier() {
    return new OidcAccessTokenVerifier(new StaticOidcProviderRegistry(List.of(provider())),
        (p, kid, alg, now) -> {
          if (!OidcTestSupport.KEY_ID.equals(kid)) throw new AuthenticationException("INVALID_ACCESS_TOKEN", "Rejected key.");
          return (RSAPublicKey) KEY.getPublic();
        }, clock);
  }

  private static String token(Consumer<ObjectNode> mutation) {
    return token(KEY, h -> {}, mutation);
  }

  private static String token(KeyPair key, Consumer<ObjectNode> header, Consumer<ObjectNode> mutation) {
    return OidcTestSupport.token(key, h -> { h.put("typ", "JWT"); header.accept(h); }, c -> {
      c.remove(List.of("client_id", "scope", "org_id"));
      c.put("iss", ISSUER).put("cid", CLIENT).put("tracepermit_tenant", "pilot-synthetic");
      c.putArray("scp").add("aws.logs.discover").add("aws.logs.read");
      c.putArray("groups").add("pilot-requesters");
      mutation.accept(c);
    });
  }

  @ParameterizedTest @ValueSource(strings = {"REQUESTER", "REVIEWER", "VIEWER"})
  void mapsOnlyVerifiedExplicitGroupsAndNeverProjectsRawGroups(String role) {
    String group = "pilot-" + switch (role) {
      case "REVIEWER" -> "reviewers";
      case "VIEWER" -> "viewers";
      default -> "requesters";
    };
    String token = token(c -> {
      c.putArray("groups").add("unmapped-harmless-group").add(group);
      c.putArray("roles").add("ADMIN");
    });
    var verified = verifier().authenticate("Bearer " + token);
    assertThat(verified.mappedRole()).contains(role);
    assertThat(verified.authenticationProfile()).isEqualTo(OidcTokenProfile.OKTA);
    assertThat(verified.toString()).doesNotContain(token, group, "unmapped-harmless-group", "ADMIN");
  }

  @ParameterizedTest @ValueSource(strings = {"missing", "empty", "string", "null", "number", "duplicate",
      "ambiguous", "unknown", "control", "oversized", "many", "blank", "object"})
  void missingMalformedAmbiguousOrRemovedGroupFailsClosed(String change) {
    String token = token(c -> {
      switch (change) {
        case "missing" -> c.remove("groups");
        case "empty" -> c.putArray("groups");
        case "string" -> c.put("groups", "pilot-requesters");
        case "null" -> c.putNull("groups");
        case "number" -> c.putArray("groups").add(1);
        case "duplicate" -> c.putArray("groups").add("pilot-requesters").add("pilot-requesters");
        case "ambiguous" -> c.putArray("groups").add("pilot-requesters").add("pilot-reviewers");
        case "unknown" -> c.putArray("groups").add("arbitrary-admin-group");
        case "control" -> c.putArray("groups").add("bad\ngroup");
        case "oversized" -> c.putArray("groups").add("x".repeat(129));
        case "many" -> { var groups = c.putArray("groups"); for (int i = 0; i < 101; i++) groups.add("g" + i); }
        case "blank" -> c.putArray("groups").add(" ");
        case "object" -> c.putObject("groups").put("role", "REVIEWER");
        default -> throw new AssertionError();
      }
    });
    rejected(token);
  }

  @ParameterizedTest @ValueSource(strings = {"iss", "aud", "aud_array", "cid", "tenant", "missing_tenant", "scope_string",
      "duplicate_scope", "unknown_scope", "missing_scope", "mixed_client", "mixed_scope", "expired", "future",
      "long_life", "algorithm", "type", "key", "jku", "signature"})
  void identityClaimsAndProfileSubstitutionCannotAuthorize(String change) {
    String token = token("signature".equals(change) ? OidcTestSupport.rsaKeyPair() : KEY, h -> {
      switch (change) {
        case "algorithm" -> h.put("alg", "HS256");
        case "type" -> h.put("typ", "at+jwt");
        case "key" -> h.put("kid", "untrusted");
        case "jku" -> h.put("jku", "https://untrusted.example.invalid/keys");
        default -> { }
      }
    }, c -> {
      switch (change) {
        case "iss" -> c.put("iss", "https://other.example.invalid/oauth2/synthetic");
        case "aud" -> c.put("aud", "https://other.example.invalid");
        case "aud_array" -> c.putArray("aud").add(OidcTestSupport.AUDIENCE).add("https://other.example.invalid");
        case "cid" -> c.put("cid", "wrong");
        case "tenant" -> c.put("tracepermit_tenant", "other");
        case "missing_tenant" -> c.remove("tracepermit_tenant");
        case "scope_string" -> c.put("scp", "aws.logs.read");
        case "duplicate_scope" -> c.putArray("scp").add("aws.logs.read").add("aws.logs.read");
        case "unknown_scope" -> c.putArray("scp").add("aws.admin");
        case "missing_scope" -> c.remove("scp");
        case "mixed_client" -> c.put("client_id", CLIENT);
        case "mixed_scope" -> c.put("scope", "aws.logs.read");
        case "expired" -> c.put("exp", 1);
        case "future" -> c.put("iat", OidcTestSupport.NOW.plusSeconds(60).getEpochSecond());
        case "long_life" -> c.put("exp", OidcTestSupport.NOW.plusSeconds(901).getEpochSecond());
        default -> { }
      }
    });
    rejected(token);
  }

  @Test void groupProjectionRunsOnlyAfterSignatureAudienceAndTimeValidation() {
    String badSignature = token(OidcTestSupport.rsaKeyPair(), h -> {}, c -> c.remove("groups"));
    assertThatThrownBy(() -> verifier().authenticate("Bearer " + badSignature))
        .isInstanceOf(AuthenticationException.class).satisfies(e ->
            assertThat(((AuthenticationException) e).diagnosticCode()).isNotEqualTo("GROUP_MAPPING"));
    String wrongAudience = token(c -> { c.put("aud", "https://other.example.invalid"); c.remove("groups"); });
    assertThatThrownBy(() -> verifier().authenticate("Bearer " + wrongAudience))
        .isInstanceOf(AuthenticationException.class).satisfies(e ->
            assertThat(((AuthenticationException) e).diagnosticCode()).isEqualTo("ISSUER_OR_AUDIENCE"));
  }

  @Test void duplicateGroupClaimKeysAreRejectedBeforeProjection() {
    String[] parts = token(c -> {}).split("\\.");
    String header = new String(java.util.Base64.getUrlDecoder().decode(parts[0]), java.nio.charset.StandardCharsets.UTF_8);
    String claims = new String(java.util.Base64.getUrlDecoder().decode(parts[1]), java.nio.charset.StandardCharsets.UTF_8)
        .replace("\"groups\":", "\"groups\":[],\"groups\":");
    rejected(OidcTestSupport.rawToken(KEY, header, claims));
  }

  @Test void independentlySignedIdTokenRequiresMatchingTenantAndMappedGroup() {
    String bearer = token(c -> {});
    var connector = verifier().authenticate("Bearer " + bearer);
    var identity = new BrowserIdTokenVerifier(provider(), (p, kid, alg, now) -> (RSAPublicKey) KEY.getPublic(),
        clock, "pilot-synthetic");
    String id = token(c -> c.put("aud", CLIENT).put("nonce", "synthetic-nonce"));
    assertThat(identity.verify(id, CLIENT, "synthetic-nonce", bearer, connector)).isAfter(clock.instant());
    for (String changed : List.of("tenant", "group", "missing_group")) {
      String invalid = token(c -> {
        c.put("aud", CLIENT).put("nonce", "synthetic-nonce");
        if (changed.equals("tenant")) c.put("tracepermit_tenant", "wrong");
        else if (changed.equals("group")) c.putArray("groups").add("pilot-reviewers");
        else c.remove("groups");
      });
      assertThatThrownBy(() -> identity.verify(invalid, CLIENT, "synthetic-nonce", bearer, connector))
          .hasMessage("INVALID_BROWSER_ID_TOKEN").hasNoCause();
    }
  }

  @Test void rotationCooldownAndExpiredCacheNeverFallBackToStaleAuthority() {
    var time = new AtomicReference<>(OidcTestSupport.NOW);
    Clock advancing = new Clock() {
      @Override public ZoneId getZone() { return ZoneOffset.UTC; }
      @Override public Clock withZone(ZoneId zone) { return this; }
      @Override public Instant instant() { return time.get(); }
    };
    var next = OidcTestSupport.rsaKeyPair();
    var fetches = new AtomicInteger();
    var document = new AtomicReference<>(OidcTestSupport.publicJwks(KEY));
    try (var keys = new CachingOidcJwkResolver(uri -> {
      fetches.incrementAndGet();
      if (document.get() == null) throw new IllegalStateException("SYNTHETIC_UNAVAILABLE");
      return document.get();
    })) {
      var access = new OidcAccessTokenVerifier(new StaticOidcProviderRegistry(List.of(provider())), keys, advancing);
      String original = token(c -> {});
      access.authenticate("Bearer " + original);
      document.set(OidcTestSupport.publicJwks(KEY, next));
      String rotated = token(next, h -> h.put("kid", "oidc-key-002"), c -> {});
      assertThatThrownBy(() -> access.authenticate("Bearer " + rotated)).isInstanceOf(AuthenticationException.class);
      assertThat(fetches).hasValue(1);
      time.set(OidcTestSupport.NOW.plusSeconds(31));
      assertThat(access.authenticate("Bearer " + rotated).mappedRole()).contains("REQUESTER");
      assertThat(fetches).hasValue(2);
      time.set(OidcTestSupport.NOW.plusSeconds(120)); document.set(null);
      assertThatThrownBy(() -> access.authenticate("Bearer " + original)).isInstanceOf(AuthenticationException.class)
          .satisfies(e -> assertThat(((AuthenticationException) e).code()).isEqualTo("OIDC_KEY_SET_UNAVAILABLE"));
      assertThat(fetches).hasValue(3);
    }
  }

  private void rejected(String token) {
    assertThatThrownBy(() -> verifier().authenticate("Bearer " + token))
        .isInstanceOf(AuthenticationException.class).hasNoCause()
        .satisfies(e -> assertThat(e.getMessage().contains(token)).isFalse())
        .hasMessageNotContaining("pilot-requesters");
  }
}
