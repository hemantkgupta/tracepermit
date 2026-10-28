package io.tracepermit.adapters.remote.agentcore;

import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.identity.AuthenticatedConnector;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Exact operator-owned reviewer roster; token group claims can only attenuate this enrollment. */
public final class OktaOperationReviewerRoster {
  public static final String FORMAT = "tracepermit-installed-operation-reviewer-roster-v1";
  private static final Duration MAXIMUM_MEMBERSHIP_AGE = Duration.ofMinutes(5);

  private record Key(String subject, String client, String tenant, String workspace) {}

  public record Entry(
      String verifiedSubjectId,
      String clientId,
      String tenantId,
      String workspaceId,
      String groupId,
      boolean human) {
    public Entry {
      require(verifiedSubjectId, "[A-Za-z0-9][A-Za-z0-9._:@/+\\-=|#]{0,1023}");
      require(clientId, "[A-Za-z0-9][A-Za-z0-9._:@/+\\-=]{0,255}");
      require(tenantId, "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
      require(workspaceId, "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
      require(groupId, "[A-Za-z0-9][A-Za-z0-9._:@/+\\-]{0,255}");
      if (!human) throw invalidConfiguration();
    }

    public String principalId() {
      String hash =
          CanonicalJson.sha256(JsonSupport.MAPPER.getNodeFactory().textNode(verifiedSubjectId));
      return "principal:" + hash.substring("sha256:".length());
    }

    private static void require(String value, String pattern) {
      if (value == null || !value.matches(pattern)) throw invalidConfiguration();
    }
  }

  record Resolved(Entry entry, Instant observedAt, Instant expiresAt) {}

  private final Map<Key, Entry> entries;
  private final String fingerprint;

  public OktaOperationReviewerRoster(List<Entry> input) {
    if (input == null
        || input.isEmpty()
        || input.size() > 32
        || input.stream().anyMatch(java.util.Objects::isNull)) {
      throw invalidConfiguration();
    }
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
    var material = JsonSupport.MAPPER.createObjectNode().put("format", FORMAT);
    var rows = material.putArray("memberships");
    for (Entry entry : sorted) {
      Key key =
          new Key(
              entry.verifiedSubjectId(),
              entry.clientId(),
              entry.tenantId(),
              entry.workspaceId());
      if (indexed.putIfAbsent(key, entry) != null) throw invalidConfiguration();
      rows.addObject()
          .put("verifiedSubjectId", entry.verifiedSubjectId())
          .put("clientId", entry.clientId())
          .put("tenantId", entry.tenantId())
          .put("workspaceId", entry.workspaceId())
          .put("groupId", entry.groupId())
          .put("human", true);
    }
    entries = Map.copyOf(indexed);
    fingerprint = CanonicalJson.sha256(material);
  }

  public String fingerprint() {
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
      throw invalidConfiguration();
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
    if (entry == null || !entry.human()) throw rejected();
    Instant observed = connector.verifiedAt();
    Instant maximum = observed.plus(MAXIMUM_MEMBERSHIP_AGE);
    Instant expiry = connector.expiresAt().isBefore(maximum) ? connector.expiresAt() : maximum;
    if (!expiry.isAfter(observed)) throw rejected();
    return new Resolved(entry, observed, expiry);
  }

  private static OktaOperationAuthorityException invalidConfiguration() {
    return new OktaOperationAuthorityException("OKTA_AUTHORITY_CONFIGURATION_INVALID");
  }

  static OktaOperationAuthorityException rejected() {
    return new OktaOperationAuthorityException("OKTA_AUTHORITY_REJECTED");
  }

  @Override
  public String toString() {
    return "OktaOperationReviewerRoster[redacted]";
  }
}
