package io.tracepermit.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.net.URI;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class Auth0OidcQualificationTest {
  private static final KeyPair KEY = OidcTestSupport.rsaKeyPair();
  private static final String ISSUER = "https://synthetic.au.auth0.com/";
  private static final String TENANT = "https://tracepermit.org/claims/tenant_id";
  private static final String ROLE = "https://tracepermit.org/claims/roles";
  private static final Clock CLOCK = Clock.fixed(OidcTestSupport.NOW, ZoneOffset.UTC);

  private OidcProviderConfiguration provider(String role) {
    var b = OidcTestSupport.provider();
    return new OidcProviderConfiguration(URI.create(ISSUER), b.resourceAudience(),
        URI.create(ISSUER + ".well-known/jwks.json"), b.resourceMetadataUri(), List.of(URI.create(ISSUER)),
        b.resourceName(), b.clientNames(), TENANT, Map.of("pilot", "tenant:pilot"),
        b.supportedScopes(), b.origin(), Duration.ofSeconds(300), b.jwksCacheLifetime(),
        b.forcedRefreshCooldown(), OidcTokenProfile.auth0(ROLE, role));
  }

  private AuthenticatedConnector verify(String role, String token) {
    return new OidcAccessTokenVerifier(new StaticOidcProviderRegistry(List.of(provider(role))),
        (p, kid, alg, at) -> {
          if (!OidcTestSupport.KEY_ID.equals(kid)) throw new IllegalArgumentException();
          return (RSAPublicKey) KEY.getPublic();
        }, CLOCK).authenticate("Bearer " + token);
  }

  private String token(String role, java.util.function.Consumer<ObjectNode> mutation) {
    return OidcTestSupport.token(KEY, h -> {}, c -> {
      c.remove("org_id");
      c.put("iss", ISSUER).put(TENANT, "pilot").put("sub",
          role.equals("REVIEWER") ? "auth0|human" : "chatgpt-connector@clients");
      c.putArray(ROLE).add(role);
      mutation.accept(c);
    });
  }

  @ParameterizedTest @ValueSource(strings = {"REQUESTER", "REVIEWER"})
  void verifiesExplicitProfileAndExactlyOneSignedRole(String role) {
    var result = verify(role, token(role, c -> {}));
    assertThat(result.authenticationProfile()).isEqualTo(OidcTokenProfile.AUTH0);
    assertThat(result.mappedRole()).contains(role);
    assertThat(result.organizationId()).isEqualTo("tenant:pilot");
  }

  @Test void permitsOnlyApiPlusIssuerUserinfoAudience() {
    assertThat(verify("REQUESTER", token("REQUESTER", c -> c.putArray("aud")
        .add(OidcTestSupport.AUDIENCE).add(ISSUER + "userinfo"))).mappedRole()).contains("REQUESTER");
  }

  @ParameterizedTest @ValueSource(strings = {"missing-role", "both-roles", "wrong-role", "role-string",
      "role-number", "role-null", "tenant", "tenant-number", "client", "jti", "expired", "lifetime",
      "cid", "scp", "azp", "permissions", "cnf", "audience", "foreign-audience", "scope-array",
      "scope-duplicate", "scope-unknown", "service-as-human", "human-as-machine"})
  void rejectsSubstitutionAndMalformedClaims(String mutation) {
    String role = mutation.equals("service-as-human") ? "REVIEWER" : "REQUESTER";
    String token = token(role, c -> {
      switch (mutation) {
        case "missing-role" -> c.remove(ROLE);
        case "both-roles" -> c.putArray(ROLE).add("REQUESTER").add("REVIEWER");
        case "wrong-role" -> c.putArray(ROLE).add("VIEWER");
        case "role-string" -> c.put(ROLE, role);
        case "role-number" -> c.putArray(ROLE).add(1);
        case "role-null" -> c.putNull(ROLE);
        case "tenant" -> c.put(TENANT, "other");
        case "tenant-number" -> c.put(TENANT, 123);
        case "client" -> c.put("client_id", "foreign");
        case "jti" -> c.remove("jti");
        case "expired" -> c.put("exp", 1);
        case "lifetime" -> c.put("exp", OidcTestSupport.NOW.plusSeconds(301).getEpochSecond());
        case "cid", "azp" -> c.put(mutation, "foreign");
        case "scp", "permissions" -> c.putArray(mutation).add("aws.logs.read");
        case "cnf" -> c.putObject("cnf");
        case "audience" -> c.put("aud", ISSUER + "api/v2/");
        case "foreign-audience" -> c.putArray("aud").add(OidcTestSupport.AUDIENCE).add("https://foreign.invalid");
        case "scope-array" -> c.putArray("scope").add("aws.logs.read");
        case "scope-duplicate" -> c.put("scope", "aws.logs.read aws.logs.read");
        case "scope-unknown" -> c.put("scope", "operations.decide");
        case "service-as-human" -> c.put("sub", "chatgpt-connector@clients");
        case "human-as-machine" -> c.put("sub", "auth0|human");
        default -> throw new AssertionError();
      }
    });
    assertThatThrownBy(() -> verify(role, token)).isInstanceOf(AuthenticationException.class).hasNoCause();
  }

  @Test void rejectsDefaultDialectAndTamperingBeforeRoleProjection() {
    String valid = token("REQUESTER", c -> {});
    String[] parts = valid.split("\\.");
    String claims = new String(Base64.getUrlDecoder().decode(parts[1]), java.nio.charset.StandardCharsets.UTF_8);
    String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"" + OidcTestSupport.KEY_ID + "\"}";
    assertThatThrownBy(() -> verify("REQUESTER", OidcTestSupport.rawToken(KEY, header, claims)))
        .isInstanceOf(AuthenticationException.class);
    assertThatThrownBy(() -> verify("REQUESTER", OidcTestSupport.rawToken(OidcTestSupport.rsaKeyPair(),
        new String(Base64.getUrlDecoder().decode(parts[0]), java.nio.charset.StandardCharsets.UTF_8), claims)))
        .isInstanceOf(AuthenticationException.class);
    assertThatThrownBy(() -> verify("REQUESTER", OidcTestSupport.rawToken(KEY,
        new String(Base64.getUrlDecoder().decode(parts[0]), java.nio.charset.StandardCharsets.UTF_8),
        claims.replace("\"jti\":", "\"jti\":\"duplicate\",\"jti\":"))))
        .isInstanceOf(AuthenticationException.class);
  }

  @Test void accessProfileCannotBeReusedAsAnIdTokenProfile() {
    assertThatThrownBy(() -> new BrowserIdTokenVerifier(provider("REVIEWER"),
        (p, kid, alg, at) -> (RSAPublicKey) KEY.getPublic(), CLOCK))
        .hasMessage("AUTH0_PROFILE_IS_ACCESS_TOKEN_ONLY");
  }

  @Test void configurationCannotReuseUnnamespacedOrReservedClaims() {
    for (String claim : List.of("roles", "scope", "http://foreign.invalid/roles", ROLE + "?x=1")) {
      assertThatThrownBy(() -> OidcTokenProfile.auth0(claim, "REVIEWER"))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> OidcTokenProfile.auth0(ROLE, "ADMIN"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> OidcTokenProfile.auth0(ROLE, "REVIEWER").validateTenantClaim(ROLE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(OidcTokenProfile.standard().mappedRole(ReviewerDummy.claims())).isEmpty();
  }

  private static final class ReviewerDummy {
    static ObjectNode claims() { return io.tracepermit.contracts.JsonSupport.MAPPER.createObjectNode(); }
  }
}
