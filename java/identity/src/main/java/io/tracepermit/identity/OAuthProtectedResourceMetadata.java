package io.tracepermit.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.JsonSupport;
import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record OAuthProtectedResourceMetadata(
    URI resource,
    String resourceName,
    List<URI> authorizationServers,
    List<ConnectorScope> scopesSupported,
    List<String> bearerMethodsSupported) {

  public OAuthProtectedResourceMetadata {
    requireHttps(resource, "resource");
    resourceName = boundedText(resourceName, "resourceName");
    authorizationServers = List.copyOf(authorizationServers);
    if (authorizationServers.isEmpty()) {
      throw new IllegalArgumentException("authorizationServers must not be empty.");
    }
    authorizationServers.forEach(value -> requireHttps(value, "authorizationServer"));
    scopesSupported = scopesSupported.stream().distinct().sorted(Comparator.comparing(ConnectorScope::value)).toList();
    if (scopesSupported.isEmpty()) throw new IllegalArgumentException("scopesSupported must not be empty.");
    bearerMethodsSupported = List.copyOf(bearerMethodsSupported);
    if (!bearerMethodsSupported.equals(List.of("header"))) {
      throw new IllegalArgumentException("Only the Authorization header bearer method is supported.");
    }
  }

  public static OAuthProtectedResourceMetadata from(OidcProviderConfiguration provider) {
    Objects.requireNonNull(provider);
    return new OAuthProtectedResourceMetadata(
        URI.create(provider.resourceAudience()),
        provider.resourceName(),
        provider.authorizationServers(),
        provider.supportedScopes().stream().toList(),
        List.of("header"));
  }

  public JsonNode toJson() {
    ObjectNode result = JsonSupport.MAPPER.createObjectNode();
    result.put("resource", resource.toString());
    result.put("resource_name", resourceName);
    ArrayNode servers = result.putArray("authorization_servers");
    authorizationServers.forEach(value -> servers.add(value.toString()));
    ArrayNode scopes = result.putArray("scopes_supported");
    scopesSupported.forEach(value -> scopes.add(value.value()));
    ArrayNode methods = result.putArray("bearer_methods_supported");
    bearerMethodsSupported.forEach(methods::add);
    return result;
  }

  private static void requireHttps(URI value, String field) {
    Objects.requireNonNull(value);
    if (!"https".equals(value.getScheme())
        || value.getHost() == null
        || value.getUserInfo() != null
        || value.getQuery() != null
        || value.getFragment() != null) {
      throw new IllegalArgumentException(field + " must be a safe HTTPS URI.");
    }
  }

  private static String boundedText(String value, String field) {
    Objects.requireNonNull(value);
    if (value.isEmpty() || value.length() > 128 || value.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(field + " is invalid.");
    }
    return value;
  }
}
