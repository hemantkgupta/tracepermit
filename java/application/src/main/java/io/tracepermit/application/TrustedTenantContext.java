package io.tracepermit.application;

import io.tracepermit.execution.TenantPartition;

import java.util.Objects;

/** Authenticated application context kept separate from transport request bodies. */
public record TrustedTenantContext(TenantPartition tenant) {
  public TrustedTenantContext {
    Objects.requireNonNull(tenant);
  }
}
