package io.tracepermit.persistence.postgres;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.policy.ActivePolicySnapshot;
import io.tracepermit.policy.PolicyConcurrencyException;
import io.tracepermit.policy.PolicyLifecycleStore;
import io.tracepermit.policy.PolicyState;
import io.tracepermit.policy.PolicyTransitionRecord;
import io.tracepermit.policy.PolicyValidator;
import io.tracepermit.policy.PolicyVersionRecord;
import io.tracepermit.policy.RollbackRecord;
import org.flywaydb.core.api.FlywayException;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * PostgreSQL-backed durable implementation of {@link PolicyLifecycleStore}.
 *
 * <p>Persists immutable policy revisions, transactional activation state, and append-only
 * transition history across process restarts. Enforces optimistic concurrency preconditions
 * via row-level locking.
 *
 * <p>Provenance Note: The {@code actorId} parameter is caller-supplied tracking context and
 * does not represent authenticated operator identity or cryptographic non-repudiation.
 * While append-only triggers prevent ordinary application modification and deletion, this
 * store does not claim cryptographic tamper-proofing against a database administrator.
 */
public final class PostgresPolicyLifecycleStore implements PolicyLifecycleStore {

  private static final Pattern WORKSPACE_PATTERN =
      Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:@/+\\-]{0,127}$");
  private static final Pattern POLICY_PATTERN =
      Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:@/+\\-]{0,255}$");
  private static final Pattern ACTOR_PATTERN =
      Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:@/+\\-#]{0,255}$");
  private static final int MAX_REASON_BYTES = 4096;

  private final DataSource dataSource;
  private final Clock clock;
  private final Supplier<String> createId;

  public PostgresPolicyLifecycleStore(DataSource dataSource) {
    this(dataSource, Clock.systemUTC(), () -> UUID.randomUUID().toString());
  }

  public PostgresPolicyLifecycleStore(DataSource dataSource, Clock clock, Supplier<String> createId) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.createId = Objects.requireNonNull(createId, "createId must not be null");
    try {
      PostgresDurableEnforcementState.migrate(dataSource, PostgresDurableEnforcementState.MIGRATION_LOCATION);
      verifySchemaVersion();
    } catch (FlywayException ex) {
      throw new PostgresStateException("Failed to migrate database schema for policy lifecycle store", ex);
    }
  }

  @Override
  public PolicyVersionRecord stage(String workspaceId, JsonNode policyDocument, Instant now) {
    requireWorkspace(workspaceId);
    Objects.requireNonNull(policyDocument, "policyDocument must not be null");
    Objects.requireNonNull(now, "now must not be null");

    JsonNode validated = PolicyValidator.validate(policyDocument);
    String policyId = validated.path("id").asText();
    int version = validated.path("version").asInt();
    String name = validated.path("name").asText();
    String description = validated.path("description").asText();
    String contentHash = CanonicalJson.sha256(validated);
    String documentJson = toJson(validated);

    requirePolicyId(policyId);
    requireVersion(version);

    return inTransaction("Failed to stage policy document", connection -> {
      // ON CONFLICT waits for the competing insertion; the following READ COMMITTED
      // query sees its committed revision without an aborted-transaction retry path.
      try (PreparedStatement insert = connection.prepareStatement("""
          INSERT INTO tp_policy_versions (
            workspace_id, policy_id, version, name, description,
            content_hash, document, current_state, staged_at
          ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, 'staged', ?)
          ON CONFLICT (workspace_id, policy_id, version) DO NOTHING
          """)) {
        insert.setString(1, workspaceId);
        insert.setString(2, policyId);
        insert.setInt(3, version);
        insert.setString(4, name);
        insert.setString(5, description);
        insert.setString(6, contentHash);
        insert.setString(7, documentJson);
        insert.setTimestamp(8, Timestamp.from(now));
        insert.executeUpdate();
      }
      PolicyVersionRecord record = findVersion(connection, workspaceId, policyId, version)
          .orElseThrow(() -> new IllegalStateException("POLICY_VERSION_NOT_FOUND"));
      if (!record.contentHash().equals(contentHash)) {
        throw new IllegalStateException("POLICY_VERSION_IMMUTABLE");
      }
      return record;
    });
  }

  @Override
  public PolicyVersionRecord activate(
      String workspaceId,
      String policyId,
      int version,
      Integer expectedActiveVersion,
      Instant now,
      String actorId,
      String reason) {
    requireWorkspace(workspaceId);
    requirePolicyId(policyId);
    requireVersion(version);
    requireReason(reason);
    validateActor(actorId);
    Objects.requireNonNull(now, "now must not be null");

    return inTransaction("Failed to activate policy version", connection -> {
      lockPolicy(connection, workspaceId, policyId);
      Integer currentActiveVersion = null;
      try (PreparedStatement checkActive = connection.prepareStatement(
          "SELECT active_version FROM tp_active_policies WHERE workspace_id = ? AND policy_id = ? FOR UPDATE")) {
        checkActive.setString(1, workspaceId);
        checkActive.setString(2, policyId);
        try (ResultSet rs = checkActive.executeQuery()) {
          if (rs.next()) {
            currentActiveVersion = rs.getInt("active_version");
          }
        }
      }

      if (expectedActiveVersion != null && !Objects.equals(expectedActiveVersion, currentActiveVersion)) {
        throw new PolicyConcurrencyException(
            "POLICY_CONCURRENCY_CONFLICT: Expected active version " + expectedActiveVersion
                + " but found " + currentActiveVersion);
      }

      if (currentActiveVersion != null && currentActiveVersion == version) {
        return findVersion(connection, workspaceId, policyId, version)
            .orElseThrow(() -> new IllegalStateException("Active version record not found"));
      }

      Optional<PolicyVersionRecord> targetOpt = findVersionForUpdate(connection, workspaceId, policyId, version);
      if (targetOpt.isEmpty()) {
        throw new IllegalArgumentException("POLICY_VERSION_NOT_FOUND: " + policyId + "@v" + version);
      }

      if (currentActiveVersion != null) {
        try (PreparedStatement deprecate = connection.prepareStatement("""
            UPDATE tp_policy_versions
            SET current_state = 'deprecated', deprecated_at = ?
            WHERE workspace_id = ? AND policy_id = ? AND version = ?
            """)) {
          deprecate.setTimestamp(1, Timestamp.from(now));
          deprecate.setString(2, workspaceId);
          deprecate.setString(3, policyId);
          deprecate.setInt(4, currentActiveVersion);
          deprecate.executeUpdate();
        }
      }

      try (PreparedStatement activateVersion = connection.prepareStatement("""
          UPDATE tp_policy_versions
          SET current_state = 'active', activated_at = ?, deprecated_at = NULL, rolled_back_at = NULL
          WHERE workspace_id = ? AND policy_id = ? AND version = ?
          """)) {
        activateVersion.setTimestamp(1, Timestamp.from(now));
        activateVersion.setString(2, workspaceId);
        activateVersion.setString(3, policyId);
        activateVersion.setInt(4, version);
        activateVersion.executeUpdate();
      }

      try (PreparedStatement upsertActive = connection.prepareStatement("""
          INSERT INTO tp_active_policies (
            workspace_id, policy_id, active_version, activated_at, activation_sequence, updated_at
          ) VALUES (?, ?, ?, ?, 1, ?)
          ON CONFLICT (workspace_id, policy_id) DO UPDATE SET
            active_version = EXCLUDED.active_version,
            activated_at = EXCLUDED.activated_at,
            activation_sequence = tp_active_policies.activation_sequence + 1,
            updated_at = EXCLUDED.updated_at
          """)) {
        upsertActive.setString(1, workspaceId);
        upsertActive.setString(2, policyId);
        upsertActive.setInt(3, version);
        upsertActive.setTimestamp(4, Timestamp.from(now));
        upsertActive.setTimestamp(5, Timestamp.from(now));
        upsertActive.executeUpdate();
      }

      long nextSequence = getNextTransitionSequence(connection, workspaceId, policyId);
      String transitionId = createId.get();
      try (PreparedStatement logTransition = connection.prepareStatement("""
          INSERT INTO tp_policy_lifecycle_transitions (
            transition_id, workspace_id, policy_id, transition_sequence, transition_type,
            from_version, to_version, occurred_at, actor_id, reason
          ) VALUES (?, ?, ?, ?, 'activate', ?, ?, ?, ?, ?)
          """)) {
        logTransition.setString(1, transitionId);
        logTransition.setString(2, workspaceId);
        logTransition.setString(3, policyId);
        logTransition.setLong(4, nextSequence);
        if (currentActiveVersion != null) {
          logTransition.setInt(5, currentActiveVersion);
        } else {
          logTransition.setNull(5, Types.INTEGER);
        }
        logTransition.setInt(6, version);
        logTransition.setTimestamp(7, Timestamp.from(now));
        if (actorId != null) {
          logTransition.setString(8, actorId);
        } else {
          logTransition.setNull(8, Types.VARCHAR);
        }
        logTransition.setString(9, reason);
        logTransition.executeUpdate();
      }

      return findVersion(connection, workspaceId, policyId, version)
          .orElseThrow(() -> new IllegalStateException("POLICY_VERSION_NOT_FOUND"));
    });
  }

  @Override
  public RollbackRecord rollback(
      String workspaceId,
      String policyId,
      Integer expectedActiveVersion,
      Integer targetVersion,
      Instant now,
      String actorId,
      String reason) {
    requireWorkspace(workspaceId);
    requirePolicyId(policyId);
    requireReason(reason);
    validateActor(actorId);
    Objects.requireNonNull(now, "now must not be null");

    return inTransaction("Failed to rollback policy version", connection -> {
      lockPolicy(connection, workspaceId, policyId);
      Integer currentActiveVersion = null;
      try (PreparedStatement checkActive = connection.prepareStatement(
          "SELECT active_version FROM tp_active_policies WHERE workspace_id = ? AND policy_id = ? FOR UPDATE")) {
        checkActive.setString(1, workspaceId);
        checkActive.setString(2, policyId);
        try (ResultSet rs = checkActive.executeQuery()) {
          if (rs.next()) {
            currentActiveVersion = rs.getInt("active_version");
          }
        }
      }

      if (currentActiveVersion == null) {
        throw new IllegalStateException("NO_ACTIVE_POLICY: Policy " + policyId + " has no active version to roll back");
      }

      if (expectedActiveVersion != null && expectedActiveVersion != (int) currentActiveVersion) {
        throw new PolicyConcurrencyException(
            "POLICY_CONCURRENCY_CONFLICT: Expected active version " + expectedActiveVersion
                + " but found " + currentActiveVersion);
      }

      int resolvedTargetVersion;
      if (targetVersion != null) {
        resolvedTargetVersion = targetVersion;
        Optional<PolicyVersionRecord> targetRecord = findVersion(connection, workspaceId, policyId, resolvedTargetVersion);
        if (targetRecord.isEmpty()) {
          throw new IllegalArgumentException("POLICY_VERSION_NOT_FOUND: " + policyId + "@v" + targetVersion);
        }
      } else {
        // Look up previous activation in transition history
        List<Integer> historyVersions = new ArrayList<>();
        try (PreparedStatement queryHistory = connection.prepareStatement("""
            SELECT to_version FROM tp_policy_lifecycle_transitions
            WHERE workspace_id = ? AND policy_id = ?
            ORDER BY transition_sequence DESC
            LIMIT 2
            """)) {
          queryHistory.setString(1, workspaceId);
          queryHistory.setString(2, policyId);
          try (ResultSet rs = queryHistory.executeQuery()) {
            while (rs.next()) {
              historyVersions.add(rs.getInt("to_version"));
            }
          }
        }

        if (historyVersions.size() < 2) {
          throw new IllegalStateException(
              "NO_PREVIOUS_ACTIVE_VERSION: Policy " + policyId + " has no previous active version to roll back to");
        }
        resolvedTargetVersion = historyVersions.get(1);
      }

      Optional<PolicyVersionRecord> targetOpt = findVersionForUpdate(connection, workspaceId, policyId, resolvedTargetVersion);
      if (targetOpt.isEmpty()) {
        throw new IllegalStateException(
            "PREVIOUS_ACTIVE_VERSION_MISSING: Version " + resolvedTargetVersion + " not found in policy catalog");
      }

      try (PreparedStatement rollBackCurrent = connection.prepareStatement("""
          UPDATE tp_policy_versions
          SET current_state = 'rolled_back', rolled_back_at = ?, rollback_target_version = ?
          WHERE workspace_id = ? AND policy_id = ? AND version = ?
          """)) {
        rollBackCurrent.setTimestamp(1, Timestamp.from(now));
        rollBackCurrent.setInt(2, resolvedTargetVersion);
        rollBackCurrent.setString(3, workspaceId);
        rollBackCurrent.setString(4, policyId);
        rollBackCurrent.setInt(5, currentActiveVersion);
        rollBackCurrent.executeUpdate();
      }

      try (PreparedStatement reactivate = connection.prepareStatement("""
          UPDATE tp_policy_versions
          SET current_state = 'active', activated_at = ?, deprecated_at = NULL, rolled_back_at = NULL
          WHERE workspace_id = ? AND policy_id = ? AND version = ?
          """)) {
        reactivate.setTimestamp(1, Timestamp.from(now));
        reactivate.setString(2, workspaceId);
        reactivate.setString(3, policyId);
        reactivate.setInt(4, resolvedTargetVersion);
        reactivate.executeUpdate();
      }

      try (PreparedStatement updateActive = connection.prepareStatement("""
          UPDATE tp_active_policies
          SET active_version = ?, activated_at = ?, activation_sequence = activation_sequence + 1, updated_at = ?
          WHERE workspace_id = ? AND policy_id = ?
          """)) {
        updateActive.setInt(1, resolvedTargetVersion);
        updateActive.setTimestamp(2, Timestamp.from(now));
        updateActive.setTimestamp(3, Timestamp.from(now));
        updateActive.setString(4, workspaceId);
        updateActive.setString(5, policyId);
        updateActive.executeUpdate();
      }

      long nextSequence = getNextTransitionSequence(connection, workspaceId, policyId);
      String transitionId = createId.get();
      try (PreparedStatement logTransition = connection.prepareStatement("""
          INSERT INTO tp_policy_lifecycle_transitions (
            transition_id, workspace_id, policy_id, transition_sequence, transition_type,
            from_version, to_version, occurred_at, actor_id, reason
          ) VALUES (?, ?, ?, ?, 'rollback', ?, ?, ?, ?, ?)
          """)) {
        logTransition.setString(1, transitionId);
        logTransition.setString(2, workspaceId);
        logTransition.setString(3, policyId);
        logTransition.setLong(4, nextSequence);
        logTransition.setInt(5, currentActiveVersion);
        logTransition.setInt(6, resolvedTargetVersion);
        logTransition.setTimestamp(7, Timestamp.from(now));
        if (actorId != null) {
          logTransition.setString(8, actorId);
        } else {
          logTransition.setNull(8, Types.VARCHAR);
        }
        logTransition.setString(9, reason);
        logTransition.executeUpdate();
      }

      return new RollbackRecord(
          workspaceId,
          policyId,
          currentActiveVersion,
          resolvedTargetVersion,
          now,
          actorId,
          reason);
    });
  }

  @Override
  public Optional<PolicyVersionRecord> activeVersion(String workspaceId, String policyId) {
    requireWorkspace(workspaceId);
    requirePolicyId(policyId);
    try (Connection connection = dataSource.getConnection();
         PreparedStatement query = connection.prepareStatement("""
             SELECT v.policy_id, v.version, v.name, v.description, v.content_hash,
                    v.document, v.current_state, v.staged_at, v.activated_at,
                    v.deprecated_at, v.rolled_back_at, v.rollback_target_version
             FROM tp_active_policies a
             JOIN tp_policy_versions v
               ON a.workspace_id = v.workspace_id
              AND a.policy_id = v.policy_id
              AND a.active_version = v.version
             WHERE a.workspace_id = ? AND a.policy_id = ?
             """)) {
      query.setString(1, workspaceId);
      query.setString(2, policyId);
      try (ResultSet rs = query.executeQuery()) {
        if (!rs.next()) return Optional.empty();
        return Optional.of(mapRecord(rs));
      }
    } catch (SQLException failure) {
      throw new PostgresStateException("Failed to query active policy version", failure);
    }
  }

  @Override
  public Optional<ActivePolicySnapshot> activePolicySnapshot(String workspaceId, String policyId) {
    requireWorkspace(workspaceId);
    requirePolicyId(policyId);
    try (Connection connection = dataSource.getConnection();
         PreparedStatement query = connection.prepareStatement("""
             SELECT a.workspace_id, a.policy_id, a.active_version, a.activation_sequence, a.activated_at,
                    v.content_hash, v.document
             FROM tp_active_policies a
             JOIN tp_policy_versions v
               ON a.workspace_id = v.workspace_id
              AND a.policy_id = v.policy_id
              AND a.active_version = v.version
             WHERE a.workspace_id = ? AND a.policy_id = ?
             """)) {
      query.setString(1, workspaceId);
      query.setString(2, policyId);
      try (ResultSet rs = query.executeQuery()) {
        if (!rs.next()) return Optional.empty();
        return Optional.of(new ActivePolicySnapshot(
            rs.getString("workspace_id"),
            rs.getString("policy_id"),
            rs.getInt("active_version"),
            rs.getString("content_hash"),
            parseJson(rs.getString("document")),
            rs.getLong("activation_sequence"),
            rs.getTimestamp("activated_at").toInstant()));
      }
    } catch (SQLException failure) {
      throw new PostgresStateException("Failed to query active policy snapshot", failure);
    }
  }

  @Override
  public Optional<PolicyVersionRecord> getVersion(String workspaceId, String policyId, int version) {
    requireWorkspace(workspaceId);
    requirePolicyId(policyId);
    requireVersion(version);
    try (Connection connection = dataSource.getConnection()) {
      return findVersion(connection, workspaceId, policyId, version);
    } catch (SQLException failure) {
      throw new PostgresStateException("Failed to query policy version", failure);
    }
  }

  @Override
  public List<PolicyVersionRecord> listVersions(String workspaceId, String policyId) {
    requireWorkspace(workspaceId);
    requirePolicyId(policyId);
    try (Connection connection = dataSource.getConnection();
         PreparedStatement query = connection.prepareStatement("""
             SELECT policy_id, version, name, description, content_hash,
                    document, current_state, staged_at, activated_at,
                    deprecated_at, rolled_back_at, rollback_target_version
             FROM tp_policy_versions
             WHERE workspace_id = ? AND policy_id = ?
             ORDER BY version DESC
             """)) {
      query.setString(1, workspaceId);
      query.setString(2, policyId);
      List<PolicyVersionRecord> list = new ArrayList<>();
      try (ResultSet rs = query.executeQuery()) {
        while (rs.next()) {
          list.add(mapRecord(rs));
        }
      }
      return Collections.unmodifiableList(list);
    } catch (SQLException failure) {
      throw new PostgresStateException("Failed to list policy versions", failure);
    }
  }

  @Override
  public List<RollbackRecord> rollbackHistory(String workspaceId, String policyId) {
    requireWorkspace(workspaceId);
    requirePolicyId(policyId);
    try (Connection connection = dataSource.getConnection();
         PreparedStatement query = connection.prepareStatement("""
             SELECT workspace_id, policy_id, from_version, to_version, occurred_at, actor_id, reason
             FROM tp_policy_lifecycle_transitions
             WHERE workspace_id = ? AND policy_id = ? AND transition_type = 'rollback'
             ORDER BY transition_sequence ASC
             """)) {
      query.setString(1, workspaceId);
      query.setString(2, policyId);
      List<RollbackRecord> list = new ArrayList<>();
      try (ResultSet rs = query.executeQuery()) {
        while (rs.next()) {
          list.add(new RollbackRecord(
              rs.getString("workspace_id"),
              rs.getString("policy_id"),
              rs.getInt("from_version"),
              rs.getInt("to_version"),
              rs.getTimestamp("occurred_at").toInstant(),
              rs.getString("actor_id"),
              rs.getString("reason")));
        }
      }
      return Collections.unmodifiableList(list);
    } catch (SQLException failure) {
      throw new PostgresStateException("Failed to query rollback history", failure);
    }
  }

  @Override
  public List<PolicyTransitionRecord> activationHistory(String workspaceId, String policyId) {
    requireWorkspace(workspaceId);
    requirePolicyId(policyId);
    try (Connection connection = dataSource.getConnection();
         PreparedStatement query = connection.prepareStatement("""
             SELECT transition_id, workspace_id, policy_id, transition_sequence, transition_type,
                    from_version, to_version, occurred_at, actor_id, reason
             FROM tp_policy_lifecycle_transitions
             WHERE workspace_id = ? AND policy_id = ?
             ORDER BY transition_sequence ASC
             """)) {
      query.setString(1, workspaceId);
      query.setString(2, policyId);
      List<PolicyTransitionRecord> list = new ArrayList<>();
      try (ResultSet rs = query.executeQuery()) {
        while (rs.next()) {
          Integer from = rs.getObject("from_version") != null ? rs.getInt("from_version") : null;
          list.add(new PolicyTransitionRecord(
              rs.getString("transition_id"),
              rs.getString("workspace_id"),
              rs.getString("policy_id"),
              rs.getLong("transition_sequence"),
              rs.getString("transition_type"),
              from,
              rs.getInt("to_version"),
              rs.getTimestamp("occurred_at").toInstant(),
              rs.getString("actor_id"),
              rs.getString("reason")));
        }
      }
      return Collections.unmodifiableList(list);
    } catch (SQLException failure) {
      throw new PostgresStateException("Failed to query activation history", failure);
    }
  }

  @Override
  public Set<String> listPolicyIds(String workspaceId) {
    requireWorkspace(workspaceId);
    try (Connection connection = dataSource.getConnection();
         PreparedStatement query = connection.prepareStatement("""
             SELECT DISTINCT policy_id FROM tp_policy_versions
             WHERE workspace_id = ?
             ORDER BY policy_id ASC
             """)) {
      query.setString(1, workspaceId);
      Set<String> set = new LinkedHashSet<>();
      try (ResultSet rs = query.executeQuery()) {
        while (rs.next()) {
          set.add(rs.getString("policy_id"));
        }
      }
      return Collections.unmodifiableSet(set);
    } catch (SQLException failure) {
      throw new PostgresStateException("Failed to list policy IDs", failure);
    }
  }

  /**
   * Serializes the namespace before looking for an active row, including the first
   * activation when SELECT FOR UPDATE alone would lock nothing. Hash collisions
   * only serialize unrelated policies; they cannot change the SQL workspace filter.
   */
  private static void lockPolicy(Connection connection, String workspaceId, String policyId) throws SQLException {
    try (PreparedStatement lock = connection.prepareStatement(
        "SELECT pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtext(?), pg_catalog.hashtext(?))")) {
      lock.setString(1, "tracepermit:policy:" + workspaceId);
      lock.setString(2, policyId);
      lock.setQueryTimeout(5);
      lock.execute();
    }
  }

  /** Owns a fresh/exclusive lease, never a caller-owned or thread-bound transaction. */
  private <T> T inTransaction(String message, SqlWork<T> work) {
    try (Connection connection = dataSource.getConnection()) {
      boolean originalAutoCommit = connection.getAutoCommit();
      int originalIsolation = connection.getTransactionIsolation();
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      connection.setAutoCommit(false);
      try {
        T result = work.run(connection);
        connection.commit();
        connection.setTransactionIsolation(originalIsolation);
        connection.setAutoCommit(originalAutoCommit);
        return result;
      } catch (RuntimeException | SQLException failure) {
        try {
          connection.rollback();
        } catch (SQLException rollbackFailure) {
          failure.addSuppressed(rollbackFailure);
        }
        // Do not enable auto-commit after failure: it could commit unrolled-back work.
        throw failure;
      }
    } catch (SQLException failure) {
      throw new PostgresStateException(message, failure);
    }
  }

  @FunctionalInterface
  private interface SqlWork<T> {
    T run(Connection connection) throws SQLException;
  }

  private Optional<PolicyVersionRecord> findVersion(
      Connection connection, String workspaceId, String policyId, int version) throws SQLException {
    try (PreparedStatement query = connection.prepareStatement("""
        SELECT policy_id, version, name, description, content_hash,
               document, current_state, staged_at, activated_at,
               deprecated_at, rolled_back_at, rollback_target_version
        FROM tp_policy_versions
        WHERE workspace_id = ? AND policy_id = ? AND version = ?
        """)) {
      query.setString(1, workspaceId);
      query.setString(2, policyId);
      query.setInt(3, version);
      try (ResultSet rs = query.executeQuery()) {
        if (!rs.next()) return Optional.empty();
        return Optional.of(mapRecord(rs));
      }
    }
  }

  private void verifySchemaVersion() {
    try (Connection connection = dataSource.getConnection();
         PreparedStatement query = connection.prepareStatement(
             "SELECT contract_version FROM tp_enforcement_schema_guard WHERE singleton = TRUE");
         ResultSet rows = query.executeQuery()) {
      if (!rows.next() || rows.getInt(1) != 9 || rows.next()) {
        throw new IllegalStateException("INCOMPATIBLE_DURABLE_STATE_SCHEMA");
      }
    } catch (SQLException failure) {
      throw new PostgresStateException("Failed to verify policy lifecycle schema", failure);
    }
  }

  private Optional<PolicyVersionRecord> findVersionForUpdate(
      Connection connection, String workspaceId, String policyId, int version) throws SQLException {
    try (PreparedStatement query = connection.prepareStatement("""
        SELECT policy_id, version, name, description, content_hash,
               document, current_state, staged_at, activated_at,
               deprecated_at, rolled_back_at, rollback_target_version
        FROM tp_policy_versions
        WHERE workspace_id = ? AND policy_id = ? AND version = ?
        FOR UPDATE
        """)) {
      query.setString(1, workspaceId);
      query.setString(2, policyId);
      query.setInt(3, version);
      try (ResultSet rs = query.executeQuery()) {
        if (!rs.next()) return Optional.empty();
        return Optional.of(mapRecord(rs));
      }
    }
  }

  private long getNextTransitionSequence(
      Connection connection, String workspaceId, String policyId) throws SQLException {
    try (PreparedStatement query = connection.prepareStatement("""
        SELECT COALESCE(MAX(transition_sequence), 0) + 1 AS next_seq
        FROM tp_policy_lifecycle_transitions
        WHERE workspace_id = ? AND policy_id = ?
        """)) {
      query.setString(1, workspaceId);
      query.setString(2, policyId);
      try (ResultSet rs = query.executeQuery()) {
        if (rs.next()) {
          return rs.getLong("next_seq");
        }
        return 1L;
      }
    }
  }

  private PolicyVersionRecord mapRecord(ResultSet rs) throws SQLException {
    String policyId = rs.getString("policy_id");
    int version = rs.getInt("version");
    String name = rs.getString("name");
    String description = rs.getString("description");
    String contentHash = rs.getString("content_hash");
    String documentJson = rs.getString("document");
    String stateStr = rs.getString("current_state");
    Instant stagedAt = rs.getTimestamp("staged_at").toInstant();
    Timestamp actTs = rs.getTimestamp("activated_at");
    Instant activatedAt = actTs != null ? actTs.toInstant() : null;
    Timestamp depTs = rs.getTimestamp("deprecated_at");
    Instant deprecatedAt = depTs != null ? depTs.toInstant() : null;
    Timestamp rbTs = rs.getTimestamp("rolled_back_at");
    Instant rolledBackAt = rbTs != null ? rbTs.toInstant() : null;
    Integer targetVersion = rs.getObject("rollback_target_version") != null
        ? rs.getInt("rollback_target_version")
        : null;

    PolicyState state = switch (stateStr) {
      case "staged" -> PolicyState.STAGED;
      case "active" -> PolicyState.ACTIVE;
      case "deprecated" -> PolicyState.DEPRECATED;
      case "rolled_back" -> PolicyState.ROLLED_BACK;
      default -> throw new IllegalStateException("Unknown policy state: " + stateStr);
    };

    JsonNode document = parseJson(documentJson);
    return new PolicyVersionRecord(
        policyId,
        version,
        name,
        description,
        state,
        contentHash,
        document,
        stagedAt,
        activatedAt,
        deprecatedAt,
        rolledBackAt,
        targetVersion);
  }

  private static String toJson(Object value) {
    try {
      return JsonSupport.MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("Cannot encode policy document.", exception);
    }
  }

  private static JsonNode parseJson(String value) {
    try {
      return JsonSupport.MAPPER.readTree(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Stored policy JSON is invalid.", exception);
    }
  }

  private void requireWorkspace(String workspaceId) {
    Objects.requireNonNull(workspaceId, "workspaceId must not be null");
    if (!WORKSPACE_PATTERN.matcher(workspaceId).matches()) {
      throw new IllegalArgumentException("Invalid workspaceId format: " + workspaceId);
    }
  }

  private void requirePolicyId(String policyId) {
    Objects.requireNonNull(policyId, "policyId must not be null");
    if (!POLICY_PATTERN.matcher(policyId).matches()) {
      throw new IllegalArgumentException("Invalid policyId format: " + policyId);
    }
  }

  private void requireVersion(int version) {
    if (version < 1) {
      throw new IllegalArgumentException("Version must be positive: " + version);
    }
  }

  private void requireReason(String reason) {
    Objects.requireNonNull(reason, "reason must not be null");
    if (reason.isBlank()) {
      throw new IllegalArgumentException("reason must not be blank");
    }
    if (reason.getBytes(StandardCharsets.UTF_8).length > MAX_REASON_BYTES) {
      throw new IllegalArgumentException("reason exceeds maximum byte length of " + MAX_REASON_BYTES);
    }
  }

  private void validateActor(String actorId) {
    if (actorId != null && !ACTOR_PATTERN.matcher(actorId).matches()) {
      throw new IllegalArgumentException("Invalid actorId format: " + actorId);
    }
  }
}
