package io.tracepermit.adapters.http;

import io.tracepermit.identity.ConnectorOrigin;
import io.tracepermit.identity.ConnectorScope;
import io.tracepermit.identity.OidcProviderConfiguration;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Operator-owned configuration for the simulator-only hosted-client OAuth profile. */
public record HostedClientOAuthConfiguration(
    Path repositoryRoot,
    int mcpPort,
    int controlPlanePort,
    URI resourceMetadataUri,
    OidcProviderConfiguration provider) {

  private static final Set<ConnectorScope> REQUIRED_TOOL_SCOPES =
      Set.of(ConnectorScope.of("aws.logs.discover"), ConnectorScope.of("aws.logs.read"));

  public HostedClientOAuthConfiguration {
    repositoryRoot = repositoryRoot.toAbsolutePath().normalize();
    if (mcpPort < 0 || mcpPort > 65_535 || controlPlanePort < 0 || controlPlanePort > 65_535) {
      throw new IllegalArgumentException("OAuth profile ports must be valid TCP ports.");
    }
    if (mcpPort != 0 && mcpPort == controlPlanePort) {
      throw new IllegalArgumentException("OAuth MCP and control-plane ports must be distinct.");
    }
    if (!resourceMetadataUri.equals(provider.resourceMetadataUri())) {
      throw new IllegalArgumentException("OAuth metadata configuration must have one authority.");
    }
    if (!provider.supportedScopes().containsAll(REQUIRED_TOOL_SCOPES)) {
      throw new IllegalArgumentException("OAuth profile omits required TracePermit tool scopes.");
    }
  }

  public static HostedClientOAuthConfiguration from(Map<String, String> environment) {
    String issuer = required(environment, "TRACEPERMIT_OAUTH_ISSUER", 512);
    String resource = required(environment, "TRACEPERMIT_OAUTH_RESOURCE", 512);
    String metadata = required(environment, "TRACEPERMIT_OAUTH_RESOURCE_METADATA_URI", 768);
    String authorizationServer =
        required(environment, "TRACEPERMIT_OAUTH_AUTHORIZATION_SERVER", 512);
    String jwks = required(environment, "TRACEPERMIT_OAUTH_JWKS_URI", 768);
    String clientId = required(environment, "TRACEPERMIT_OAUTH_CLIENT_ID", 256);
    String externalTenant = required(environment, "TRACEPERMIT_OAUTH_TENANT_VALUE", 256);
    String tenantId = required(environment, "TRACEPERMIT_OAUTH_TENANT_ID", 128);
    String tenantClaim =
        optional(environment, "TRACEPERMIT_OAUTH_TENANT_CLAIM", "tracepermit_tenant", 256);
    String clientName =
        optional(environment, "TRACEPERMIT_OAUTH_CLIENT_NAME", "ChatGPT hosted client", 128);
    ConnectorOrigin origin =
        origin(optional(environment, "TRACEPERMIT_OAUTH_ORIGIN", "chatgpt-web", 32));
    Set<ConnectorScope> scopes =
        scopes(
            optional(
                environment,
                "TRACEPERMIT_OAUTH_SCOPES",
                "openid profile email offline_access aws.logs.discover aws.logs.read",
                512));
    URI metadataUri = URI.create(metadata);
    OidcProviderConfiguration provider =
        new OidcProviderConfiguration(
            URI.create(issuer),
            resource,
            URI.create(jwks),
            metadataUri,
            List.of(URI.create(authorizationServer)),
            "TracePermit simulator MCP",
            Map.of(clientId, clientName),
            tenantClaim,
            Map.of(externalTenant, tenantId),
            scopes,
            origin,
            Duration.ofMinutes(15),
            Duration.ofMinutes(1),
            Duration.ofSeconds(5));
    return new HostedClientOAuthConfiguration(
        repositoryRoot(environment),
        port(environment, "TRACEPERMIT_OAUTH_MCP_PORT", 8080),
        port(environment, "TRACEPERMIT_OAUTH_API_PORT", 8090),
        metadataUri,
        provider);
  }

  public List<ConnectorScope> requiredToolScopes() {
    return REQUIRED_TOOL_SCOPES.stream()
        .sorted(java.util.Comparator.comparing(ConnectorScope::value))
        .toList();
  }

  private static ConnectorOrigin origin(String value) {
    return switch (value) {
      case "chatgpt-web" -> ConnectorOrigin.CHATGPT_WEB;
      case "claude-web" -> ConnectorOrigin.CLAUDE_WEB;
      default -> throw new IllegalArgumentException("TRACEPERMIT_OAUTH_ORIGIN is invalid.");
    };
  }

  private static Set<ConnectorScope> scopes(String value) {
    if (value.startsWith(" ") || value.endsWith(" ") || value.contains("  ")) {
      throw new IllegalArgumentException("TRACEPERMIT_OAUTH_SCOPES is invalid.");
    }
    List<ConnectorScope> configured =
        Arrays.stream(value.split(" ")).map(ConnectorScope::of).toList();
    Set<ConnectorScope> result = configured.stream().collect(Collectors.toUnmodifiableSet());
    if (result.isEmpty() || result.size() != configured.size()) {
      throw new IllegalArgumentException("TRACEPERMIT_OAUTH_SCOPES is invalid.");
    }
    return result;
  }

  private static Path repositoryRoot(Map<String, String> environment) {
    String configured = environment.get("TRACEPERMIT_REPOSITORY_ROOT");
    return configured == null || configured.isEmpty() ? Path.of("..").toAbsolutePath() : Path.of(configured);
  }

  private static int port(Map<String, String> environment, String name, int defaultValue) {
    try {
      int value = Integer.parseInt(environment.getOrDefault(name, Integer.toString(defaultValue)));
      if (value < 0 || value > 65_535) throw new NumberFormatException("out of range");
      return value;
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(name + " must be a valid TCP port.", exception);
    }
  }

  private static String required(
      Map<String, String> environment, String name, int maximumLength) {
    String value = environment.get(name);
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException(name + " is required for hosted-client OAuth.");
    }
    return bounded(value, name, maximumLength);
  }

  private static String optional(
      Map<String, String> environment, String name, String defaultValue, int maximumLength) {
    return bounded(environment.getOrDefault(name, defaultValue), name, maximumLength);
  }

  private static String bounded(String value, String name, int maximumLength) {
    if (value.isEmpty()
        || value.length() > maximumLength
        || value.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(name + " is invalid.");
    }
    return value;
  }
}
