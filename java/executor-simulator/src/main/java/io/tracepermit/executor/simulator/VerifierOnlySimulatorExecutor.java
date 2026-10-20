package io.tracepermit.executor.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.application.SimulatedTarget;
import io.tracepermit.contracts.ActionValidator;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.TenantPartition;
import io.tracepermit.security.ExecutionPermitAuthority;

import java.util.List;
import java.util.Objects;

/**
 * Verifier-only execution boundary for deterministic simulator targets.
 *
 * <p>The service cannot issue permits. It validates a canonical action, atomically consumes the
 * request-bound permit, rechecks an operator-owned route immediately before dispatch, and returns
 * a bounded receipt instead of the raw target result.
 */
public final class VerifierOnlySimulatorExecutor {
  private final ExecutionPermitAuthority permits;
  private final String audience;
  private final List<ExecutorRoute> routes;
  private final SimulatedTarget target;

  VerifierOnlySimulatorExecutor(
      ExecutionPermitAuthority permits,
      String audience,
      List<ExecutorRoute> routes,
      SimulatedTarget target) {
    this.permits = Objects.requireNonNull(permits, "permits");
    if (!permits.isVerifierOnly()) {
      throw new IllegalArgumentException("EXECUTOR_REQUIRES_VERIFIER_ONLY_AUTHORITY");
    }
    this.audience = requireText(audience, "audience");
    this.routes = List.copyOf(Objects.requireNonNull(routes, "routes"));
    if (this.routes.isEmpty()) {
      throw new IllegalArgumentException("EXECUTOR_REQUIRES_ALLOWLIST");
    }
    this.target = Objects.requireNonNull(target, "target");
  }

  public JsonNode execute(String permit, JsonNode suppliedEvent) {
    JsonNode event = ActionValidator.validate(suppliedEvent);
    if (!event.equals(suppliedEvent)) {
      throw new IllegalArgumentException("NON_CANONICAL_EXECUTOR_ACTION");
    }
    ExecutorRoute route = allowedRoute(event);
    String tenantId = new TenantPartition(
        event.path("identity").path("subject").path("organizationId").asText()).value();
    JsonNode claims = permits.verifyAndConsume(tenantId, permit, event, audience);

    // Re-evaluate the fixed route immediately before target invocation. No request field can
    // replace the configured issuer, audience, verification key, or allowlist.
    if (!route.allows(event)) {
      throw new IllegalStateException("EXECUTOR_ROUTE_CHANGED");
    }
    JsonNode targetResult = target.invoke(event.path("action").deepCopy());
    return receipt(event, claims, targetResult);
  }

  private ExecutorRoute allowedRoute(JsonNode event) {
    return routes.stream()
        .filter(route -> route.allows(event))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("EXECUTOR_ROUTE_NOT_ALLOWED"));
  }

  private static JsonNode receipt(JsonNode event, JsonNode claims, JsonNode targetResult) {
    ObjectNode receipt = JsonSupport.MAPPER.createObjectNode();
    receipt.put("schemaVersion", "1.0");
    receipt.put("status", "succeeded");
    receipt.put("mode", "simulated");
    receipt.put("eventId", event.path("eventId").asText());
    receipt.put("permitId", claims.path("permitId").asText());
    receipt.put("system", event.path("action").path("target").path("system").asText());
    receipt.put("operation", event.path("action").path("toolName").asText());
    receipt.put("resultHash", CanonicalJson.sha256(targetResult));
    return receipt;
  }

  private static String requireText(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Missing " + label);
    }
    return value;
  }
}
