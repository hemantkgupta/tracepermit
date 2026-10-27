package io.tracepermit.persistence.operations;

import static io.tracepermit.application.BusinessOperationStorageException.Code.OPERATION_STORAGE_CORRUPT;
import static io.tracepermit.persistence.operations.JdbcBusinessOperationRepository.prepare;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.application.BusinessOperationScope;
import io.tracepermit.application.BusinessOperationStorageException;
import io.tracepermit.application.BusinessOperationTransitionPlanner;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Closed immutable V7 vocabulary. Integrity within the trusted DB, never retained signatures. */
final class OperationDeliveryJournal {
  static final String FORMAT = "tracepermit-operation-delivery-v1";
  static final String GENESIS = "sha256:" + "0".repeat(64);
  static final int MAX_EVENTS = 1024; // Saturation refuses; no eviction or authority renewal.
  private static final BusinessOperationTransitionPlanner PLANNER = new BusinessOperationTransitionPlanner();
  private static final Set<String> COMMON = Set.of("format", "kind", "operationId", "scopeHash", "admissionHash",
      "outboxHash", "attemptId", "admittedSnapshotHash", "sequence", "fence", "previousEventHash", "at");
  record Event(ObjectNode json, String hash) {
    Event { json = json.deepCopy(); }
    @Override public ObjectNode json() { return json.deepCopy(); }
    String kind() { return json.path("kind").asText(); }
    int sequence() { return json.path("sequence").asInt(); }
    int fence() { return json.path("fence").asInt(); }
    Instant at() { return time(json.path("at")); }
    @Override public String toString() { return "OperationDeliveryEvent[redacted; historical]"; }
  }
  private OperationDeliveryJournal() {}

  static List<Event> read(Connection connection, String id) throws SQLException {
    if (!OperationDatabase.hasDelivery(connection)) return List.of();
    OperationDatabase.requireDeliveryVersion(connection);
    List<Event> events = new ArrayList<>();
    try (var statement = prepare(connection,
        "SELECT event_sequence, event_hash, event_json FROM tp_operation_delivery_events WHERE operation_id = ? ORDER BY event_sequence")) {
      statement.setString(1, id);
      try (var rows = statement.executeQuery()) {
        while (rows.next()) {
          if (events.size() == MAX_EVENTS) throw corrupt();
          Event event = checked(StoredOperationJson.read(rows.getString(3), 4096), rows.getString(2));
          if (!id.equals(event.json().path("operationId").asText()) || rows.getInt(1) != events.size() + 1
              || event.sequence() != rows.getInt(1)) throw corrupt();
          events.add(event);
        }
      }
    }
    return List.copyOf(events);
  }

  static Event create(ObjectNode json) { return checked(json, CanonicalJson.sha256(json)); }

  private static Event checked(JsonNode value, String hash) {
    try {
      Set<String> fields = new HashSet<>(COMMON);
      String kind = value.path("kind").asText();
      switch (kind) {
        case "lease" -> fields.addAll(Set.of("leaseId", "expiresAt", "identity"));
        case "dispatch_started" -> fields.addAll(Set.of("leaseHash", "identity", "previousSnapshotHash", "resultSnapshotHash", "factHash"));
        case "result" -> fields.addAll(Set.of("startHash", "outcome", "receiptFingerprint", "previousSnapshotHash", "resultSnapshotHash", "factHash"));
        default -> throw corrupt();
      }
      fields(value, fields);
      if (!FORMAT.equals(value.path("format").asText()) || !hash.equals(CanonicalJson.sha256(value))) throw corrupt();
      for (String name : fields) {
        if (name.endsWith("Hash") || name.equals("receiptFingerprint")) hash(value.path(name));
      }
      for (String name : Set.of("operationId", "attemptId")) uuid(value.path(name));
      for (String name : Set.of("sequence", "fence")) {
        JsonNode number = value.path(name);
        if (!number.isIntegralNumber() || !number.canConvertToInt() || number.asInt() < 1 || number.asInt() > MAX_EVENTS) throw corrupt();
      }
      time(value.path("at"));
      if (kind.equals("lease")) { uuid(value.path("leaseId")); time(value.path("expiresAt")); }
      if (kind.equals("result") && !Set.of("succeeded", "failed", "unknown").contains(value.path("outcome").asText())) throw corrupt();
      return new Event((ObjectNode) value, hash);
    } catch (RuntimeException failure) { throw corrupt(); }
  }

  static ObjectNode base(String kind, BusinessOperationScope scope, OperationAdmissionJournal.Admission admission,
      String outboxHash, JsonNode admitted, List<Event> previous, int fence, Instant now) {
    return JsonSupport.MAPPER.createObjectNode().put("format", FORMAT).put("kind", kind)
        .put("operationId", admitted.path("operationId").asText()).put("scopeHash", scope.fingerprint())
        .put("admissionHash", admission.hash()).put("outboxHash", outboxHash)
        .put("attemptId", admission.json().path("claims").path("attemptId").asText())
        .put("admittedSnapshotHash", CanonicalJson.sha256(admitted)).put("sequence", previous.size() + 1)
        .put("fence", fence).put("previousEventHash", previous.isEmpty() ? GENESIS : previous.getLast().hash())
        .put("at", now.toString());
  }

  static Instant deadline(JsonNode admission, JsonNode identity, Instant now) {
    Instant end = now.plusSeconds(30);
    JsonNode claims = admission.path("claims");
    var bounds = new ArrayList<Instant>();
    bounds.add(time(claims.path("expiresAt")));
    bounds.add(time(admission.path("identity").path("authorityExpiresAt")));
    bounds.add(time(identity.path("authorityExpiresAt")));
    for (JsonNode bound : claims.path("identityBounds")) bounds.add(time(bound));
    for (Instant bound : bounds) if (bound.isBefore(end)) end = bound;
    return end;
  }

  static void insert(Connection connection, Event event) throws SQLException {
    try (var statement = prepare(connection,
        "INSERT INTO tp_operation_delivery_events (operation_id, event_sequence, event_hash, event_json) VALUES (?, ?, ?, ?)")) {
      statement.setString(1, event.json().path("operationId").asText()); statement.setInt(2, event.sequence());
      statement.setString(3, event.hash()); statement.setString(4, CanonicalJson.canonicalize(event.json()));
      if (statement.executeUpdate() != 1) throw corrupt();
    }
  }

  static ObjectNode actor(Event event) {
    return JsonSupport.MAPPER.createObjectNode().put("journalFormat", FORMAT).put("kind", event.kind()).put("eventHash", event.hash());
  }

  static Event transition(ObjectNode base, BusinessOperationScope scope, JsonNode before, String status) {
    JsonNode result = snapshot(scope, before, status, time(base.path("at")));
    base.put("previousSnapshotHash", CanonicalJson.sha256(before)).put("resultSnapshotHash", CanonicalJson.sha256(result))
        .put("factHash", CanonicalJson.sha256(result.path("transitions").get(result.path("transitions").size() - 1)));
    return create(base);
  }

  static JsonNode snapshot(BusinessOperationScope scope, JsonNode before, String status, Instant at) {
    return PLANNER.propose(scope, before, PLANNER.observe(scope, before), BusinessOperationTransitionPlanner.Dimension.EXECUTION,
        status, status.equals("executing") ? "OPERATION_DISPATCH_STARTED" : "OPERATION_DISPATCH_" + status.toUpperCase(java.util.Locale.ROOT), at).snapshot();
  }

  static JsonNode replay(Connection connection, BusinessOperationScope scope, JsonNode before, JsonNode fact, JsonNode actor) throws SQLException {
    try {
      fields(actor, Set.of("journalFormat", "kind", "eventHash"));
      Event event = read(connection, before.path("operationId").asText()).stream()
          .filter(e -> e.hash().equals(actor.path("eventHash").asText())).findFirst().orElseThrow(OperationDeliveryJournal::corrupt);
      if (!FORMAT.equals(actor.path("journalFormat").asText()) || !event.kind().equals(actor.path("kind").asText())
          || event.kind().equals("lease")) throw corrupt();
      return replayEvent(scope, before, fact, event);
    } catch (RuntimeException failure) { throw corrupt(); }
  }

  private static JsonNode replayEvent(BusinessOperationScope scope, JsonNode before, JsonNode fact, Event event) {
    String state = event.kind().equals("dispatch_started") ? "executing" : event.json().path("outcome").asText();
    String required = event.kind().equals("dispatch_started") ? "claimed" : "executing";
    if (!required.equals(before.path("execution").path("status").asText())) throw corrupt();
    JsonNode result = snapshot(scope, before, state, event.at());
    JsonNode expectedFact = result.path("transitions").get(result.path("transitions").size() - 1);
    if (!CanonicalJson.sha256(before).equals(event.json().path("previousSnapshotHash").asText())
        || !CanonicalJson.sha256(result).equals(event.json().path("resultSnapshotHash").asText())
        || !CanonicalJson.sha256(expectedFact).equals(event.json().path("factHash").asText())
        || !CanonicalJson.canonicalize(fact).equals(CanonicalJson.canonicalize(expectedFact))) throw corrupt();
    return result;
  }

  /** All event/fact cardinalities and immutable anchors, including leases with no lifecycle fact. */
  static void requireHistory(Connection connection, BusinessOperationScope scope, JsonNode admitted, JsonNode head,
      OperationAdmissionJournal.Admission admission) throws SQLException {
    List<Event> events = read(connection, head.path("operationId").asText());
    if (events.isEmpty()) {
      if (admitted != null && !CanonicalJson.sha256(admitted).equals(CanonicalJson.sha256(head))) throw corrupt();
      return;
    }
    if (admitted == null || admission == null) throw corrupt();
    String outbox = outboxHash(connection, head.path("operationId").asText());
    Event lease = null, start = null;
    JsonNode replay = admitted;
    String previous = GENESIS;
    Instant last = Instant.parse(admitted.path("updatedAt").asText());
    int fence = 0;
    boolean resultSeen = false;
    for (Event event : events) {
      JsonNode value = event.json();
      if (!scope.fingerprint().equals(value.path("scopeHash").asText()) || !admission.hash().equals(value.path("admissionHash").asText())
          || !outbox.equals(value.path("outboxHash").asText()) || !CanonicalJson.sha256(admitted).equals(value.path("admittedSnapshotHash").asText())
          || !admission.json().path("claims").path("attemptId").equals(value.path("attemptId"))
          || !previous.equals(value.path("previousEventHash").asText()) || event.at().isBefore(last) || resultSeen) throw corrupt();
      if (event.kind().equals("lease")) {
        if (start != null || event.fence() != ++fence || (lease != null && time(lease.json().path("expiresAt")).isAfter(event.at()))) throw corrupt();
        identity(value.path("identity"), scope, event.at());
        Instant expiry = deadline(admission.json(), value.path("identity"), event.at());
        if (!expiry.isAfter(event.at()) || !expiry.equals(time(value.path("expiresAt")))
            || event.at().isBefore(time(admission.json().path("claims").path("issuedAt")))) throw corrupt();
        lease = event;
      } else {
        if (lease == null || event.fence() != fence) throw corrupt();
        if (event.kind().equals("dispatch_started")) {
          if (start != null || !lease.hash().equals(value.path("leaseHash").asText())
              || !time(lease.json().path("expiresAt")).isAfter(event.at())) throw corrupt();
          identity(value.path("identity"), scope, event.at());
          start = event;
        } else {
          if (start == null || !start.hash().equals(value.path("startHash").asText())) throw corrupt();
          if (!SyntheticGitHubDeliveryTarget.receiptFingerprint(value.path("operationId").asText(), value.path("attemptId").asText(),
              fence, value.path("outcome").asText()).equals(value.path("receiptFingerprint").asText())) throw corrupt();
          resultSeen = true;
        }
        int position = replay.path("transitions").size();
        if (head.path("transitions").size() <= position) throw corrupt();
        replay = replayEvent(scope, replay, head.path("transitions").get(position), event);
      }
      previous = event.hash(); last = event.at();
    }
    if (!CanonicalJson.sha256(replay).equals(CanonicalJson.sha256(head))) throw corrupt();
  }

  static String outboxHash(Connection connection, String id) throws SQLException {
    try (var statement = prepare(connection, "SELECT outbox_hash, outbox_json FROM tp_operation_admission_outbox WHERE operation_id = ?")) {
      statement.setString(1, id);
      try (var rows = statement.executeQuery()) {
        if (!rows.next()) throw corrupt();
        String hash = rows.getString(1);
        if (!hash.equals(CanonicalJson.sha256(StoredOperationJson.read(rows.getString(2), 4096))) || rows.next()) throw corrupt();
        return hash;
      }
    }
  }

  private static void identity(JsonNode value, BusinessOperationScope scope, Instant at) {
    fields(value, Set.of("scopeHash", "principalId", "authenticationMethod", "credentialFingerprint", "authorityExpiresAt"));
    if (!scope.fingerprint().equals(value.path("scopeHash").asText())
        || !scope.requesterPrincipalId().equals(value.path("principalId").asText())
        || !value.path("authenticationMethod").isTextual()
        || !value.path("authenticationMethod").asText().matches("[A-Za-z0-9][A-Za-z0-9._:@/+\\-]{0,127}")
        || !time(value.path("authorityExpiresAt")).isAfter(at)) throw corrupt();
    hash(value.path("credentialFingerprint"));
  }
  private static void fields(JsonNode value, Set<String> fields) {
    Set<String> actual = new HashSet<>(); value.fieldNames().forEachRemaining(actual::add);
    if (!value.isObject() || !actual.equals(fields)) throw corrupt();
  }
  private static void uuid(JsonNode value) {
    if (!value.isTextual() || !java.util.UUID.fromString(value.asText()).toString().equals(value.asText())) throw corrupt();
  }
  private static void hash(JsonNode value) { if (!value.isTextual() || !value.asText().matches("sha256:[a-f0-9]{64}")) throw corrupt(); }
  static Instant time(JsonNode value) { return OperationReviewJournal.time(value); }
  private static BusinessOperationStorageException corrupt() { return new BusinessOperationStorageException(OPERATION_STORAGE_CORRUPT); }
}
