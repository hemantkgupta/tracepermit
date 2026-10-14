package io.tracepermit.execution;

import java.util.Objects;

/** Tenant identity supplied by an authenticated application boundary, never a request body. */
public record TenantPartition(String value) {
  public TenantPartition {
    Objects.requireNonNull(value);
    if (!value.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")) {
      throw new IllegalArgumentException("The trusted tenant identifier is invalid.");
    }
  }
}
