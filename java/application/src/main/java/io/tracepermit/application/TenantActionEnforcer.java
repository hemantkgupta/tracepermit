package io.tracepermit.application;

import com.fasterxml.jackson.databind.JsonNode;

/** Application boundary that keeps authenticated tenant context separate from action JSON. */
@FunctionalInterface
public interface TenantActionEnforcer {
  ExecutionResult execute(TrustedTenantContext context, JsonNode event);
}
