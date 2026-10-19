package io.tracepermit.persistence.postgres;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;

/** Explicit public-schema installation and non-migrating operator startup (not a recovery fence). */
public final class PostgresDatabaseLifecycle {
  // Database-local, session-scoped; cooperating initializers fail fast rather than wait indefinitely.
  static final long INITIALIZATION_LOCK = 0x54504d4954475241L;
  public static final int CURRENT_VERSION = 10;
  private static final Set<String> PREDECESSOR_VERSIONS = Set.of("1", "2", "3", "4", "5", "6", "7", "8", "9");
  private static final Set<String> VERSIONS = Set.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
  private static final String CANCELLATION_TRIGGER =
      "tp_approval_transitions:tp_10_approval_cancellation_guard:tp_validate_approval_cancellation";
  private static final Set<String> TABLES = Set.of(
      "flyway_schema_history", "tp_enforcement_schema_guard", "tp_consumed_execution_permits",
      "tp_execution_attempts", "tp_execution_transitions", "tp_evidence_streams", "tp_evidence_events",
      "tp_evidence_outbox", "tp_approval_requests", "tp_approval_transitions", "tp_evidence_anchors",
      "tp_reconciliation_queue", "tp_policy_versions", "tp_active_policies",
      "tp_policy_lifecycle_transitions", "tp_approval_action_snapshots", "tp_approval_policy_bindings",
      "tp_recovery_quarantine");
  private static final List<String> TRIGGERS = List.of(
      "tp_execution_transitions:tp_execution_transition_guard:tp_validate_execution_transition",
      "tp_execution_attempts:tp_execution_attempt_update_guard:tp_reject_direct_attempt_mutation",
      "tp_evidence_events:tp_evidence_append_guard:tp_validate_evidence_append",
      "tp_execution_transitions:tp_execution_transition_append_only:tp_reject_append_only_mutation",
      "tp_evidence_events:tp_evidence_event_append_only:tp_reject_append_only_mutation",
      "tp_approval_transitions:tp_approval_transition_guard:tp_validate_approval_transition",
      "tp_approval_requests:tp_approval_request_update_guard:tp_reject_direct_approval_mutation",
      "tp_approval_transitions:tp_approval_transition_append_only:tp_reject_append_only_mutation",
      "tp_evidence_anchors:tp_evidence_anchor_append_only:tp_reject_append_only_mutation",
      "tp_reconciliation_queue:tp_reconciliation_queue_guard:tp_validate_reconciliation_queue",
      "tp_policy_versions:tp_policy_version_guard_trigger:tp_guard_policy_version_mutation",
      "tp_policy_lifecycle_transitions:tp_policy_lifecycle_transitions_immutable:tp_reject_append_only_mutation",
      "tp_approval_action_snapshots:tp_approval_action_snapshot_immutable:tp_reject_append_only_mutation",
      "tp_approval_policy_bindings:tp_approval_policy_binding_immutable:tp_reject_append_only_mutation",
      "tp_recovery_quarantine:tp_recovery_quarantine_guard:tp_guard_recovery_quarantine",
      "tp_recovery_quarantine:tp_recovery_quarantine_no_truncate:tp_guard_recovery_quarantine",
      "tp_consumed_execution_permits:tp_00_recovery_permit_guard:tp_require_recovery_ready",
      "tp_execution_attempts:tp_00_recovery_attempt_guard:tp_require_recovery_ready",
      "tp_approval_requests:tp_00_recovery_request_guard:tp_require_recovery_ready",
      "tp_approval_transitions:tp_00_recovery_review_guard:tp_require_recovery_ready");

  private PostgresDatabaseLifecycle() {}

  /** First installation only. Never clean, repair, baseline or adopt existing application objects. */
  public static void initializeEmpty(DataSource source) {
    Objects.requireNonNull(source);
    try (Connection lock = source.getConnection(); var query = lock.createStatement()) {
      query.setQueryTimeout(5);
      try (var rows = query.executeQuery("SELECT pg_catalog.pg_try_advisory_lock(" + INITIALIZATION_LOCK + ")")) {
        rows.next();
        if (!rows.getBoolean(1)) throw new IllegalStateException("DATABASE_INITIALIZATION_BUSY");
      }
      try {
        try (var rows = query.executeQuery("""
          SELECT EXISTS (
            SELECT 1 FROM pg_catalog.pg_namespace
             WHERE nspname !~ '^pg_' AND nspname NOT IN ('public', 'information_schema')
            UNION ALL SELECT 1 FROM pg_catalog.pg_class c
              JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public'
            UNION ALL SELECT 1 FROM pg_catalog.pg_proc p
              JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public'
            UNION ALL SELECT 1 FROM pg_catalog.pg_type t
              JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace WHERE n.nspname = 'public'
            UNION ALL SELECT 1 FROM pg_catalog.pg_collation x
              JOIN pg_catalog.pg_namespace n ON n.oid = x.collnamespace WHERE n.nspname = 'public'
            UNION ALL SELECT 1 FROM pg_catalog.pg_conversion x
              JOIN pg_catalog.pg_namespace n ON n.oid = x.connamespace WHERE n.nspname = 'public'
            UNION ALL SELECT 1 FROM pg_catalog.pg_operator x
              JOIN pg_catalog.pg_namespace n ON n.oid = x.oprnamespace WHERE n.nspname = 'public'
            UNION ALL SELECT 1 FROM pg_catalog.pg_opclass x
              JOIN pg_catalog.pg_namespace n ON n.oid = x.opcnamespace WHERE n.nspname = 'public'
            UNION ALL SELECT 1 FROM pg_catalog.pg_opfamily x
              JOIN pg_catalog.pg_namespace n ON n.oid = x.opfnamespace WHERE n.nspname = 'public'
            UNION ALL SELECT 1 FROM pg_catalog.pg_ts_config x
              JOIN pg_catalog.pg_namespace n ON n.oid = x.cfgnamespace WHERE n.nspname = 'public'
            UNION ALL SELECT 1 FROM pg_catalog.pg_ts_dict x
              JOIN pg_catalog.pg_namespace n ON n.oid = x.dictnamespace WHERE n.nspname = 'public'
            UNION ALL SELECT 1 FROM pg_catalog.pg_ts_parser x
              JOIN pg_catalog.pg_namespace n ON n.oid = x.prsnamespace WHERE n.nspname = 'public'
            UNION ALL SELECT 1 FROM pg_catalog.pg_ts_template x
              JOIN pg_catalog.pg_namespace n ON n.oid = x.tmplnamespace WHERE n.nspname = 'public'
            UNION ALL SELECT 1 FROM pg_catalog.pg_event_trigger
            UNION ALL SELECT 1 FROM pg_catalog.pg_foreign_server
            UNION ALL SELECT 1 FROM pg_catalog.pg_largeobject_metadata
            UNION ALL SELECT 1 FROM pg_catalog.pg_extension WHERE extname <> 'plpgsql'
          ) OR NOT EXISTS (SELECT 1 FROM pg_catalog.pg_namespace WHERE nspname = 'public')
          """)) {
          rows.next();
          if (rows.getBoolean(1)) throw new IllegalStateException("DATABASE_INITIALIZATION_REQUIRES_EMPTY_DATABASE");
        }
        if (!"public".equals(lock.getSchema())) throw new IllegalStateException("DATABASE_SCHEMA_NOT_READY");
        flyway(source).migrate();
        validateExisting(source);
      } finally {
        // Do not rely solely on lease close: a future pool might retain the physical session.
        query.execute("SELECT pg_catalog.pg_advisory_unlock(" + INITIALIZATION_LOCK + ")");
      }
    } catch (SQLException failure) {
      throw new PostgresStateException("Database initialization failed", failure);
    }
  }

  /** Reads metadata only; never invokes migrate/repair/clean/baseline. Not archive-authenticity proof. */
  public static void validateExisting(DataSource source) {
    validateExisting(source, CURRENT_VERSION);
  }

  /** Explicit V9-to-V10 only. Services must be stopped; no automatic repair, retry or downgrade. */
  public static void upgradeFromV9(DataSource source) {
    Objects.requireNonNull(source);
    try (Connection lock = source.getConnection(); var query = lock.createStatement()) {
      query.setQueryTimeout(5);
      if (!"public".equals(lock.getSchema())) throw new IllegalStateException("DATABASE_SCHEMA_NOT_READY");
      try (var rows = query.executeQuery("SELECT pg_catalog.pg_try_advisory_lock(" + INITIALIZATION_LOCK + ")")) {
        rows.next();
        if (!rows.getBoolean(1)) throw new IllegalStateException("DATABASE_UPGRADE_BUSY");
      }
      try {
        try (var rows = query.executeQuery("""
            SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_namespace
              WHERE nspname !~ '^pg_' AND nspname NOT IN ('public', 'information_schema'))
            """)) {
          rows.next();
          if (rows.getBoolean(1)) throw new IllegalStateException("DATABASE_SCHEMA_NOT_READY");
        }
        // Ownership is deliberately stronger than runtime DML permissions. Reject RLS-filtered views
        // and unexpected public tables rather than qualify an unknown installation by partial counts.
        try (var rows = query.executeQuery("""
            SELECT c.relname, pg_catalog.pg_has_role(current_user, c.relowner, 'USAGE') AND NOT c.relrowsecurity
            FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public' AND c.relkind = 'r'
            """)) {
          Set<String> tables = new HashSet<>();
          while (rows.next()) {
            if (!rows.getBoolean(2)) throw new IllegalStateException("DATABASE_UPGRADE_OWNER_REQUIRED");
            tables.add(rows.getString(1));
          }
          if (!tables.equals(TABLES)) throw new IllegalStateException("DATABASE_SCHEMA_NOT_READY");
        }
        validateExisting(source, 9);
        // Migration V10 repeats ready/unresolved-work checks under conflicting table locks.
        // Its one PostgreSQL transaction atomically changes DDL, guard and Flyway history.
        flyway(source).migrate();
        validateExisting(source);
      } finally {
        query.execute("SELECT pg_catalog.pg_advisory_unlock(" + INITIALIZATION_LOCK + ")");
      }
    } catch (SQLException failure) {
      throw new PostgresStateException("Database upgrade failed", failure);
    }
  }

  private static void validateExisting(DataSource source, int expectedVersion) {
    Objects.requireNonNull(source);
    try (Connection connection = source.getConnection(); var query = connection.createStatement()) {
      connection.setReadOnly(true);
      connection.setAutoCommit(false);
      if (!"public".equals(connection.getSchema())) throw new IllegalStateException("DATABASE_SCHEMA_NOT_READY");
      query.setQueryTimeout(5);
      Set<String> found = new HashSet<>();
      try (var rows = query.executeQuery("""
          SELECT c.relname FROM pg_catalog.pg_class c
          JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
          WHERE n.nspname = 'public' AND c.relkind = 'r'
          """)) {
        while (rows.next()) found.add(rows.getString(1));
      }
      if (!found.containsAll(TABLES)) throw new IllegalStateException("DATABASE_SCHEMA_NOT_READY");
      try (var rows = query.executeQuery(
          "SELECT contract_version FROM public.tp_enforcement_schema_guard WHERE singleton = TRUE")) {
        if (!rows.next() || rows.getInt(1) != expectedVersion || rows.next()) {
          throw new IllegalStateException("INCOMPATIBLE_DURABLE_STATE_SCHEMA");
        }
      }
      PostgresRecoveryQuarantine.read(connection, "");
      Set<String> triggers = new HashSet<>();
      try (var rows = query.executeQuery("""
          SELECT c.relname || ':' || t.tgname || ':' || p.proname
          FROM pg_catalog.pg_trigger t
          JOIN pg_catalog.pg_class c ON c.oid = t.tgrelid
          JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
          JOIN pg_catalog.pg_proc p ON p.oid = t.tgfoid
          JOIN pg_catalog.pg_namespace f ON f.oid = p.pronamespace
          WHERE n.nspname = 'public' AND f.nspname = 'public'
            AND NOT t.tgisinternal AND t.tgenabled IN ('O', 'A')
          """)) {
        while (rows.next()) triggers.add(rows.getString(1));
      }
      if (!triggers.containsAll(TRIGGERS)) throw new IllegalStateException("DATABASE_SCHEMA_NOT_READY");
      if (expectedVersion == CURRENT_VERSION && !triggers.contains(CANCELLATION_TRIGGER)) {
        throw new IllegalStateException("DATABASE_SCHEMA_NOT_READY");
      }
      connection.rollback();
    } catch (SQLException failure) {
      throw new PostgresStateException("Database validation failed", failure);
    }
    // Only the explicit predecessor path resolves V10 above its target; ordinary startup
    // retains full validation with no ignored pending/future migrations.
    var validator = expectedVersion == 9
        ? Flyway.configure().configuration(flyway(source).getConfiguration()).target("9").load() : flyway(source);
    validator.validate();
    Set<String> versions = new HashSet<>();
    for (var migration : validator.info().all()) {
      if (expectedVersion == 9 && migration.getVersion() != null
          && "10".equals(migration.getVersion().getVersion())
          && migration.getState() == MigrationState.ABOVE_TARGET) continue;
      if (migration.getVersion() == null || migration.getState() != MigrationState.SUCCESS
          || !versions.add(migration.getVersion().getVersion())) {
        throw new IllegalStateException("DATABASE_SCHEMA_NOT_READY");
      }
    }
    if (!versions.equals(expectedVersion == 9 ? PREDECESSOR_VERSIONS : VERSIONS)) {
      throw new IllegalStateException("DATABASE_SCHEMA_NOT_READY");
    }
  }

  private static Flyway flyway(DataSource source) {
    // Empty logger selection uses Flyway's public configuration API: no JDBC URL/SQL diagnostics.
    return Flyway.configure().dataSource(source).loggers(new String[0])
        .locations(PostgresDurableEnforcementState.MIGRATION_LOCATION)
        .defaultSchema("public").schemas("public").createSchemas(false)
        .validateMigrationNaming(true).ignoreMigrationPatterns(new String[0])
        .initSql("SET statement_timeout = '10s'; SET lock_timeout = '5s'")
        .lockRetryCount(5).load();
  }
}
