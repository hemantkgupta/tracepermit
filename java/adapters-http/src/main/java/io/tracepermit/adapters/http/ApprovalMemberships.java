package io.tracepermit.adapters.http;

import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.TenantPartition;
import io.tracepermit.identity.AuthenticatedConnector;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Operator-owned enrollment, independent of token roles and all HTTP payloads. */
public final class ApprovalMemberships {
  public enum Role { REQUESTER, REVIEWER, VIEWER }

  public record Entry(
      String verifiedSubjectId, String clientId, TenantPartition tenant,
      boolean human, Set<Role> roles) {
    public Entry {
      Objects.requireNonNull(verifiedSubjectId);
      Objects.requireNonNull(clientId);
      Objects.requireNonNull(tenant);
      roles = Set.copyOf(roles);
      if (verifiedSubjectId.isBlank() || verifiedSubjectId.length() > 1024
          || clientId.isBlank() || clientId.length() > 256 || roles.isEmpty()
          || (!human && roles.contains(Role.REVIEWER))) {
        throw new IllegalArgumentException("Invalid approval membership.");
      }
    }

    public String principalId() {
      return "principal:" + CanonicalJson.sha256(
          JsonSupport.MAPPER.getNodeFactory().textNode(verifiedSubjectId)).substring(7);
    }
  }

  private record Key(String subject, String client, TenantPartition tenant) {}
  private volatile Map<Key, Entry> entries;

  public ApprovalMemberships(List<Entry> initial) { replace(initial); }

  /** Called by the trusted operator composition, never a public HTTP route. */
  public void replace(List<Entry> replacement) {
    if (replacement.size() > 1000) throw new IllegalArgumentException("Too many memberships.");
    Map<Key, Entry> next = new HashMap<>();
    for (Entry entry : replacement) {
      Key key = new Key(entry.verifiedSubjectId(), entry.clientId(), entry.tenant());
      if (next.putIfAbsent(key, entry) != null) {
        throw new IllegalArgumentException("Duplicate approval membership.");
      }
    }
    entries = Map.copyOf(next);
  }

  public Entry resolve(AuthenticatedConnector connector) {
    Entry entry = entries.get(new Key(connector.subjectId(), connector.clientId(),
        new TenantPartition(connector.organizationId())));
    if (entry == null) throw new SecurityException("MEMBERSHIP_REQUIRED");
    return entry;
  }

  public boolean isActiveReviewer(TenantPartition tenant, String principalId) {
    return entries.values().stream().anyMatch(entry -> entry.tenant().equals(tenant)
        && entry.human() && entry.roles().contains(Role.REVIEWER)
        && entry.principalId().equals(principalId));
  }
}
