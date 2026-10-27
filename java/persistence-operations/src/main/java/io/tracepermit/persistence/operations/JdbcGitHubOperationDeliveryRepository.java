package io.tracepermit.persistence.operations;

import static io.tracepermit.application.BusinessOperationStorageException.Code.*;
import static io.tracepermit.persistence.operations.JdbcBusinessOperationRepository.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.adapters.remote.github.GitHubBusinessOperationBinding;
import io.tracepermit.application.BusinessOperationAuthorityProfile;
import io.tracepermit.application.BusinessOperationExecution;
import io.tracepermit.application.BusinessOperationScope;
import io.tracepermit.application.BusinessOperationStorageException;
import io.tracepermit.application.BusinessOperationTransitionPlanner;
import io.tracepermit.contracts.CanonicalJson;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

/** Explicit V7 synthetic-only sender. No network/provider/credential, default runtime or retry. */
public final class JdbcGitHubOperationDeliveryRepository {
  private final DataSource source;
  private final BusinessOperationAuthorityProfile profile;
  private final GitHubBusinessOperationBinding.ExpectedProfile expectedTarget;
  private final SyntheticGitHubDeliveryTarget target;
  private final OperationPermitCodec codec;
  // Package-internal observation-only crash seam. Never supplies time, identity, claims or decisions.
  interface Barriers { void reached(String phase); }
  private final Barriers barriers;
  private static final BusinessOperationTransitionPlanner PLANNER = new BusinessOperationTransitionPlanner();

  /** Privately constructed acknowledgement, NOT serializable/reconstructible from a historical read. */
  public static final class Lease {
    private final JdbcGitHubOperationDeliveryRepository owner;
    private final OperationDeliveryJournal.Event event;
    private final JsonNode admitted;
    private Lease(JdbcGitHubOperationDeliveryRepository owner, OperationDeliveryJournal.Event event, JsonNode admitted) {
      this.owner = owner; this.event = event; this.admitted = admitted.deepCopy();
    }
    public Instant expiresAt() { return OperationDeliveryJournal.time(event.json().path("expiresAt")); }
    public int fence() { return event.fence(); }
    @Override public String toString() { return "OperationDeliveryLease[redacted; fresh revalidation required]"; }
  }
  public record Reconciliation(String durableState, boolean dispatchStarted, String syntheticObservation, int syntheticInvocations) {}

  public JdbcGitHubOperationDeliveryRepository(DataSource source, BusinessOperationAuthorityProfile profile,
      GitHubBusinessOperationBinding.ExpectedProfile expectedTarget, SyntheticGitHubDeliveryTarget target) {
    this(source, profile, expectedTarget, target, phase -> {});
  }

  JdbcGitHubOperationDeliveryRepository(DataSource source, BusinessOperationAuthorityProfile profile,
      GitHubBusinessOperationBinding.ExpectedProfile expectedTarget, SyntheticGitHubDeliveryTarget target, Barriers barriers) {
    if (source == null || profile == null || expectedTarget == null || target == null) throw error(OPERATION_COMMAND_INVALID);
    this.source = source; this.profile = profile; this.expectedTarget = expectedTarget; this.target = target;
    this.barriers = java.util.Objects.requireNonNull(barriers);
    codec = new OperationPermitCodec(profile.admissionSettings(), null);
    OperationDatabase.validateDeliveryExisting(source);
  }

  /** Caller needs the original actual signed permit AND fresh trusted owner execution authority. */
  public Lease claim(BusinessOperationExecution command, String serializedPermit) {
    barriers.reached("before_claim");
    JsonNode signed = verified(command, serializedPermit);
    OperationDeliveryJournal.Event event;
    JsonNode admitted;
    boolean commitAttempted = false;
    try (Connection connection = source.getConnection()) {
      var engine = begin(connection);
      try {
        OperationAuthorityControl.requireCurrentForDeliveryClaim(connection, profile);
        Loaded loaded = load(connection, command);
        admitted = loaded.head();
        Instant now = time(connection, engine);
        requirePresentation(loaded, command, signed, serializedPermit, now);
        List<OperationDeliveryJournal.Event> events = loaded.events();
        if (events.stream().anyMatch(e -> !e.kind().equals("lease"))) throw error(OPERATION_DISPATCH_ALREADY_STARTED);
        if (!events.isEmpty() && OperationDeliveryJournal.time(events.getLast().json().path("expiresAt")).isAfter(now)) {
          throw error(OPERATION_DELIVERY_LEASE_BUSY);
        }
        // Refusal creates nothing. Do not serialize 31 losing contenders through policy/binding recapture.
        // Every successful claim still performs that complete check, plus its post-write recheck.
        requireAuthority(connection, loaded, command, signed, serializedPermit, now);
        Instant expiry = OperationDeliveryJournal.deadline(loaded.admission().json(), command.actor(), now);
        if (!expiry.isAfter(now)) throw error(OPERATION_PERMIT_EXPIRED);
        ObjectNode json = OperationDeliveryJournal.base("lease", command.scope(), loaded.admission(), loaded.outboxHash(), admitted,
            events, events.isEmpty() ? 1 : events.getLast().fence() + 1, now)
            .put("leaseId", UUID.randomUUID().toString()).put("expiresAt", expiry.toString());
        json.set("identity", command.actor());
        event = OperationDeliveryJournal.create(json);
        OperationDeliveryJournal.insert(connection, event);
        Instant after = time(connection, engine);
        if (after.isBefore(now)) throw error(OPERATION_CLOCK_REGRESSION);
        requireAuthority(connection, loaded, command, signed, serializedPermit, after);
        requireLive(event, after);
        commitAttempted = true; connection.commit();
      } catch (SQLException | RuntimeException failure) { throw rollback(connection, failure, commitAttempted); }
    } catch (SQLException | RuntimeException failure) { throw fixed(failure, commitAttempted); }
    barriers.reached("after_claim");
    return new Lease(this, event, admitted); // Only after commit AND close acknowledge.
  }

  /** Exactly one acknowledged start may invoke the fixed synthetic target. This method never retries. */
  public String dispatch(Lease lease, BusinessOperationExecution command, String serializedPermit) {
    JsonNode signed = verified(command, serializedPermit);
    if (lease == null || lease.owner != this) throw error(OPERATION_COMMAND_INVALID);
    // Concrete immutable preflight, outside any DB lock; locked strict bindings are checked again below.
    target.requirePreconditions(command.scope(), lease.admitted, expectedTarget.fingerprint());
    barriers.reached("before_dispatch_started");
    OperationDeliveryJournal.Event start;
    boolean commitAttempted = false;
    try (Connection connection = source.getConnection()) {
      var engine = begin(connection);
      try {
        Loaded loaded = load(connection, command);
        Instant now = time(connection, engine);
        requireAuthority(connection, loaded, command, signed, serializedPermit, now);
        if (loaded.events().isEmpty() || !loaded.events().getLast().hash().equals(lease.event.hash())) throw error(OPERATION_STATE_STALE);
        requireLive(lease.event, now);
        ObjectNode json = OperationDeliveryJournal.base("dispatch_started", command.scope(), loaded.admission(), loaded.outboxHash(),
            loaded.head(), loaded.events(), lease.fence(), now).put("leaseHash", lease.event.hash());
        json.set("identity", command.actor());
        start = OperationDeliveryJournal.transition(json, command.scope(), loaded.head(), "executing");
        JsonNode result = OperationDeliveryJournal.snapshot(command.scope(), loaded.head(), "executing", now);
        OperationDeliveryJournal.insert(connection, start);
        Instant observed = recheck(connection, engine, loaded, command, signed, serializedPermit, lease.event, now);
        appendFact(connection, command.operationId(), loaded.head(), result, start);
        observed = recheck(connection, engine, loaded, command, signed, serializedPermit, lease.event, observed);
        updateHead(connection, command.operationId(), loaded.head(), result);
        recheck(connection, engine, loaded, command, signed, serializedPermit, lease.event, observed);
        commitAttempted = true; connection.commit();
      } catch (SQLException | RuntimeException failure) { throw rollback(connection, failure, commitAttempted); }
    } catch (SQLException | RuntimeException failure) { throw fixed(failure, commitAttempted); }
    // NO DATABASE CONNECTION/LOCK remains here. A paused worker cannot be atomically fenced by the DB.
    barriers.reached("after_dispatch_started");
    String outcome = target.invoke(command.operationId());
    barriers.reached("after_target_before_result");
    try { recordResult(command, start, outcome); }
    catch (BusinessOperationStorageException failure) {
      // The target was invoked. A storage error must not look like a pre-effect failure/retry signal.
      throw error(OPERATION_DELIVERY_RESULT_UNKNOWN);
    }
    return outcome;
  }

  /** Historical metadata only; never claims, mutates, retries, or reconstructs a Lease. */
  public Reconciliation reconcile(BusinessOperationScope scope, String operationId) {
    if (!profile.scope().equals(scope)) throw error(OPERATION_SCOPE_MISMATCH);
    String id = normalizeId(operationId);
    String state;
    boolean started;
    try (Connection connection = source.getConnection()) {
      OperationDatabase.inspectWriter(connection); OperationDatabase.requireDeliveryVersion(connection);
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ); connection.setAutoCommit(false);
      try {
        JsonNode initial = read(connection, scope, "operation_id", id).orElseThrow(() -> error(OPERATION_NOT_FOUND));
        JsonNode head = JdbcBusinessOperationLifecycleRepository.current(connection, scope, id, initial, false);
        state = head.path("execution").path("status").asText();
        started = OperationDeliveryJournal.read(connection, id).stream().anyMatch(e -> e.kind().equals("dispatch_started"));
      } finally { connection.rollback(); }
    } catch (SQLException | RuntimeException failure) { throw fixed(failure, false); }
    return new Reconciliation(state, started, target.observation(id), target.invocations(id));
  }

  private void recordResult(BusinessOperationExecution command, OperationDeliveryJournal.Event start, String outcome) {
    boolean commitAttempted = false;
    try (Connection connection = source.getConnection()) {
      var engine = begin(connection);
      try {
        Loaded loaded = load(connection, command, false);
        if (loaded.events().isEmpty() || !loaded.events().getLast().hash().equals(start.hash())
            || !"executing".equals(loaded.head().path("execution").path("status").asText())) throw error(OPERATION_STATE_STALE);
        Instant now = time(connection, engine);
        if (now.isBefore(start.at()) || now.isBefore(Instant.parse(loaded.head().path("updatedAt").asText()))) throw error(OPERATION_CLOCK_REGRESSION);
        // Recording observed history grants no authority and does not require renewing expired authority.
        ObjectNode json = OperationDeliveryJournal.base("result", command.scope(), loaded.admission(), loaded.outboxHash(),
            admitted(connection, command.scope(), loaded), loaded.events(), start.fence(), now)
            .put("startHash", start.hash()).put("outcome", outcome)
            .put("receiptFingerprint", SyntheticGitHubDeliveryTarget.receiptFingerprint(command.operationId(),
                loaded.admission().json().path("claims").path("attemptId").asText(), start.fence(), outcome));
        var event = OperationDeliveryJournal.transition(json, command.scope(), loaded.head(), outcome);
        JsonNode result = OperationDeliveryJournal.snapshot(command.scope(), loaded.head(), outcome, now);
        OperationDeliveryJournal.insert(connection, event);
        appendFact(connection, command.operationId(), loaded.head(), result, event);
        updateHead(connection, command.operationId(), loaded.head(), result);
        if (time(connection, engine).isBefore(now)) throw error(OPERATION_CLOCK_REGRESSION);
        commitAttempted = true; connection.commit();
      } catch (SQLException | RuntimeException failure) { throw rollback(connection, failure, commitAttempted); }
    } catch (SQLException | RuntimeException failure) { throw fixed(failure, commitAttempted); }
  }

  private JsonNode verified(BusinessOperationExecution command, String serializedPermit) {
    if (command == null || !profile.scope().equals(command.scope())) throw error(OPERATION_COMMAND_INVALID);
    JsonNode signed = codec.verify(serializedPermit).claims();
    if (!command.operationId().equals(signed.path("operationId").asText())) throw error(OPERATION_PERMIT_INVALID);
    return signed;
  }

  private OperationDatabase.Engine begin(Connection connection) throws SQLException {
    var engine = OperationDatabase.inspectWriter(connection);
    connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED); connection.setAutoCommit(false);
    return engine;
  }
  private record Loaded(JsonNode initial, JsonNode head, JsonNode approved, OperationAdmissionJournal.Admission admission,
      String outboxHash, List<OperationDeliveryJournal.Event> events) {}
  private Loaded load(Connection connection, BusinessOperationExecution command) throws SQLException { return load(connection, command, true); }
  private Loaded load(Connection connection, BusinessOperationExecution command, boolean expected) throws SQLException {
    OperationAuthorityControl.requireCurrent(connection, profile); // Global order: control BEFORE head.
    OperationDatabase.requireDeliveryVersion(connection);
    JsonNode initial = read(connection, command.scope(), "operation_id", command.operationId()).orElseThrow(() -> error(OPERATION_NOT_FOUND));
    JsonNode head = JdbcBusinessOperationLifecycleRepository.current(connection, command.scope(), command.operationId(), initial, true);
    var events = OperationDeliveryJournal.read(connection, command.operationId());
    if (expected && events.stream().anyMatch(e -> !e.kind().equals("lease"))) throw error(OPERATION_DISPATCH_ALREADY_STARTED);
    if (expected && !PLANNER.observe(command.scope(), head).equals(command.expected())) throw error(OPERATION_STATE_STALE);
    var admission = OperationAdmissionJournal.read(connection, command.operationId()).orElseThrow(() -> error(OPERATION_NOT_APPROVED));
    JsonNode approved = initial;
    var binding = OperationReviewJournal.read(connection, command.operationId()).orElseThrow(() -> error(OPERATION_STORAGE_CORRUPT));
    try (var statement = prepare(connection,
        "SELECT fact_json, actor_json FROM tp_operation_lifecycle_facts WHERE operation_id = ? AND sequence_number IN (4, 5) ORDER BY sequence_number")) {
      statement.setString(1, command.operationId());
      try (var rows = statement.executeQuery()) {
        for (int n = 0; n < 2; n++) {
          if (!rows.next()) throw error(OPERATION_STORAGE_CORRUPT);
          approved = OperationReviewJournal.replay(command.scope(), approved, StoredOperationJson.read(rows.getString(1), MAX_BYTES),
              StoredOperationJson.read(rows.getString(2), 4096), binding);
        }
        if (rows.next()) throw error(OPERATION_STORAGE_CORRUPT);
      }
    }
    return new Loaded(initial, head, approved, admission, OperationDeliveryJournal.outboxHash(connection, command.operationId()), events);
  }
  private static JsonNode admitted(Connection connection, BusinessOperationScope scope, Loaded loaded) throws SQLException {
    try (var statement = prepare(connection, "SELECT fact_json, actor_json FROM tp_operation_lifecycle_facts WHERE operation_id = ? AND sequence_number = 6")) {
      statement.setString(1, loaded.head().path("operationId").asText());
      try (var rows = statement.executeQuery()) {
        if (!rows.next()) throw error(OPERATION_STORAGE_CORRUPT);
        JsonNode result = OperationAdmissionJournal.replay(connection, scope, loaded.approved(), StoredOperationJson.read(rows.getString(1), MAX_BYTES),
            StoredOperationJson.read(rows.getString(2), 4096), loaded.admission(), OperationReviewJournal.read(connection, loaded.head().path("operationId").asText()).orElseThrow());
        if (rows.next()) throw error(OPERATION_STORAGE_CORRUPT); return result;
      }
    }
  }
  private void requireAuthority(Connection connection, Loaded loaded, BusinessOperationExecution command, JsonNode signed,
      String serialized, Instant now) throws SQLException {
    requirePresentation(loaded, command, signed, serialized, now);
    JsonNode actual = OperationPermitClaims.recaptureForDelivery(connection, loaded.initial(), loaded.approved(), profile, expectedTarget, command, now);
    if (!CanonicalJson.sha256(stable(actual)).equals(CanonicalJson.sha256(stable(signed)))) throw error(OPERATION_PERMIT_INVALID);
    GitHubBusinessOperationBinding.bind(command.scope(), loaded.head(), expectedTarget);
  }
  private void requirePresentation(Loaded loaded, BusinessOperationExecution command, JsonNode signed, String serialized, Instant now) {
    if (!"claimed".equals(loaded.head().path("execution").path("status").asText())) throw error(OPERATION_NOT_APPROVED);
    if (now.isBefore(Instant.parse(loaded.head().path("updatedAt").asText()))
        || now.isBefore(OperationDeliveryJournal.time(signed.path("issuedAt")))
        || (!loaded.events().isEmpty() && now.isBefore(loaded.events().getLast().at()))) throw error(OPERATION_CLOCK_REGRESSION);
    if (!OperationDeliveryJournal.deadline(loaded.admission().json(), command.actor(), now).isAfter(now)) throw error(OPERATION_PERMIT_EXPIRED);
    if (!OperationPermitCodec.fingerprint(serialized).equals(loaded.admission().json().path("permitFingerprint").asText())
        || !CanonicalJson.sha256(signed).equals(CanonicalJson.sha256(loaded.admission().json().path("claims")))) throw error(OPERATION_PERMIT_INVALID);
    command.requireAuthorizedAt(profile, now);
  }
  private static ObjectNode stable(JsonNode value) {
    ObjectNode result = value.deepCopy(); result.remove(List.of("permitId", "attemptId", "issuedAt", "expiresAt", "executeIdentity"));
    ((ObjectNode) result.path("identityBounds")).remove("ownerExpiresAt"); return result;
  }
  private Instant recheck(Connection connection, OperationDatabase.Engine engine, Loaded loaded, BusinessOperationExecution command,
      JsonNode signed, String serialized, OperationDeliveryJournal.Event lease, Instant prior) throws SQLException {
    Instant now = time(connection, engine);
    if (now.isBefore(prior)) throw error(OPERATION_CLOCK_REGRESSION);
    requireAuthority(connection, loaded, command, signed, serialized, now); requireLive(lease, now);
    return now;
  }
  private static void requireLive(OperationDeliveryJournal.Event lease, Instant now) {
    if (!OperationDeliveryJournal.time(lease.json().path("expiresAt")).isAfter(now)) throw error(OPERATION_DELIVERY_LEASE_EXPIRED);
  }
  private static Instant time(Connection connection, OperationDatabase.Engine engine) throws SQLException {
    return JdbcBusinessOperationLifecycleRepository.storeTime(connection, engine);
  }
  private static void appendFact(Connection connection, String id, JsonNode before, JsonNode result, OperationDeliveryJournal.Event event) throws SQLException {
    JsonNode fact = result.path("transitions").get(result.path("transitions").size() - 1); JsonNode actor = OperationDeliveryJournal.actor(event);
    try (var statement = prepare(connection, "INSERT INTO tp_operation_lifecycle_facts (operation_id, sequence_number, previous_snapshot_hash, result_snapshot_hash, fact_json, actor_hash, actor_json) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
      statement.setString(1, id); statement.setInt(2, fact.path("sequence").asInt()); statement.setString(3, CanonicalJson.sha256(before));
      statement.setString(4, CanonicalJson.sha256(result)); statement.setString(5, CanonicalJson.canonicalize(fact));
      statement.setString(6, CanonicalJson.sha256(actor)); statement.setString(7, CanonicalJson.canonicalize(actor));
      if (statement.executeUpdate() != 1) throw error(OPERATION_STORAGE_CORRUPT);
    }
  }
  private static void updateHead(Connection connection, String id, JsonNode before, JsonNode result) throws SQLException {
    try (var statement = prepare(connection, "UPDATE tp_operation_lifecycle_heads SET snapshot_hash = ?, snapshot_json = ? WHERE operation_id = ? AND snapshot_hash = ?")) {
      statement.setString(1, CanonicalJson.sha256(result)); statement.setString(2, CanonicalJson.canonicalize(result));
      statement.setString(3, id); statement.setString(4, CanonicalJson.sha256(before));
      if (statement.executeUpdate() != 1) throw error(OPERATION_STATE_STALE);
    }
  }
  private static BusinessOperationStorageException rollback(Connection connection, Exception failure, boolean commitAttempted) {
    try { connection.rollback(); } catch (SQLException | RuntimeException cleanup) { return error(commitAttempted ? OPERATION_COMMIT_UNKNOWN : OPERATION_STORAGE_UNAVAILABLE); }
    return fixed(failure, commitAttempted);
  }
  private static BusinessOperationStorageException fixed(Exception failure, boolean commitAttempted) {
    return error(commitAttempted ? OPERATION_COMMIT_UNKNOWN : failure instanceof BusinessOperationStorageException fixed ? fixed.code() : OPERATION_STORAGE_UNAVAILABLE);
  }
  private static BusinessOperationStorageException error(BusinessOperationStorageException.Code code) { return new BusinessOperationStorageException(code); }
  @Override public String toString() { return "JdbcGitHubOperationDeliveryRepository[synthetic only; redacted]"; }
}
