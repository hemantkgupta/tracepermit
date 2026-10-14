package io.tracepermit.adapters.http;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class HostedClientOAuthConfigurationTest {
  @Test
  void mapsOnlyOperatorOwnedOAuthTrustAndRequiredToolScopes() {
    HostedClientOAuthConfiguration configuration =
        HostedClientOAuthConfiguration.from(environment());

    assertThat(configuration.provider().issuer().toString())
        .isEqualTo("https://identity.example.invalid");
    assertThat(configuration.provider().resourceAudience())
        .isEqualTo("https://mcp.example.invalid");
    assertThat(configuration.provider().clientNames())
        .containsExactlyEntriesOf(Map.of("https://chatgpt.com/oauth/client.json", "ChatGPT hosted client"));
    assertThat(configuration.provider().tenantMappings())
        .containsExactlyEntriesOf(Map.of("enterprise-example", "tenant:demo"));
    assertThat(configuration.provider().supportedScopes())
        .extracting(scope -> scope.value())
        .contains("offline_access", "aws.logs.discover", "aws.logs.read");
    assertThat(configuration.requiredToolScopes())
        .extracting(scope -> scope.value())
        .containsExactly("aws.logs.discover", "aws.logs.read");

    Map<String, String> namespacedClaim = environment();
    namespacedClaim.put(
        "TRACEPERMIT_OAUTH_TENANT_CLAIM", "https://tracepermit.example/tenant");
    assertThat(HostedClientOAuthConfiguration.from(namespacedClaim).provider().tenantClaim())
        .isEqualTo("https://tracepermit.example/tenant");
  }

  @Test
  void incompleteOrUnsafeConfigurationFailsBeforeListening() {
    Map<String, String> missing = environment();
    missing.remove("TRACEPERMIT_OAUTH_CLIENT_ID");
    assertThatThrownBy(() -> HostedClientOAuthConfiguration.from(missing))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("TRACEPERMIT_OAUTH_CLIENT_ID");

    Map<String, String> unsafeJwks = environment();
    unsafeJwks.put("TRACEPERMIT_OAUTH_JWKS_URI", "http://127.0.0.1/jwks.json");
    assertThatThrownBy(() -> HostedClientOAuthConfiguration.from(unsafeJwks))
        .isInstanceOf(IllegalArgumentException.class);

    Map<String, String> missingScope = environment();
    missingScope.put("TRACEPERMIT_OAUTH_SCOPES", "openid aws.logs.discover");
    assertThatThrownBy(() -> HostedClientOAuthConfiguration.from(missingScope))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("required TracePermit tool scopes");
  }

  @Test
  void rejectsAmbiguousPortsOriginsScopesAndControlCharacters() {
    HostedClientOAuthConfiguration valid =
        HostedClientOAuthConfiguration.from(environment());
    assertThatThrownBy(
            () ->
                new HostedClientOAuthConfiguration(
                    valid.repositoryRoot(),
                    -1,
                    valid.controlPlanePort(),
                    valid.resourceMetadataUri(),
                    valid.provider()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("valid TCP ports");
    assertThatThrownBy(
            () ->
                new HostedClientOAuthConfiguration(
                    valid.repositoryRoot(),
                    8090,
                    8090,
                    valid.resourceMetadataUri(),
                    valid.provider()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("distinct");
    assertThatThrownBy(
            () ->
                new HostedClientOAuthConfiguration(
                    valid.repositoryRoot(),
                    valid.mcpPort(),
                    valid.controlPlanePort(),
                    URI.create("https://other.example.invalid/metadata"),
                    valid.provider()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("one authority");

    Map<String, String> claude = environment();
    claude.put("TRACEPERMIT_OAUTH_ORIGIN", "claude-web");
    assertThat(HostedClientOAuthConfiguration.from(claude).provider().origin().value())
        .isEqualTo("claude-web");

    Map<String, String> invalidOrigin = environment();
    invalidOrigin.put("TRACEPERMIT_OAUTH_ORIGIN", "unknown-web");
    assertThatThrownBy(() -> HostedClientOAuthConfiguration.from(invalidOrigin))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ORIGIN");

    for (String invalidScopes :
        new String[] {
          " openid aws.logs.discover aws.logs.read",
          "openid aws.logs.discover aws.logs.read aws.logs.read"
        }) {
      Map<String, String> invalid = environment();
      invalid.put("TRACEPERMIT_OAUTH_SCOPES", invalidScopes);
      assertThatThrownBy(() -> HostedClientOAuthConfiguration.from(invalid))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("SCOPES");
    }

    Map<String, String> invalidPort = environment();
    invalidPort.put("TRACEPERMIT_OAUTH_MCP_PORT", "not-a-port");
    assertThatThrownBy(() -> HostedClientOAuthConfiguration.from(invalidPort))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("valid TCP port");

    Map<String, String> controlCharacter = environment();
    controlCharacter.put("TRACEPERMIT_OAUTH_CLIENT_NAME", "unsafe\nname");
    assertThatThrownBy(() -> HostedClientOAuthConfiguration.from(controlCharacter))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("CLIENT_NAME");

    for (String invalidClaim :
        new String[] {
          "http://tracepermit.example/tenant",
          "https://tracepermit.example/tenant?source=request",
          "bad claim"
        }) {
      Map<String, String> invalid = environment();
      invalid.put("TRACEPERMIT_OAUTH_TENANT_CLAIM", invalidClaim);
      assertThatThrownBy(() -> HostedClientOAuthConfiguration.from(invalid))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("tenantClaim");
    }
  }

  @Test
  void completeOAuthCompositionStartsWithoutADevelopmentTokenIssuer() throws Exception {
    Map<String, String> environment = environment();
    environment.put(
        "TRACEPERMIT_REPOSITORY_ROOT", System.getProperty("tracepermit.repositoryRoot"));
    Clock clock = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);

    try (HostedClientOAuthBackendMain.RunningBackend backend =
        HostedClientOAuthBackendMain.start(environment, clock)) {
      HttpClient client = HttpClient.newHttpClient();
      HttpResponse<String> health = get(client, backend.mcpPort(), "/health");
      HttpResponse<String> token = get(client, backend.mcpPort(), "/dev/oauth/token");
      HttpResponse<String> catalog =
          post(
              client,
              backend.mcpPort(),
              """
              {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
              """);
      HttpResponse<String> protectedCall =
          post(
              client,
              backend.mcpPort(),
              """
              {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                "name":"tracepermit.get_profile","arguments":{}}}
              """);
      HttpResponse<String> system =
          get(client, backend.controlPlanePort(), "/api/v1/system-info");

      assertThat(health.statusCode()).isEqualTo(200);
      assertThat(health.body())
          .contains("simulated-oauth-loopback", "\"tokenIssuerPresent\":false");
      assertThat(token.statusCode()).isEqualTo(404);
      assertThat(catalog.statusCode()).isEqualTo(200);
      assertThat(catalog.body())
          .contains(
              "aws.logs.filter_log_events",
              "aws.ec2.terminate_instances",
              "tracepermit.get_profile",
              "securitySchemes")
          .doesNotContain("tenant:demo", "enterprise-example");
      assertThat(protectedCall.statusCode()).isEqualTo(200);
      assertThat(protectedCall.body())
          .contains("mcp/www_authenticate", "insufficient_scope", "error_description")
          .doesNotContain("structuredContent");
      assertThat(system.statusCode()).isEqualTo(200);
      assertThat(system.body()).contains("simulated-oauth-loopback", "\"simulated\":true");
    }
  }

  static Map<String, String> environment() {
    Map<String, String> environment = new HashMap<>();
    environment.put("TRACEPERMIT_REPOSITORY_ROOT", ".");
    environment.put("TRACEPERMIT_OAUTH_ISSUER", "https://identity.example.invalid");
    environment.put("TRACEPERMIT_OAUTH_RESOURCE", "https://mcp.example.invalid");
    environment.put(
        "TRACEPERMIT_OAUTH_RESOURCE_METADATA_URI",
        "https://mcp.example.invalid/.well-known/oauth-protected-resource");
    environment.put(
        "TRACEPERMIT_OAUTH_AUTHORIZATION_SERVER", "https://identity.example.invalid");
    environment.put(
        "TRACEPERMIT_OAUTH_JWKS_URI", "https://identity.example.invalid/oauth2/jwks.json");
    environment.put("TRACEPERMIT_OAUTH_CLIENT_ID", "https://chatgpt.com/oauth/client.json");
    environment.put("TRACEPERMIT_OAUTH_TENANT_VALUE", "enterprise-example");
    environment.put("TRACEPERMIT_OAUTH_TENANT_ID", "tenant:demo");
    environment.put("TRACEPERMIT_OAUTH_MCP_PORT", "0");
    environment.put("TRACEPERMIT_OAUTH_API_PORT", "0");
    return environment;
  }

  private static HttpResponse<String> get(HttpClient client, int port, String path)
      throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> post(HttpClient client, int port, String body)
      throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
