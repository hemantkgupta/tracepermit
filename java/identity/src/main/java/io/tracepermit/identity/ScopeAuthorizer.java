package io.tracepermit.identity;

import java.util.Objects;

public final class ScopeAuthorizer {
  private ScopeAuthorizer() {}

  public static boolean isAllowed(AuthenticatedConnector connector, ConnectorScope required) {
    return Objects.requireNonNull(connector).hasScope(Objects.requireNonNull(required));
  }

  public static void require(AuthenticatedConnector connector, ConnectorScope required) {
    if (!isAllowed(connector, required)) {
      throw new AuthenticationException(
          "AUTHORIZATION_DENIED", "The authenticated connector is not authorized for this operation.");
    }
  }
}
