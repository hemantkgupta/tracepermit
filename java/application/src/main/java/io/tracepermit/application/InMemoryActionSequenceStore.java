package io.tracepermit.application;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.policy.Classification;
import io.tracepermit.policy.ClassificationContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded development store; production requires shared tenant-partitioned state. */
public final class InMemoryActionSequenceStore implements ActionSequenceStore {
  public static final String RAPID_READ = "RAPID_REPEATED_READ_SEQUENCE";
  public static final String READ_THEN_DESTRUCTIVE = "READ_THEN_DESTRUCTIVE_SEQUENCE";
  public static final String PII_READ_THEN_EXTERNAL_SEND = "SYNTHETIC_PII_READ_THEN_EXTERNAL_SEND";
  private static final int MAX_KEYS = 1_024;
  private static final int MAX_OBSERVATIONS_PER_KEY = 32;

  private final Duration window;
  private final Map<String, Deque<Observation>> observations = new LinkedHashMap<>();
  private final Map<String, Instant> syntheticPiiReads = new LinkedHashMap<>();

  public InMemoryActionSequenceStore(Duration window) {
    this.window = Objects.requireNonNull(window);
    if (window.isZero() || window.isNegative() || window.compareTo(Duration.ofMinutes(10)) > 0) {
      throw new IllegalArgumentException("The sequence window is invalid.");
    }
  }

  public static InMemoryActionSequenceStore oneMinute() {
    return new InMemoryActionSequenceStore(Duration.ofMinutes(1));
  }

  @Override
  public synchronized ClassificationContext contextFor(JsonNode event, Instant now) {
    Objects.requireNonNull(event);
    Objects.requireNonNull(now);
    Deque<Observation> recent = observations.get(key(event));
    if (recent != null) prune(recent, now);
    String tool = event.path("action").path("toolName").asText();
    List<String> signals = new ArrayList<>();
    int adjustment = 0;
    if ("aws.logs.filter_log_events".equals(tool)
        && recent != null
        && recent.stream().filter(item -> "read".equals(item.actionClass())).count() >= 2) {
      signals.add(RAPID_READ);
      adjustment += 50;
    }
    if (destructive(tool)
        && recent != null
        && recent.stream().anyMatch(item -> "read".equals(item.actionClass()))) {
      signals.add(READ_THEN_DESTRUCTIVE);
      adjustment += 5;
    }
    if ("demo.egress.send_external".equals(tool)
        && "synthetic-egress".equals(event.path("action").path("target").path("system").asText())) {
      Instant prior = syntheticPiiReads.get(principalKey(event));
      if (prior != null && !prior.isAfter(now) && !prior.isBefore(now.minus(window))) {
        signals.add(PII_READ_THEN_EXTERNAL_SEND);
        adjustment += 45;
      }
    }
    return signals.isEmpty()
        ? ClassificationContext.EMPTY
        : new ClassificationContext(signals, Math.min(adjustment, 100));
  }

  @Override
  public synchronized void record(
      JsonNode event, Classification classification, Instant now) {
    Objects.requireNonNull(event);
    Objects.requireNonNull(classification);
    Objects.requireNonNull(now);
    String key = key(event);
    Deque<Observation> recent = observations.computeIfAbsent(key, ignored -> new ArrayDeque<>());
    prune(recent, now);
    recent.addLast(new Observation(now, classification.actionClass()));
    while (recent.size() > MAX_OBSERVATIONS_PER_KEY) recent.removeFirst();
    if ("demo.customer.bulk_read".equals(event.path("action").path("toolName").asText())
        && "synthetic-crm".equals(event.path("action").path("target").path("system").asText())
        && "read".equals(classification.actionClass())) {
      String principal = principalKey(event);
      syntheticPiiReads.remove(principal);
      syntheticPiiReads.put(principal, now);
      if (syntheticPiiReads.size() > MAX_KEYS) {
        syntheticPiiReads.remove(syntheticPiiReads.keySet().iterator().next());
      }
    }
    if (observations.size() > MAX_KEYS) {
      String oldest = observations.keySet().iterator().next();
      observations.remove(oldest);
    }
  }

  private void prune(Deque<Observation> recent, Instant now) {
    Instant cutoff = now.minus(window);
    while (!recent.isEmpty() && recent.getFirst().observedAt().isBefore(cutoff)) {
      recent.removeFirst();
    }
  }

  private static String key(JsonNode event) {
    return principalKey(event)
        + "\u0000"
        + event.path("action").path("target").path("system").asText();
  }

  private static String principalKey(JsonNode event) {
    JsonNode subject = event.path("identity").path("subject");
    return subject.path("organizationId").asText() + "\u0000" + subject.path("id").asText();
  }

  private static boolean destructive(String tool) {
    String normalized = tool.toLowerCase(java.util.Locale.ROOT);
    return normalized.contains("delete")
        || normalized.contains("drop")
        || normalized.contains("terminate")
        || normalized.contains("destroy")
        || normalized.contains("purge")
        || normalized.contains("truncate");
  }

  private record Observation(Instant observedAt, String actionClass) {}
}
