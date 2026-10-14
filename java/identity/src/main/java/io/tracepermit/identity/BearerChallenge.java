package io.tracepermit.identity;

import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public final class BearerChallenge {
  private static final Pattern SAFE_ERROR = Pattern.compile("^[a-z_]{1,64}$");

  private BearerChallenge() {}

  public static String missingToken(URI resourceMetadata, List<ConnectorScope> requiredScopes) {
    return render(resourceMetadata, requiredScopes, null, null);
  }

  public static String invalidToken(URI resourceMetadata, List<ConnectorScope> requiredScopes) {
    return render(resourceMetadata, requiredScopes, "invalid_token", null);
  }

  public static String toolAuthenticationRequired(
      URI resourceMetadata, List<ConnectorScope> requiredScopes) {
    return render(
        resourceMetadata,
        requiredScopes,
        "insufficient_scope",
        "Authentication is required to use this tool.");
  }

  public static String toolInvalidToken(
      URI resourceMetadata, List<ConnectorScope> requiredScopes) {
    return render(
        resourceMetadata,
        requiredScopes,
        "invalid_token",
        "The access token is invalid or expired.");
  }

  private static String render(
      URI resourceMetadata,
      List<ConnectorScope> requiredScopes,
      String error,
      String errorDescription) {
    requireSafeMetadataUri(resourceMetadata);
    Objects.requireNonNull(requiredScopes);
    String scopes =
        requiredScopes.stream()
            .distinct()
            .sorted(Comparator.comparing(ConnectorScope::value))
            .map(ConnectorScope::value)
            .reduce((left, right) -> left + " " + right)
            .orElseThrow(() -> new IllegalArgumentException("requiredScopes must not be empty."));
    StringBuilder result =
        new StringBuilder("Bearer resource_metadata=\"")
            .append(resourceMetadata)
            .append("\", scope=\"")
            .append(scopes)
            .append('"');
    if (error != null) {
      if (!SAFE_ERROR.matcher(error).matches()) throw new IllegalArgumentException("error is invalid.");
      result.append(", error=\"").append(error).append('"');
    }
    if (errorDescription != null) {
      result.append(", error_description=\"").append(errorDescription).append('"');
    }
    return result.toString();
  }

  private static void requireSafeMetadataUri(URI value) {
    Objects.requireNonNull(value);
    if (!"https".equals(value.getScheme())
        || value.getHost() == null
        || value.getUserInfo() != null
        || value.getQuery() != null
        || value.getFragment() != null) {
      throw new IllegalArgumentException("resourceMetadata must be a safe HTTPS URI.");
    }
  }
}
