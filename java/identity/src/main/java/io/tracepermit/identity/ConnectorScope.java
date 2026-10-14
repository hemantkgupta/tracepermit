package io.tracepermit.identity;

import java.util.Objects;

public record ConnectorScope(String value) {
  public ConnectorScope {
    Objects.requireNonNull(value);
    if (value.isEmpty()) throw new IllegalArgumentException("Connector scopes must not be empty.");
  }

  public static ConnectorScope of(String value) {
    return new ConnectorScope(value);
  }
}
