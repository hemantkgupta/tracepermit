package io.tracepermit.adapters.http.requester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.identity.BrowserIdTokenVerifier;
import io.tracepermit.identity.OidcAccessTokenVerifier;
import io.tracepermit.identity.OidcVerificationKeyResolver;
import io.tracepermit.identity.StaticOidcProviderRegistry;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RequesterTokenVerifierTest {
  private static final String KEY_ID = "requester-key-001";
  private static final KeyPair KEY = keyPair();

  @TempDir java.nio.file.Path temporary;

  @Test
  void verifiesTheExactInstalledRequesterAccessAndIdentityProfile() throws Exception {
    InstalledRequesterConfiguration configuration =
        InstalledRequesterTestSupport.fixture(temporary).load();
    RequesterTokenVerifier verifier = verifier(configuration);
    String access = access(configuration, claims -> {});

    RequesterTokenVerifier.VerifiedAccess verified =
        verifier.verifyLogin(access, identity(configuration, claims -> {}), "login-nonce");

    assertThat(verified.connector().subjectId())
        .isEqualTo(configuration.issuer() + "#" + configuration.expectedSubject());
    assertThat(verified.connector().organizationId()).isEqualTo(configuration.organization());
    assertThat(verified.connector().clientId()).isEqualTo(configuration.clientId());
    assertThat(verified.expiresAt()).isEqualTo(InstalledRequesterTestSupport.NOW.plusSeconds(270));
  }

  @Test
  void rejectsIdentityScopeLifetimeAndSenderConstraintSubstitution() throws Exception {
    InstalledRequesterConfiguration configuration =
        InstalledRequesterTestSupport.fixture(temporary).load();
    RequesterTokenVerifier verifier = verifier(configuration);

    assertCode(
        () -> verifier.verifyAccess(
            access(configuration, claims -> claims.put("sub", "provider|another-requester"))),
        "REQUESTER_IDENTITY_MISMATCH");
    assertCode(
        () -> verifier.verifyAccess(
            access(configuration, claims -> claims.put("scope", "openid approvals.read"))),
        "ACCESS_TOKEN_INVALID");
    assertCode(
        () -> verifier.verifyAccess(access(configuration,
            claims -> claims.put("exp", InstalledRequesterTestSupport.NOW.plusSeconds(59)
                .getEpochSecond()))),
        "ACCESS_TOKEN_INVALID");
    assertCode(
        () -> verifier.verifyAccess(access(configuration, claims -> claims.putObject("cnf")
            .put("x5t#S256", Base64.getUrlEncoder().withoutPadding()
                .encodeToString(new byte[32])))),
        "ACCESS_TOKEN_INVALID");
    assertCode(() -> verifier.verifyAccess("not-a-token"), "ACCESS_TOKEN_INVALID");
  }

  @Test
  void rejectsInvalidOrInsufficientlyLivedIdentityTokensWithoutLeakingVerifierDetails()
      throws Exception {
    InstalledRequesterConfiguration configuration =
        InstalledRequesterTestSupport.fixture(temporary).load();
    RequesterTokenVerifier verifier = verifier(configuration);
    String access = access(configuration, claims -> {});

    assertCode(() -> verifier.verifyLogin(access, "not-an-id-token", "login-nonce"),
        "ID_TOKEN_INVALID");
    assertCode(
        () -> verifier.verifyLogin(access,
            identity(configuration, claims -> claims.put("exp",
                InstalledRequesterTestSupport.NOW.plusSeconds(59).getEpochSecond())),
            "login-nonce"),
        "ID_TOKEN_INVALID");
  }

  private static RequesterTokenVerifier verifier(
      InstalledRequesterConfiguration configuration) {
    OidcVerificationKeyResolver resolver =
        (provider, keyId, algorithm, now) -> (RSAPublicKey) KEY.getPublic();
    var access = new OidcAccessTokenVerifier(
        new StaticOidcProviderRegistry(List.of(configuration.provider())),
        resolver,
        InstalledRequesterTestSupport.CLOCK);
    var identity = new BrowserIdTokenVerifier(
        configuration.provider(), resolver, InstalledRequesterTestSupport.CLOCK,
        configuration.organization());
    return new RequesterTokenVerifier(
        configuration, access, identity, InstalledRequesterTestSupport.CLOCK);
  }

  private static String access(InstalledRequesterConfiguration configuration,
      Consumer<ObjectNode> mutation) {
    ObjectNode header = header("at+jwt");
    ObjectNode claims = common(configuration);
    claims.put("aud", configuration.audience());
    claims.put("name", "Synthetic requester");
    claims.put("client_id", configuration.clientId());
    claims.put("scope", "openid approvals.read approvals.request approvals.execute");
    claims.put("nbf", InstalledRequesterTestSupport.NOW.minusSeconds(30).getEpochSecond());
    claims.put("jti", "requester-token-001");
    mutation.accept(claims);
    return sign(header, claims);
  }

  private static String identity(InstalledRequesterConfiguration configuration,
      Consumer<ObjectNode> mutation) {
    ObjectNode claims = common(configuration);
    claims.put("aud", configuration.clientId());
    claims.put("nonce", "login-nonce");
    mutation.accept(claims);
    return sign(header("JWT"), claims);
  }

  private static ObjectNode common(InstalledRequesterConfiguration configuration) {
    ObjectNode claims = JsonSupport.MAPPER.createObjectNode();
    claims.put("iss", configuration.issuer().toString());
    claims.put("sub", configuration.expectedSubject());
    claims.put("org_id", configuration.organization());
    claims.put("iat", InstalledRequesterTestSupport.NOW.minusSeconds(30).getEpochSecond());
    claims.put("exp", InstalledRequesterTestSupport.NOW.plusSeconds(270).getEpochSecond());
    return claims;
  }

  private static ObjectNode header(String type) {
    ObjectNode header = JsonSupport.MAPPER.createObjectNode();
    header.put("alg", "RS256");
    header.put("typ", type);
    header.put("kid", KEY_ID);
    return header;
  }

  private static String sign(ObjectNode header, ObjectNode claims) {
    try {
      String input = encode(header) + "." + encode(claims);
      Signature signature = Signature.getInstance("SHA256withRSA");
      signature.initSign(KEY.getPrivate());
      signature.update(input.getBytes(StandardCharsets.US_ASCII));
      return input + "." + Base64.getUrlEncoder().withoutPadding()
          .encodeToString(signature.sign());
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static String encode(ObjectNode value) {
    try {
      return Base64.getUrlEncoder().withoutPadding()
          .encodeToString(JsonSupport.MAPPER.writeValueAsBytes(value));
    } catch (java.io.IOException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static KeyPair keyPair() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
      String expected) {
    assertThatThrownBy(action).isInstanceOfSatisfying(
        InstalledRequesterFailure.class,
        failure -> assertThat(failure.code()).isEqualTo(expected));
  }
}
