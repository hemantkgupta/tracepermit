package io.tracepermit.identity;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public record OidcProviderConfiguration(
    URI issuer,
    String resourceAudience,
    URI jwksUri,
    URI resourceMetadataUri,
    List<URI> authorizationServers,
    String resourceName,
    Map<String, String> clientNames,
    String tenantClaim,
    Map<String, String> tenantMappings,
    Set<ConnectorScope> supportedScopes,
    ConnectorOrigin origin,
    Duration maximumTokenLifetime,
    Duration jwksCacheLifetime,
    Duration forcedRefreshCooldown,
    OidcTokenProfile tokenProfile) {

  public OidcProviderConfiguration(URI issuer, String resourceAudience, URI jwksUri,
      URI resourceMetadataUri, List<URI> authorizationServers, String resourceName,
      Map<String, String> clientNames, String tenantClaim, Map<String, String> tenantMappings,
      Set<ConnectorScope> supportedScopes, ConnectorOrigin origin, Duration maximumTokenLifetime,
      Duration jwksCacheLifetime, Duration forcedRefreshCooldown) {
    this(issuer, resourceAudience, jwksUri, resourceMetadataUri, authorizationServers,
        resourceName, clientNames, tenantClaim, tenantMappings, supportedScopes, origin,
        maximumTokenLifetime, jwksCacheLifetime, forcedRefreshCooldown, OidcTokenProfile.standard());
  }

  private static final Pattern CLAIM_NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_.-]{0,63}$");
  private static final Duration MAXIMUM_CACHE_LIFETIME = Duration.ofMinutes(15);
  private static final Duration MAXIMUM_TOKEN_LIFETIME = Duration.ofMinutes(15);

  public OidcProviderConfiguration {
    issuer = requireHttpsUri(issuer, "issuer");
    resourceAudience = nonEmpty(resourceAudience, "resourceAudience", 512);
    requireHttpsUri(URI.create(resourceAudience), "resourceAudience");
    jwksUri = requireHttpsUri(jwksUri, "jwksUri");
    resourceMetadataUri = requireHttpsUri(resourceMetadataUri, "resourceMetadataUri");
    authorizationServers = List.copyOf(authorizationServers);
    if (authorizationServers.isEmpty()) {
      throw new IllegalArgumentException("authorizationServers must not be empty.");
    }
    if (Set.copyOf(authorizationServers).size() != authorizationServers.size()) {
      throw new IllegalArgumentException("authorizationServers must be unique.");
    }
    authorizationServers.forEach(value -> requireHttpsUri(value, "authorizationServer"));
    resourceName = nonEmpty(resourceName, "resourceName", 128);
    clientNames = Map.copyOf(clientNames);
    if (clientNames.isEmpty()) throw new IllegalArgumentException("clientNames must not be empty.");
    clientNames.forEach(
        (clientId, clientName) -> {
          nonEmpty(clientId, "clientId", 256);
          nonEmpty(clientName, "clientName", 128);
        });
    tenantClaim = claimName(tenantClaim);
    Objects.requireNonNull(tokenProfile).validateTenantClaim(tenantClaim);
    if (tokenProfile.isOkta() && clientNames.containsKey(resourceAudience)) {
      throw new IllegalArgumentException("API audience and client audience must be distinct.");
    }
    tenantMappings = Map.copyOf(tenantMappings);
    if (tenantMappings.isEmpty()) {
      throw new IllegalArgumentException("tenantMappings must not be empty.");
    }
    tenantMappings.forEach(
        (externalTenant, tenantId) -> {
          nonEmpty(externalTenant, "externalTenant", 256);
          nonEmpty(tenantId, "tenantId", 128);
        });
    supportedScopes = Set.copyOf(supportedScopes);
    if (supportedScopes.isEmpty()) {
      throw new IllegalArgumentException("supportedScopes must not be empty.");
    }
    Objects.requireNonNull(origin);
    maximumTokenLifetime = boundedPositive(maximumTokenLifetime, MAXIMUM_TOKEN_LIFETIME, "maximumTokenLifetime");
    jwksCacheLifetime = boundedPositive(jwksCacheLifetime, MAXIMUM_CACHE_LIFETIME, "jwksCacheLifetime");
    forcedRefreshCooldown = boundedPositive(forcedRefreshCooldown, jwksCacheLifetime, "forcedRefreshCooldown");
  }

  public String clientName(String clientId) {
    return clientNames.get(clientId);
  }

  public String tenantId(String externalTenant) {
    return tenantMappings.get(externalTenant);
  }

  private static URI requireHttpsUri(URI value, String field) {
    Objects.requireNonNull(value);
    if (!"https".equals(value.getScheme())
        || value.getHost() == null
        || value.getHost().isEmpty()
        || value.getUserInfo() != null
        || value.getQuery() != null
        || value.getFragment() != null) {
      throw new IllegalArgumentException(field + " must be an absolute HTTPS URI without userinfo, query, or fragment.");
    }
    return value;
  }

  private static String nonEmpty(String value, String field, int maximumLength) {
    Objects.requireNonNull(value);
    if (value.isEmpty() || value.length() > maximumLength || value.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(field + " is invalid.");
    }
    return value;
  }

  private static String claimName(String value) {
    String bounded = nonEmpty(value, "tenantClaim", 256);
    if (CLAIM_NAME.matcher(bounded).matches()) return bounded;
    try {
      URI namespaced = URI.create(bounded);
      if ("https".equals(namespaced.getScheme())
          && namespaced.getHost() != null
          && !namespaced.getHost().isEmpty()
          && namespaced.getUserInfo() == null
          && namespaced.getQuery() == null
          && namespaced.getFragment() == null) {
        return bounded;
      }
    } catch (IllegalArgumentException ignored) {
      // Converted to the stable configuration error below.
    }
    throw new IllegalArgumentException("tenantClaim is invalid.");
  }

  private static Duration boundedPositive(Duration value, Duration maximum, String field) {
    Objects.requireNonNull(value);
    if (value.isZero() || value.isNegative() || value.compareTo(maximum) > 0) {
      throw new IllegalArgumentException(field + " is outside its safe bound.");
    }
    return value;
  }
}
