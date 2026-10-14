package io.tracepermit.adapters.remote;

import io.tracepermit.identity.ConnectorScope;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public record RemoteOAuthEndpointConfiguration(
    Set<String> allowedHostnames,
    Set<URI> allowedOrigins,
    boolean allowMissingOrigin,
    URI resourceMetadataUri,
    List<ConnectorScope> requiredScopes,
    int maximumRequestBytes) {
  private static final Pattern DNS_HOST =
      Pattern.compile("^[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?$");

  public RemoteOAuthEndpointConfiguration {
    allowedHostnames = Set.copyOf(allowedHostnames);
    if (allowedHostnames.isEmpty()) {
      throw new IllegalArgumentException("allowedHostnames must not be empty.");
    }
    for (String hostname : allowedHostnames) {
      if (!DNS_HOST.matcher(hostname).matches()
          || !hostname.equals(hostname.toLowerCase(Locale.ROOT))
          || hostname.endsWith(".")) {
        throw new IllegalArgumentException("allowedHostnames contains an unsafe hostname.");
      }
    }
    allowedOrigins = Set.copyOf(allowedOrigins);
    for (URI origin : allowedOrigins) requireOrigin(origin);
    resourceMetadataUri = requireHttps(resourceMetadataUri, "resourceMetadataUri");
    requiredScopes = List.copyOf(requiredScopes);
    if (requiredScopes.isEmpty() || Set.copyOf(requiredScopes).size() != requiredScopes.size()) {
      throw new IllegalArgumentException("requiredScopes must be non-empty and unique.");
    }
    if (maximumRequestBytes < 1_024 || maximumRequestBytes > 65_536) {
      throw new IllegalArgumentException("maximumRequestBytes is outside its safe bound.");
    }
  }

  private static void requireOrigin(URI value) {
    requireHttps(value, "allowedOrigin");
    if (!value.getPath().isEmpty()) {
      throw new IllegalArgumentException("allowedOrigin must not include a path.");
    }
  }

  private static URI requireHttps(URI value, String field) {
    Objects.requireNonNull(value);
    if (!"https".equals(value.getScheme())
        || value.getHost() == null
        || value.getHost().isEmpty()
        || value.getUserInfo() != null
        || value.getQuery() != null
        || value.getFragment() != null) {
      throw new IllegalArgumentException(field + " must be an absolute HTTPS URI.");
    }
    return value;
  }
}
