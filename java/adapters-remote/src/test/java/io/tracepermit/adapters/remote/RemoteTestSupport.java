package io.tracepermit.adapters.remote;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.identity.AuthenticatedConnector;
import io.tracepermit.identity.ConnectorOrigin;
import io.tracepermit.identity.ConnectorScope;
import io.tracepermit.identity.CredentialFingerprint;
import io.tracepermit.identity.TokenSenderConstraint;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import okhttp3.OkHttpClient;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;

final class RemoteTestSupport {
  static final Instant NOW = Instant.parse("2030-01-01T00:00:30Z");
  static final String ISSUER = "https://identity.example.invalid";
  static final String AUDIENCE = "https://mcp.tracepermit.example.invalid";
  static final String KEY_ID = "oidc-key-001";
  static final ConnectorScope DISCOVER = ConnectorScope.of("aws.logs.discover");
  static final ConnectorScope READ = ConnectorScope.of("aws.logs.read");

  private RemoteTestSupport() {}

  static TlsMaterial tlsMaterial() {
    HeldCertificate certificate =
        new HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build();
    HandshakeCertificates server =
        new HandshakeCertificates.Builder().heldCertificate(certificate).build();
    HandshakeCertificates client =
        new HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate())
            .build();
    return new TlsMaterial(server, client);
  }

  static MutualTlsMaterial mutualTlsMaterial() {
    HeldCertificate certificateAuthority =
        new HeldCertificate.Builder()
            .commonName("TracePermit test CA")
            .certificateAuthority(0)
            .build();
    HeldCertificate serverCertificate =
        new HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .signedBy(certificateAuthority)
            .build();
    HeldCertificate clientCertificate =
        new HeldCertificate.Builder()
            .commonName("connector-client")
            .signedBy(certificateAuthority)
            .build();
    HeldCertificate otherClientCertificate =
        new HeldCertificate.Builder()
            .commonName("other-connector-client")
            .signedBy(certificateAuthority)
            .build();
    HandshakeCertificates server =
        new HandshakeCertificates.Builder()
            .heldCertificate(serverCertificate, certificateAuthority.certificate())
            .addTrustedCertificate(certificateAuthority.certificate())
            .build();
    HandshakeCertificates client =
        new HandshakeCertificates.Builder()
            .heldCertificate(clientCertificate, certificateAuthority.certificate())
            .addTrustedCertificate(certificateAuthority.certificate())
            .build();
    HandshakeCertificates otherClient =
        new HandshakeCertificates.Builder()
            .heldCertificate(otherClientCertificate, certificateAuthority.certificate())
            .addTrustedCertificate(certificateAuthority.certificate())
            .build();
    HandshakeCertificates publicClient =
        new HandshakeCertificates.Builder()
            .addTrustedCertificate(certificateAuthority.certificate())
            .build();
    return new MutualTlsMaterial(
        server, client, otherClient, publicClient, clientCertificate.certificate());
  }

  static OkHttpClient client(TlsMaterial tls) {
    return new OkHttpClient.Builder()
        .sslSocketFactory(tls.client().sslSocketFactory(), tls.client().trustManager())
        .build();
  }

  static OkHttpClient client(HandshakeCertificates certificates) {
    return new OkHttpClient.Builder()
        .sslSocketFactory(certificates.sslSocketFactory(), certificates.trustManager())
        .build();
  }

  static AuthenticatedConnector connector(ConnectorScope... scopes) {
    return connector(ConnectorOrigin.CHATGPT_WEB, scopes);
  }

  static AuthenticatedConnector connector(ConnectorOrigin origin, ConnectorScope... scopes) {
    return new AuthenticatedConnector(
        ISSUER + "#user:alice@example.invalid",
        "Alice Example",
        "tenant:acme",
        origin,
        "chatgpt-connector",
        "ChatGPT Web",
        ISSUER,
        NOW,
        "oauth-access-token-jwt-rs256-v1",
        KEY_ID,
        new CredentialFingerprint("sha256:" + "a".repeat(64)),
        List.of(scopes),
        NOW.plusSeconds(300));
  }

  static AuthenticatedConnector senderConstrainedConnector(
      X509Certificate certificate, ConnectorScope... scopes) {
    return new AuthenticatedConnector(
        ISSUER + "#user:alice@example.invalid",
        "Alice Example",
        "tenant:acme",
        ConnectorOrigin.CHATGPT_WEB,
        "chatgpt-connector",
        "ChatGPT Web",
        ISSUER,
        NOW,
        "oauth-access-token-jwt-rs256-v1",
        KEY_ID,
        new CredentialFingerprint("sha256:" + "a".repeat(64)),
        List.of(scopes),
        NOW.plusSeconds(300),
        Optional.of(
            new TokenSenderConstraint(
                TokenSenderConstraint.MTLS_X5T_S256_V1,
                new CredentialFingerprint(
                    "sha256:" + HexFormat.of().formatHex(certificateDigest(certificate))))));
  }

  static KeyPair rsaKeyPair() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException(exception);
    }
  }

  static String token(KeyPair keyPair) {
    return token(keyPair, null);
  }

  static String token(KeyPair keyPair, X509Certificate certificate) {
    ObjectNode header = JsonSupport.MAPPER.createObjectNode();
    header.put("alg", "RS256");
    header.put("typ", "at+jwt");
    header.put("kid", KEY_ID);
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
    if (certificate != null) {
      claims.putObject("cnf").put("x5t#S256", certificateThumbprint(certificate));
    }
    String input = encode(header) + "." + encode(claims);
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

  static RemoteOAuthEndpointConfiguration endpointConfiguration() {
    return new RemoteOAuthEndpointConfiguration(
        java.util.Set.of("localhost"),
        java.util.Set.of(
            URI.create("https://chatgpt.com"), URI.create("https://claude.ai")),
        true,
        URI.create(AUDIENCE + "/.well-known/oauth-protected-resource"),
        List.of(DISCOVER, READ),
        65_536);
  }

  private static String encode(ObjectNode node) {
    try {
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(JsonSupport.MAPPER.writeValueAsBytes(node));
    } catch (java.io.IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static String certificateThumbprint(X509Certificate certificate) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(certificateDigest(certificate));
  }

  private static byte[] certificateDigest(X509Certificate certificate) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException(exception);
    }
  }

  record TlsMaterial(HandshakeCertificates server, HandshakeCertificates client) {}

  record MutualTlsMaterial(
      HandshakeCertificates server,
      HandshakeCertificates client,
      HandshakeCertificates otherClient,
      HandshakeCertificates publicClient,
      X509Certificate clientCertificate) {}
}
