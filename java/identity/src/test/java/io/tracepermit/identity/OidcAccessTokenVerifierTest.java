package io.tracepermit.identity;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class OidcAccessTokenVerifierTest {
  private final java.security.KeyPair keyPair = OidcTestSupport.rsaKeyPair();

  @Test
  void verifiesProductionProfileAndReturnsOnlyBoundedAttribution() {
    String token = OidcTestSupport.token(keyPair);
    AuthenticatedConnector connector = verifier().authenticate("Bearer " + token);

    assertThat(connector.subjectId())
        .isEqualTo(OidcTestSupport.ISSUER + "#user:alice@example.invalid");
    assertThat(connector.displayName()).isEqualTo("Alice Example");
    assertThat(connector.organizationId()).isEqualTo("tenant:acme");
    assertThat(connector.clientId()).isEqualTo("chatgpt-connector");
    assertThat(connector.clientName()).isEqualTo("ChatGPT Web");
    assertThat(connector.authenticationProfile())
        .isEqualTo(OidcAccessTokenVerifier.AUTHENTICATION_PROFILE);
    assertThat(connector.verificationKeyId()).isEqualTo(OidcTestSupport.KEY_ID);
    assertThat(connector.credentialFingerprint().value()).matches("^sha256:[a-f0-9]{64}$");
    assertThat(connector.scopes())
        .extracting(ConnectorScope::value)
        .containsExactly("aws.logs.discover", "aws.logs.read");
    assertThat(connector.toString()).doesNotContain(token);
    assertThat(AuthenticatedConnector.class.getRecordComponents())
        .extracting(component -> component.getName())
        .doesNotContain("accessToken", "authorization", "bearerToken", "tokenId");
  }

  @Test
  void ignoresInjectedTrustedAliasesAndUsesOnlyConfiguredMappings() {
    String token =
        OidcTestSupport.token(
            keyPair,
            header -> {},
            claims -> {
              claims.put("organizationId", "tenant:attacker");
              claims.put("client_name", "Administrator");
              claims.put("origin", "cli");
              claims.put("verificationKey", "attacker-key");
            });

    AuthenticatedConnector connector = verifier().authenticate("Bearer " + token);
    assertThat(connector.organizationId()).isEqualTo("tenant:acme");
    assertThat(connector.clientName()).isEqualTo("ChatGPT Web");
    assertThat(connector.origin()).isEqualTo(ConnectorOrigin.CHATGPT_WEB);
    assertThat(connector.verificationKeyId()).isEqualTo(OidcTestSupport.KEY_ID);
  }

  @Test
  void projectsOnlyTheVerifiedCertificateThumbprintFromAnRfc8705Confirmation() {
    byte[] certificateDigest = new byte[32];
    java.util.Arrays.fill(certificateDigest, (byte) 0x5a);
    String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(certificateDigest);
    String token =
        OidcTestSupport.token(
            keyPair,
            header -> {},
            claims -> claims.putObject("cnf").put("x5t#S256", encoded));

    AuthenticatedConnector connector = verifier().authenticate("Bearer " + token);

    assertThat(connector.senderConstraint()).isPresent();
    assertThat(connector.senderConstraint().orElseThrow().profile())
        .isEqualTo(TokenSenderConstraint.MTLS_X5T_S256_V1);
    assertThat(connector.senderConstraint().orElseThrow().certificateFingerprint().value())
        .isEqualTo("sha256:" + "5a".repeat(32));
    assertThat(connector.toString()).doesNotContain(encoded, token);
  }

  @Test
  void malformedOrAmbiguousCertificateConfirmationsFailClosed() {
    assertInvalidClaims(claims -> claims.put("cnf", "attacker-key"));
    assertInvalidClaims(claims -> claims.putObject("cnf"));
    assertInvalidClaims(
        claims ->
            claims
                .putObject("cnf")
                .put("x5t#S256", Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[31])));
    assertInvalidClaims(
        claims -> claims.putObject("cnf").put("x5t#S256", "=".repeat(44)));
    assertInvalidClaims(
        claims -> {
          ObjectNode confirmation = claims.putObject("cnf");
          confirmation.put(
              "x5t#S256",
              Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]));
          confirmation.put("jkt", "attacker-thumbprint");
        });
  }

  @Test
  void missingMalformedDuplicateAndOversizedTokensFailClosed() {
    assertCode(() -> verifier().authenticate(null), "MISSING_BEARER_TOKEN");
    assertCode(() -> verifier().authenticate("Basic credential"), "MISSING_BEARER_TOKEN");
    assertCode(() -> verifier().authenticate("Bearer a.b"), "MISSING_BEARER_TOKEN");
    assertCode(
        () ->
            verifier()
                .authenticate(
                    "Bearer "
                        + "a".repeat(OidcAccessTokenVerifier.MAXIMUM_TOKEN_LENGTH)
                        + ".a.a"),
        "MALFORMED_ACCESS_TOKEN");
    String duplicate =
        OidcTestSupport.rawToken(
            keyPair,
            "{\"alg\":\"RS256\",\"alg\":\"RS256\",\"typ\":\"at+jwt\",\"kid\":\"oidc-key-001\"}",
            "{}");
    assertCode(
        () -> verifier().authenticate("Bearer " + duplicate), "MALFORMED_ACCESS_TOKEN");
  }

  @Test
  void signatureHeaderIssuerAudienceAndKeyMustMatchTrustedConfiguration() {
    var wrongKey = OidcTestSupport.rsaKeyPair();
    assertCode(
        () -> verifier().authenticate("Bearer " + OidcTestSupport.token(wrongKey)),
        "INVALID_ACCESS_TOKEN");
    assertInvalidHeader(header -> header.put("alg", "none"));
    assertInvalidHeader(header -> header.put("typ", "JWT"));
    assertInvalidHeader(header -> header.put("kid", "unknown-key"));
    assertInvalidHeader(header -> header.put("jku", "https://attacker.example.invalid/jwks"));
    assertInvalidClaims(claims -> claims.put("iss", "https://attacker.example.invalid"));
    assertInvalidClaims(claims -> claims.put("aud", "https://another.example.invalid"));
  }

  @Test
  void acceptsBoundedMultiAudienceAndOptionalNotBeforeFromRfc9068Issuers() {
    String multiAudience =
        OidcTestSupport.token(
            keyPair,
            header -> {},
            claims -> {
              claims
                  .putArray("aud")
                  .add(OidcTestSupport.AUDIENCE)
                  .add(OidcTestSupport.ISSUER + "/userinfo");
              claims.remove("nbf");
            });

    assertThat(verifier().authenticate("Bearer " + multiAudience).subjectId())
        .isEqualTo(OidcTestSupport.ISSUER + "#user:alice@example.invalid");
  }

  @Test
  void acceptsBoundedAuth0ProviderQualifiedSubjectIdentifiers() {
    String token =
        OidcTestSupport.token(
            keyPair,
            header -> {},
            claims -> claims.put("sub", "google-oauth2|113355779900"));

    assertThat(verifier().authenticate("Bearer " + token).subjectId())
        .isEqualTo(OidcTestSupport.ISSUER + "#google-oauth2|113355779900");
  }

  @Test
  void clientTenantScopeAndClaimTypesFailClosed() {
    assertInvalidClaims(claims -> claims.put("client_id", "unknown-client"));
    assertInvalidClaims(claims -> claims.put("org_id", "unknown-tenant"));
    assertInvalidClaims(claims -> claims.put("scope", "aws.logs.read aws.admin"));
    assertInvalidClaims(claims -> claims.put("scope", "aws.logs.read aws.logs.read"));
    assertInvalidClaims(
        claims ->
            claims
                .putArray("aud")
                .add("https://another.example.invalid")
                .add(OidcTestSupport.ISSUER + "/userinfo"));
    assertInvalidClaims(
        claims ->
            claims
                .putArray("aud")
                .add(OidcTestSupport.AUDIENCE)
                .add(OidcTestSupport.AUDIENCE));
    assertInvalidClaims(claims -> claims.putArray("aud"));
    assertInvalidClaims(
        claims -> {
          var audiences = claims.putArray("aud");
          audiences.add(OidcTestSupport.AUDIENCE);
          audiences.add("https://audience-2.example.invalid");
          audiences.add("https://audience-3.example.invalid");
          audiences.add("https://audience-4.example.invalid");
          audiences.add("https://audience-5.example.invalid");
        });
    assertInvalidClaims(
        claims ->
            claims
                .putArray("aud")
                .add(OidcTestSupport.AUDIENCE)
                .add("bad\naudience"));
    assertInvalidClaims(claims -> claims.put("exp", "2030-01-01T00:05:00Z"));
    assertInvalidClaims(claims -> claims.remove("jti"));
    assertInvalidClaims(claims -> claims.put("name", "bad\nname"));
    assertInvalidClaims(claims -> claims.put("scope", " aws.logs.read"));
    assertInvalidClaims(claims -> claims.put("aud", 42));
  }

  @Test
  void exposesOnlyBoundedReasonCodesForOperatorDiagnostics() {
    String token =
        OidcTestSupport.token(
            keyPair, header -> {}, claims -> claims.put("client_id", "unknown-client"));

    assertThatThrownBy(() -> verifier().authenticate("Bearer " + token))
        .isInstanceOf(AuthenticationException.class)
        .satisfies(
            failure ->
                assertThat(((AuthenticationException) failure).diagnosticCode())
                    .isEqualTo("CLIENT_MAPPING"))
        .hasMessageNotContaining(token)
        .hasMessageNotContaining("unknown-client");
  }

  @Test
  void malformedEncodingAndBoundaryClaimShapesFailClosed() {
    assertCode(() -> verifier().authenticate("Bearer a.a.a"), "MALFORMED_ACCESS_TOKEN");
    assertCode(
        () -> verifier().authenticate("Bearer AB.e30.AA"), "INVALID_ACCESS_TOKEN");
    assertCode(
        () ->
            verifier()
                .authenticate(
                    "Bearer " + OidcTestSupport.rawToken(keyPair, "[]", "{}")),
        "MALFORMED_ACCESS_TOKEN");
    assertCode(
        () ->
            verifier()
                .authenticate(
                    "Bearer " + OidcTestSupport.rawToken(keyPair, "{}", "[]")),
        "MALFORMED_ACCESS_TOKEN");

    assertInvalidClaims(claims -> claims.remove("aud"));
    assertInvalidClaims(claims -> claims.putArray("scope").add("aws.logs.read"));
    assertInvalidClaims(claims -> claims.put("scope", ""));
    assertInvalidClaims(claims -> claims.put("scope", "aws.logs.read "));
    assertInvalidClaims(claims -> claims.put("scope", "aws.logs.read  aws.logs.discover"));
    assertInvalidClaims(claims -> claims.put("scope", "aws.logs.read?"));
    assertInvalidClaims(claims -> claims.put("sub", ""));
    assertInvalidClaims(claims -> claims.put("sub", "bad?subject"));
    assertInvalidClaims(claims -> claims.put("sub", 42));
    assertInvalidClaims(claims -> claims.put("sub", "bad\nsubject"));
    assertInvalidClaims(claims -> claims.put("iss", "x".repeat(513)));
    assertInvalidClaims(claims -> claims.put("name", ""));
    assertInvalidClaims(claims -> claims.put("name", 42));
    assertInvalidClaims(claims -> claims.put("name", "x".repeat(129)));
    assertInvalidClaims(claims -> claims.remove("iat"));
    assertInvalidClaims(claims -> claims.put("nbf", "2030-01-01T00:00:00Z"));
    assertInvalidClaims(claims -> claims.put("iat", 1.5));
    assertInvalidClaims(claims -> claims.put("iat", -1));
    assertInvalidClaims(claims -> claims.put("exp", 9_007_199_254_740_992L));
    assertInvalidClaims(
        claims -> {
          long now = OidcTestSupport.NOW.getEpochSecond();
          claims.put("iat", now + 30);
          claims.put("nbf", now);
          claims.put("exp", now + 20);
        });
  }

  @Test
  void verifierRechecksIssuerEvenIfRegistryReturnsAMisboundProvider() {
    String token =
        OidcTestSupport.token(
            keyPair,
            header -> {},
            claims -> claims.put("iss", "https://attacker.example.invalid"));
    OidcAccessTokenVerifier verifier =
        new OidcAccessTokenVerifier(
            ignored -> java.util.Optional.of(OidcTestSupport.provider()),
            (provider, keyId, algorithm, now) ->
                (java.security.interfaces.RSAPublicKey) keyPair.getPublic(),
            clock());

    assertCode(() -> verifier.authenticate("Bearer " + token), "INVALID_ACCESS_TOKEN");
  }

  @Test
  void absentOptionalDisplayNameFallsBackToTheVerifiedSubject() {
    String token =
        OidcTestSupport.token(
            keyPair, header -> {}, claims -> claims.remove("name"));
    assertThat(verifier().authenticate("Bearer " + token).displayName())
        .isEqualTo("user:alice@example.invalid");
  }

  @Test
  void expiryFutureTimesAndLifetimeFailClosed() {
    long now = OidcTestSupport.NOW.getEpochSecond();
    assertCode(
        () -> authenticate(claims -> claims.put("exp", now)), "EXPIRED_ACCESS_TOKEN");
    assertInvalidClaims(claims -> claims.put("iat", now + 31));
    assertInvalidClaims(claims -> claims.put("nbf", now + 31));
    assertInvalidClaims(claims -> claims.put("nbf", now - 100));
    assertInvalidClaims(
        claims -> {
          claims.put("iat", now - 31);
          claims.put("exp", now + 300);
        });
  }

  @Test
  void providerAndKeyDependenciesFailClosedWithBoundedErrors() {
    String token = OidcTestSupport.token(keyPair);
    OidcAccessTokenVerifier registryFailure =
        new OidcAccessTokenVerifier(
            ignored -> {
              throw new IllegalStateException("database-secret");
            },
            (provider, keyId, algorithm, now) ->
                (java.security.interfaces.RSAPublicKey) keyPair.getPublic(),
            clock());
    assertCode(
        () -> registryFailure.authenticate("Bearer " + token),
        "OIDC_CONFIGURATION_UNAVAILABLE");

    AtomicInteger fetches = new AtomicInteger();
    OidcAccessTokenVerifier jwksFailure =
        new OidcAccessTokenVerifier(
            registry(),
            new CachingOidcJwkResolver(
                ignored -> {
                  fetches.incrementAndGet();
                  throw new IllegalStateException("endpoint-secret");
                }),
            clock());
    assertThatThrownBy(() -> jwksFailure.authenticate("Bearer " + token))
        .isInstanceOf(AuthenticationException.class)
        .hasMessage("The configured OIDC verification keys are unavailable.")
        .hasMessageNotContaining(token)
        .hasMessageNotContaining("endpoint-secret");
    assertThat(fetches).hasValue(1);
  }

  @Test
  void tokenExpiringDuringKeyResolutionFailsClosedWhenTimeIsResampled() {
    AtomicReference<Instant> currentClockTime = new AtomicReference<>(OidcTestSupport.NOW);
    Clock advancingClock =
        new Clock() {
          @Override
          public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(java.time.ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return currentClockTime.get();
          }
        };

    String token =
        OidcTestSupport.token(
            keyPair,
            header -> {},
            claims -> claims.put("exp", OidcTestSupport.NOW.getEpochSecond() + 5));

    OidcVerificationKeyResolver delayedResolver =
        (provider, keyId, algorithm, now) -> {
          currentClockTime.set(OidcTestSupport.NOW.plusSeconds(6));
          return (java.security.interfaces.RSAPublicKey) keyPair.getPublic();
        };

    OidcAccessTokenVerifier verifier =
        new OidcAccessTokenVerifier(
            issuer -> java.util.Optional.of(OidcTestSupport.provider()),
            delayedResolver,
            advancingClock);

    assertThatThrownBy(() -> verifier.authenticate("Bearer " + token))
        .isInstanceOf(AuthenticationException.class)
        .extracting(error -> ((AuthenticationException) error).code())
        .isEqualTo("EXPIRED_ACCESS_TOKEN");
  }

  private OidcAccessTokenVerifier verifier() {
    return new OidcAccessTokenVerifier(
        registry(),
        new CachingOidcJwkResolver(ignored -> OidcTestSupport.publicJwks(keyPair)),
        clock());
  }

  private StaticOidcProviderRegistry registry() {
    return new StaticOidcProviderRegistry(List.of(OidcTestSupport.provider()));
  }

  private static Clock clock() {
    return Clock.fixed(OidcTestSupport.NOW, ZoneOffset.UTC);
  }

  private void assertInvalidHeader(Consumer<ObjectNode> mutation) {
    assertCode(
        () ->
            verifier()
                .authenticate(
                    "Bearer " + OidcTestSupport.token(keyPair, mutation, claims -> {})),
        "INVALID_ACCESS_TOKEN");
  }

  private void assertInvalidClaims(Consumer<ObjectNode> mutation) {
    assertCode(() -> authenticate(mutation), "INVALID_ACCESS_TOKEN");
  }

  private void authenticate(Consumer<ObjectNode> mutation) {
    verifier()
        .authenticate(
            "Bearer " + OidcTestSupport.token(keyPair, header -> {}, mutation));
  }

  private static void assertCode(ThrowingCallable operation, String expectedCode) {
    assertThatThrownBy(operation)
        .isInstanceOf(AuthenticationException.class)
        .extracting(error -> ((AuthenticationException) error).code())
        .isEqualTo(expectedCode);
  }
}
