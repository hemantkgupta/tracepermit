package io.tracepermit.application;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import java.util.Objects;

/** Internal scope derived by a trusted adapter; construction does not authenticate a caller. */
public record BusinessOperationScope(
    String tenantId, String workspaceId, String requesterPrincipalId) {
  public BusinessOperationScope {
    require(tenantId, "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");
    require(workspaceId, "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");
    require(requesterPrincipalId, "^[A-Za-z0-9][A-Za-z0-9._:@/-]{0,255}$");
  }

  public ObjectNode trustedContext() {
    ObjectNode context = JsonSupport.MAPPER.createObjectNode();
    context.put("tenantId", tenantId);
    context.put("workspaceId", workspaceId);
    context.put("requesterPrincipalId", requesterPrincipalId);
    return context;
  }

  public String fingerprint() {
    return CanonicalJson.sha256(trustedContext());
  }

  @Override
  public String toString() {
    return "BusinessOperationScope[redacted]";
  }

  private static void require(String value, String pattern) {
    if (!Objects.requireNonNull(value).matches(pattern)) {
      throw new IllegalArgumentException("OPERATION_SCOPE_INVALID");
    }
  }
}
