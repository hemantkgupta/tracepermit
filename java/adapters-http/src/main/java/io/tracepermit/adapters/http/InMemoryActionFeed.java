package io.tracepermit.adapters.http;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class InMemoryActionFeed {
  private final Map<String, ActionFeedEntry> entries = new LinkedHashMap<>();
  private final Set<String> currentRunLatencySamples = new LinkedHashSet<>();

  public synchronized void append(ActionFeedEntry entry) {
    append(entry, true);
  }

  synchronized void appendHistoricalFixture(ActionFeedEntry entry) {
    append(entry, false);
  }

  private void append(ActionFeedEntry entry, boolean includeInCurrentRunLatency) {
    if (entries.putIfAbsent(entry.eventId(), entry) != null) {
      throw new IllegalArgumentException("An action-feed event with that ID already exists.");
    }
    if (includeInCurrentRunLatency) currentRunLatencySamples.add(entry.eventId());
  }

  public synchronized ActionFeedEntry find(String eventId) {
    return entries.get(eventId);
  }

  public synchronized ActionFeedPage list(
      String decision, String severity, String origin, String query, int limit) {
    if (limit < 1 || limit > 100) {
      throw new IllegalArgumentException("The action-feed limit must be between 1 and 100.");
    }
    String normalizedQuery = normalize(query);
    List<ActionFeedEntry> matches = entries.values().stream()
        .filter(entry -> blank(decision) || decision.equals(entry.decision().outcome()))
        .filter(entry -> blank(severity) || severity.equals(entry.risk().severity()))
        .filter(entry -> blank(origin) || origin.equals(entry.trigger().origin()))
        .filter(entry -> normalizedQuery.isEmpty() || searchable(entry).contains(normalizedQuery))
        .sorted(Comparator.comparing(ActionFeedEntry::observedAt).reversed())
        .toList();
    return new ActionFeedPage("1.0", true, matches.size(), null, matches.stream().limit(limit).toList());
  }

  public synchronized ActionFeedOverview overview() {
    List<ActionFeedEntry> values = new ArrayList<>(entries.values());
    int allowed = count(values, "allow");
    int blocked = count(values, "block");
    int sanitized = count(values, "sanitize");
    int approvals = count(values, "require_approval");
    List<Long> latencies = values.stream()
        .filter(entry -> currentRunLatencySamples.contains(entry.eventId()))
        .map(ActionFeedEntry::latencyMs)
        .sorted()
        .toList();
    long p95 = latencies.isEmpty()
        ? 0
        : latencies.get(Math.max(0, (int) Math.ceil(latencies.size() * 0.95d) - 1));
    return new ActionFeedOverview(
        "1.0",
        true,
        values.size(),
        allowed,
        blocked,
        sanitized,
        approvals,
        p95,
        latencies.size(),
        "current_run");
  }

  private static int count(List<ActionFeedEntry> values, String outcome) {
    return (int) values.stream().filter(entry -> outcome.equals(entry.decision().outcome())).count();
  }

  private static String searchable(ActionFeedEntry entry) {
    return normalize(
        String.join(
            " ",
            entry.intent(),
            entry.identity().subject().displayName(),
            entry.identity().subject().id(),
            entry.trigger().origin(),
            entry.target().system(),
            entry.target().toolName(),
            entry.target().resourceType(),
            entry.decision().outcome()));
  }

  private static String normalize(String value) {
    return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
