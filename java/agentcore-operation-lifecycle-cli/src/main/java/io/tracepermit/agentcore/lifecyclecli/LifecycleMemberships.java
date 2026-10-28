package io.tracepermit.agentcore.lifecyclecli;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.identity.AuthenticatedConnector;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Exact operator-owned human roster, independent of token role claims and command JSON. */
final class LifecycleMemberships {
  private record Key(String subject, String client, String tenant, String workspace) {}

  record Entry(
      String verifiedSubjectId,
      String clientId,
      String tenantId,
      String workspaceId,
      String groupId,
      boolean human) {
    Entry {
      require(verifiedSubjectId, "[A-Za-z0-9][A-Za-z0-9._:@/+\\-=|#]{0,1023}");
      require(clientId, "[A-Za-z0-9][A-Za-z0-9._:@/+\\-=]{0,255}");
      require(tenantId, "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
      require(workspaceId, "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
      require(groupId, "[A-Za-z0-9][A-Za-z0-9._:@/+\\-]{0,255}");
      if (!human) throw new LifecycleFailure("CONFIG_INVALID");
    }

    String principalId() {
      String hash =
          CanonicalJson.sha256(
              JsonSupport.MAPPER.getNodeFactory().textNode(verifiedSubjectId));
      return "principal:" + hash.substring("sha256:".length());
    }

    private static void require(String value, String pattern) {
      if (value == null || !value.matches(pattern)) {
        throw new LifecycleFailure("CONFIG_INVALID");
      }
    }
  }

  record Resolved(Entry entry, Instant observedAt, Instant expiresAt) {}

  private final Map<Key, Entry> entries;
  private final String fingerprint;

  private LifecycleMemberships(List<Entry> input) {
    if (input.isEmpty() || input.size() > 32) throw new LifecycleFailure("CONFIG_INVALID");
    List<Entry> sorted =
        input.stream()
            .sorted(
                Comparator.comparing(Entry::verifiedSubjectId)
                    .thenComparing(Entry::clientId)
                    .thenComparing(Entry::tenantId)
                    .thenComparing(Entry::workspaceId)
                    .thenComparing(Entry::groupId))
            .toList();
    Map<Key, Entry> indexed = new HashMap<>();
    var material = JsonSupport.MAPPER.createObjectNode();
    material.put("format", "tracepermit-installed-operation-reviewer-roster-v1");
    var rows = material.putArray("memberships");
    for (Entry entry : sorted) {
      Key key =
          new Key(
              entry.verifiedSubjectId(),
              entry.clientId(),
              entry.tenantId(),
              entry.workspaceId());
      if (indexed.putIfAbsent(key, entry) != null) {
        throw new LifecycleFailure("CONFIG_INVALID");
      }
      rows.addObject()
          .put("verifiedSubjectId", entry.verifiedSubjectId())
          .put("clientId", entry.clientId())
          .put("tenantId", entry.tenantId())
          .put("workspaceId", entry.workspaceId())
          .put("groupId", entry.groupId())
          .put("human", entry.human());
    }
    entries = Map.copyOf(indexed);
    fingerprint = CanonicalJson.sha256(material);
  }

  static LifecycleMemberships parse(JsonNode value) {
    if (value == null || !value.isArray() || value.isEmpty() || value.size() > 32) {
      throw new LifecycleFailure("CONFIG_INVALID");
    }
    List<Entry> entries = new ArrayList<>();
    for (JsonNode row : value) {
      LifecycleProtocol.fields(
          row,
          java.util.Set.of(
              "verifiedSubjectId", "clientId", "tenantId", "workspaceId", "groupId", "human"),
          java.util.Set.of());
      if (!row.path("human").isBoolean()) throw new LifecycleFailure("CONFIG_INVALID");
      entries.add(
          new Entry(
              LifecycleProtocol.text(row, "verifiedSubjectId", 1024),
              LifecycleProtocol.text(row, "clientId", 256),
              LifecycleProtocol.text(row, "tenantId", 128),
              LifecycleProtocol.text(row, "workspaceId", 128),
              LifecycleProtocol.text(row, "groupId", 256),
              row.path("human").booleanValue()));
    }
    return new LifecycleMemberships(entries);
  }

  String fingerprint() {
    return fingerprint;
  }

  void requireProfile(String tenant, String workspace, String client, String group) {
    if (entries.values().stream()
        .anyMatch(
            entry ->
                !entry.tenantId().equals(tenant)
                    || !entry.workspaceId().equals(workspace)
                    || !entry.clientId().equals(client)
                    || !entry.groupId().equals(group))) {
      throw new LifecycleFailure("CONFIG_INVALID");
    }
  }

  Resolved resolve(AuthenticatedConnector connector, String workspace) {
    Entry entry =
        entries.get(
            new Key(
                connector.subjectId(),
                connector.clientId(),
                connector.organizationId(),
                workspace));
    if (entry == null || !entry.human()) throw new LifecycleFailure("REJECTED");
    Instant observed = connector.verifiedAt();
    Instant maximum = observed.plus(Duration.ofMinutes(5));
    Instant expiry = connector.expiresAt().isBefore(maximum) ? connector.expiresAt() : maximum;
    if (!expiry.isAfter(observed)) throw new LifecycleFailure("REJECTED");
    return new Resolved(entry, observed, expiry);
  }

  @Override
  public String toString() {
    return "LifecycleMemberships[redacted]";
  }
}
