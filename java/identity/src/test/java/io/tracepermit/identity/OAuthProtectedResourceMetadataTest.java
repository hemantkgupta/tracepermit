package io.tracepermit.identity;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class OAuthProtectedResourceMetadataTest {
  @Test
  void publishesBoundedMetadataAndBearerChallenges() {
    OAuthProtectedResourceMetadata metadata =
        OAuthProtectedResourceMetadata.from(OidcTestSupport.provider());

    assertThat(metadata.toJson().toPrettyString())
        .isEqualTo(
            """
            {
              "resource" : "https://mcp.tracepermit.example.invalid",
              "resource_name" : "TracePermit MCP",
              "authorization_servers" : [ "https://identity.example.invalid" ],
              "scopes_supported" : [ "aws.logs.discover", "aws.logs.read" ],
              "bearer_methods_supported" : [ "header" ]
            }""");
    assertThat(
            BearerChallenge.missingToken(
                OidcTestSupport.provider().resourceMetadataUri(),
                List.of(
                    ConnectorScope.of("aws.logs.read"),
                    ConnectorScope.of("aws.logs.discover"))))
        .isEqualTo(
            "Bearer resource_metadata=\"https://mcp.tracepermit.example.invalid/.well-known/oauth-protected-resource\", scope=\"aws.logs.discover aws.logs.read\"");
    assertThat(
            BearerChallenge.invalidToken(
                OidcTestSupport.provider().resourceMetadataUri(),
                List.of(ConnectorScope.of("aws.logs.read"))))
        .isEqualTo(
            "Bearer resource_metadata=\"https://mcp.tracepermit.example.invalid/.well-known/oauth-protected-resource\", scope=\"aws.logs.read\", error=\"invalid_token\"");
    assertThat(
            BearerChallenge.toolAuthenticationRequired(
                OidcTestSupport.provider().resourceMetadataUri(),
                List.of(ConnectorScope.of("aws.logs.read"))))
        .contains(
            "error=\"insufficient_scope\"",
            "error_description=\"Authentication is required to use this tool.\"");
    assertThat(
            BearerChallenge.toolInvalidToken(
                OidcTestSupport.provider().resourceMetadataUri(),
                List.of(ConnectorScope.of("aws.logs.read"))))
        .contains(
            "error=\"invalid_token\"",
            "error_description=\"The access token is invalid or expired.\"");
  }

  @Test
  void unsafeMetadataAndProviderConfigurationAreRejected() {
    assertThatThrownBy(
            () ->
                new OAuthProtectedResourceMetadata(
                    URI.create("http://mcp.example.invalid"),
                    "TracePermit",
                    List.of(URI.create("https://identity.example.invalid")),
                    List.of(ConnectorScope.of("aws.logs.read")),
                    List.of("header")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new OAuthProtectedResourceMetadata(
                    URI.create("https://mcp.example.invalid"),
                    "TracePermit",
                    List.of(),
                    List.of(ConnectorScope.of("aws.logs.read")),
                    List.of("header")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new OAuthProtectedResourceMetadata(
                    URI.create("https://mcp.example.invalid"),
                    "TracePermit",
                    List.of(URI.create("https://identity.example.invalid")),
                    List.of(),
                    List.of("header")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new OAuthProtectedResourceMetadata(
                    URI.create("https://mcp.example.invalid"),
                    "TracePermit",
                    List.of(URI.create("https://identity.example.invalid")),
                    List.of(ConnectorScope.of("aws.logs.read")),
                    List.of("query")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new OAuthProtectedResourceMetadata(
                    URI.create("https://mcp.example.invalid"),
                    "",
                    List.of(URI.create("https://identity.example.invalid")),
                    List.of(ConnectorScope.of("aws.logs.read")),
                    List.of("header")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                BearerChallenge.missingToken(
                    URI.create("https://mcp.example.invalid/metadata"), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                BearerChallenge.missingToken(
                    URI.create("http://attacker.example.invalid/metadata"),
                    List.of(ConnectorScope.of("aws.logs.read"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new OidcProviderConfiguration(
                    URI.create("http://identity.example.invalid"),
                    OidcTestSupport.AUDIENCE,
                    URI.create("https://identity.example.invalid/jwks"),
                    URI.create("https://mcp.example.invalid/metadata"),
                    List.of(URI.create("https://identity.example.invalid")),
                    "TracePermit",
                    Map.of("client", "Client"),
                    "org_id",
                    Map.of("external", "tenant"),
                    Set.of(ConnectorScope.of("aws.logs.read")),
                    ConnectorOrigin.CHATGPT_WEB,
                    Duration.ofMinutes(5),
                    Duration.ofMinutes(1),
                    Duration.ofSeconds(30)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void staticRegistryRejectsEmptyAndDuplicateIssuerConfiguration() {
    assertThatThrownBy(() -> new StaticOidcProviderRegistry(List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new StaticOidcProviderRegistry(
                    List.of(OidcTestSupport.provider(), OidcTestSupport.provider())))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void providerConfigurationRejectsEmptyAndAmbiguousTrustMappings() {
    assertProviderRejected(List.of(), Map.of("client", "Client"), "org_id");
    assertProviderRejected(
        List.of(
            URI.create("https://identity.example.invalid"),
            URI.create("https://identity.example.invalid")),
        Map.of("client", "Client"),
        "org_id");
    assertProviderRejected(
        List.of(URI.create("https://identity.example.invalid")), Map.of(), "org_id");
    assertProviderRejected(
        List.of(URI.create("https://identity.example.invalid")),
        Map.of("client", "Client"),
        "bad claim");
    assertProviderRejected(
        List.of(URI.create("https://identity.example.invalid")),
        Map.of("client", "Client"),
        "http://tracepermit.example/tenant");
    assertProviderRejected(
        List.of(URI.create("https://identity.example.invalid")),
        Map.of("client", "Client"),
        "https://tracepermit.example/tenant?source=request");

    assertThat(
            new OidcProviderConfiguration(
                    URI.create(OidcTestSupport.ISSUER),
                    OidcTestSupport.AUDIENCE,
                    URI.create(OidcTestSupport.ISSUER + "/jwks"),
                    URI.create(OidcTestSupport.AUDIENCE + "/metadata"),
                    List.of(URI.create("https://identity.example.invalid")),
                    "TracePermit",
                    Map.of("client", "Client"),
                    "https://tracepermit.example/tenant",
                    Map.of("external", "tenant"),
                    Set.of(ConnectorScope.of("aws.logs.read")),
                    ConnectorOrigin.CHATGPT_WEB,
                    Duration.ofMinutes(5),
                    Duration.ofMinutes(1),
                    Duration.ofSeconds(30))
                .tenantClaim())
        .isEqualTo("https://tracepermit.example/tenant");
  }

  @Test
  void providerConfigurationRejectsUnsafeUrisMappingsTextAndLifetimes() {
    assertConfigurationRejected(
        () -> provider(
            URI.create("https://user@identity.example.invalid"),
            OidcTestSupport.AUDIENCE,
            URI.create(OidcTestSupport.ISSUER + "/jwks"),
            URI.create(OidcTestSupport.AUDIENCE + "/metadata"),
            "TracePermit",
            Map.of("client", "Client"),
            Map.of("external", "tenant"),
            Set.of(ConnectorScope.of("aws.logs.read")),
            Duration.ofMinutes(5),
            Duration.ofMinutes(1),
            Duration.ofSeconds(30)));
    assertConfigurationRejected(
        () -> provider(
            URI.create(OidcTestSupport.ISSUER + "#fragment"),
            OidcTestSupport.AUDIENCE,
            URI.create(OidcTestSupport.ISSUER + "/jwks"),
            URI.create(OidcTestSupport.AUDIENCE + "/metadata"),
            "TracePermit",
            Map.of("client", "Client"),
            Map.of("external", "tenant"),
            Set.of(ConnectorScope.of("aws.logs.read")),
            Duration.ofMinutes(5),
            Duration.ofMinutes(1),
            Duration.ofSeconds(30)));
    assertConfigurationRejected(
        () -> provider(
            URI.create(OidcTestSupport.ISSUER),
            "http://mcp.example.invalid",
            URI.create(OidcTestSupport.ISSUER + "/jwks"),
            URI.create(OidcTestSupport.AUDIENCE + "/metadata"),
            "TracePermit",
            Map.of("client", "Client"),
            Map.of("external", "tenant"),
            Set.of(ConnectorScope.of("aws.logs.read")),
            Duration.ofMinutes(5),
            Duration.ofMinutes(1),
            Duration.ofSeconds(30)));
    assertConfigurationRejected(
        () -> provider(
            URI.create(OidcTestSupport.ISSUER),
            OidcTestSupport.AUDIENCE,
            URI.create(OidcTestSupport.ISSUER + "/jwks?source=token"),
            URI.create(OidcTestSupport.AUDIENCE + "/metadata"),
            "TracePermit",
            Map.of("client", "Client"),
            Map.of("external", "tenant"),
            Set.of(ConnectorScope.of("aws.logs.read")),
            Duration.ofMinutes(5),
            Duration.ofMinutes(1),
            Duration.ofSeconds(30)));
    assertConfigurationRejected(
        () -> provider(
            URI.create(OidcTestSupport.ISSUER),
            OidcTestSupport.AUDIENCE,
            URI.create(OidcTestSupport.ISSUER + "/jwks"),
            URI.create(OidcTestSupport.AUDIENCE + "/metadata#fragment"),
            "TracePermit",
            Map.of("client", "Client"),
            Map.of("external", "tenant"),
            Set.of(ConnectorScope.of("aws.logs.read")),
            Duration.ofMinutes(5),
            Duration.ofMinutes(1),
            Duration.ofSeconds(30)));
    assertConfigurationRejected(
        () -> provider(
            URI.create(OidcTestSupport.ISSUER),
            OidcTestSupport.AUDIENCE,
            URI.create(OidcTestSupport.ISSUER + "/jwks"),
            URI.create(OidcTestSupport.AUDIENCE + "/metadata"),
            "x".repeat(129),
            Map.of("client", "Client"),
            Map.of("external", "tenant"),
            Set.of(ConnectorScope.of("aws.logs.read")),
            Duration.ofMinutes(5),
            Duration.ofMinutes(1),
            Duration.ofSeconds(30)));
    assertConfigurationRejected(
        () -> provider(
            URI.create(OidcTestSupport.ISSUER),
            OidcTestSupport.AUDIENCE,
            URI.create(OidcTestSupport.ISSUER + "/jwks"),
            URI.create(OidcTestSupport.AUDIENCE + "/metadata"),
            "TracePermit",
            Map.of("", "Client"),
            Map.of("external", "tenant"),
            Set.of(ConnectorScope.of("aws.logs.read")),
            Duration.ofMinutes(5),
            Duration.ofMinutes(1),
            Duration.ofSeconds(30)));
    assertConfigurationRejected(
        () -> provider(
            URI.create(OidcTestSupport.ISSUER),
            OidcTestSupport.AUDIENCE,
            URI.create(OidcTestSupport.ISSUER + "/jwks"),
            URI.create(OidcTestSupport.AUDIENCE + "/metadata"),
            "TracePermit",
            Map.of("client", "Client\nName"),
            Map.of("external", "tenant"),
            Set.of(ConnectorScope.of("aws.logs.read")),
            Duration.ofMinutes(5),
            Duration.ofMinutes(1),
            Duration.ofSeconds(30)));
    assertConfigurationRejected(
        () -> provider(
            URI.create(OidcTestSupport.ISSUER),
            OidcTestSupport.AUDIENCE,
            URI.create(OidcTestSupport.ISSUER + "/jwks"),
            URI.create(OidcTestSupport.AUDIENCE + "/metadata"),
            "TracePermit",
            Map.of("client", "Client"),
            Map.of(),
            Set.of(ConnectorScope.of("aws.logs.read")),
            Duration.ofMinutes(5),
            Duration.ofMinutes(1),
            Duration.ofSeconds(30)));
    assertConfigurationRejected(
        () -> provider(
            URI.create(OidcTestSupport.ISSUER),
            OidcTestSupport.AUDIENCE,
            URI.create(OidcTestSupport.ISSUER + "/jwks"),
            URI.create(OidcTestSupport.AUDIENCE + "/metadata"),
            "TracePermit",
            Map.of("client", "Client"),
            Map.of("external", "tenant"),
            Set.of(),
            Duration.ofMinutes(5),
            Duration.ofMinutes(1),
            Duration.ofSeconds(30)));
    assertConfigurationRejected(
        () -> provider(
            URI.create(OidcTestSupport.ISSUER),
            OidcTestSupport.AUDIENCE,
            URI.create(OidcTestSupport.ISSUER + "/jwks"),
            URI.create(OidcTestSupport.AUDIENCE + "/metadata"),
            "TracePermit",
            Map.of("client", "Client"),
            Map.of("external", "tenant"),
            Set.of(ConnectorScope.of("aws.logs.read")),
            Duration.ZERO,
            Duration.ofMinutes(1),
            Duration.ofSeconds(30)));
    assertConfigurationRejected(
        () -> provider(
            URI.create(OidcTestSupport.ISSUER),
            OidcTestSupport.AUDIENCE,
            URI.create(OidcTestSupport.ISSUER + "/jwks"),
            URI.create(OidcTestSupport.AUDIENCE + "/metadata"),
            "TracePermit",
            Map.of("client", "Client"),
            Map.of("external", "tenant"),
            Set.of(ConnectorScope.of("aws.logs.read")),
            Duration.ofMinutes(16),
            Duration.ofMinutes(1),
            Duration.ofSeconds(30)));
    assertConfigurationRejected(
        () -> provider(
            URI.create(OidcTestSupport.ISSUER),
            OidcTestSupport.AUDIENCE,
            URI.create(OidcTestSupport.ISSUER + "/jwks"),
            URI.create(OidcTestSupport.AUDIENCE + "/metadata"),
            "TracePermit",
            Map.of("client", "Client"),
            Map.of("external", "tenant"),
            Set.of(ConnectorScope.of("aws.logs.read")),
            Duration.ofMinutes(5),
            Duration.ofSeconds(-1),
            Duration.ofSeconds(30)));
    assertConfigurationRejected(
        () -> provider(
            URI.create(OidcTestSupport.ISSUER),
            OidcTestSupport.AUDIENCE,
            URI.create(OidcTestSupport.ISSUER + "/jwks"),
            URI.create(OidcTestSupport.AUDIENCE + "/metadata"),
            "TracePermit",
            Map.of("client", "Client"),
            Map.of("external", "tenant"),
            Set.of(ConnectorScope.of("aws.logs.read")),
            Duration.ofMinutes(5),
            Duration.ofMinutes(1),
            Duration.ofMinutes(2)));
  }

  private static void assertProviderRejected(
      List<URI> authorizationServers, Map<String, String> clients, String tenantClaim) {
    assertThatThrownBy(
            () ->
                new OidcProviderConfiguration(
                    URI.create(OidcTestSupport.ISSUER),
                    OidcTestSupport.AUDIENCE,
                    URI.create(OidcTestSupport.ISSUER + "/jwks"),
                    URI.create(OidcTestSupport.AUDIENCE + "/metadata"),
                    authorizationServers,
                    "TracePermit",
                    clients,
                    tenantClaim,
                    Map.of("external", "tenant"),
                    Set.of(ConnectorScope.of("aws.logs.read")),
                    ConnectorOrigin.CHATGPT_WEB,
                    Duration.ofMinutes(5),
                    Duration.ofMinutes(1),
                    Duration.ofSeconds(30)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static OidcProviderConfiguration provider(
      URI issuer,
      String resourceAudience,
      URI jwksUri,
      URI resourceMetadataUri,
      String resourceName,
      Map<String, String> clientNames,
      Map<String, String> tenantMappings,
      Set<ConnectorScope> supportedScopes,
      Duration maximumTokenLifetime,
      Duration jwksCacheLifetime,
      Duration forcedRefreshCooldown) {
    return new OidcProviderConfiguration(
        issuer,
        resourceAudience,
        jwksUri,
        resourceMetadataUri,
        List.of(URI.create(OidcTestSupport.ISSUER)),
        resourceName,
        clientNames,
        "org_id",
        tenantMappings,
        supportedScopes,
        ConnectorOrigin.CHATGPT_WEB,
        maximumTokenLifetime,
        jwksCacheLifetime,
        forcedRefreshCooldown);
  }

  private static void assertConfigurationRejected(ThrowingCallable operation) {
    assertThatThrownBy(operation).isInstanceOf(IllegalArgumentException.class);
  }
}
