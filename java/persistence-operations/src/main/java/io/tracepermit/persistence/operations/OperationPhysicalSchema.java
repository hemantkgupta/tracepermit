package io.tracepermit.persistence.operations;

import static io.tracepermit.application.BusinessOperationStorageException.Code.OPERATION_SCHEMA_NOT_READY;

import io.tracepermit.application.BusinessOperationStorageException;
import java.io.IOException;
import java.io.Reader;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Validate application DDL, not merely migration history. Read-only; never repairs a schema. */
final class OperationPhysicalSchema {
  private record Column(String name, String type, Integer length) {}

  private OperationPhysicalSchema() {}

  static void require(Connection connection, OperationDatabase.Engine engine, String version) throws SQLException {
    boolean pg = engine == OperationDatabase.Engine.POSTGRESQL;
    Map<String, List<Column>> tables = new HashMap<>();
    tables.put("tp_business_operations", List.of(varchar("operation_id", 36), varchar("scope_hash", 71),
        varchar("request_key_hash", 71), varchar("binding_hash", 71), varchar("snapshot_hash", 71), text("snapshot_json")));
    tables.put("tp_business_operation_facts", List.of(varchar("operation_id", 36), integer("sequence_number"), text("fact_json")));
    if (!"1".equals(version)) {
      tables.put("tp_operation_lifecycle_heads", List.of(varchar("operation_id", 36), varchar("snapshot_hash", 71), text("snapshot_json")));
      tables.put("tp_operation_lifecycle_facts", List.of(varchar("operation_id", 36), integer("sequence_number"),
          varchar("previous_snapshot_hash", 71), varchar("result_snapshot_hash", 71), text("fact_json"),
          varchar("actor_hash", 71), text("actor_json")));
    }
    if (Set.of("3", "4", "6").contains(version)) {
      tables.put("tp_operation_authority_controls", List.of(varchar("scope_hash", 71), varchar("profile_hash", 71),
          new Column("lock_marker", "smallint", null), new Column("recovery_generation", "bigint", null), varchar("control_state", 16)));
    }
    if (Set.of("4", "6").contains(version)) {
      tables.put("tp_operation_review_bindings", List.of(varchar("operation_id", 36),
          varchar("binding_hash", 71), text("binding_json")));
    }
    if ("6".equals(version)) {
      tables.put("tp_operation_admissions", List.of(varchar("operation_id", 36), varchar("permit_id", 36),
          varchar("attempt_id", 36), varchar("admission_hash", 71), text("admission_json")));
      tables.put("tp_operation_admission_outbox", List.of(varchar("operation_id", 36), varchar("outbox_hash", 71), text("outbox_json")));
    }
    String sql = pg ? """
        SELECT table_name, column_name, data_type, character_maximum_length, character_set_name,
               collation_name, is_nullable, column_default, is_generated, is_identity, udt_name
          FROM information_schema.columns WHERE table_schema = current_schema()
        """ : """
        SELECT table_name, column_name, data_type, character_maximum_length, character_set_name,
               collation_name, is_nullable, column_default, extra, 'NO', column_type
          FROM information_schema.columns WHERE table_schema = DATABASE()
        """;
    Map<String, Set<String>> observed = new HashMap<>();
    try (var statement = connection.prepareStatement(sql)) {
      statement.setQueryTimeout(5);
      try (var rows = statement.executeQuery()) {
        while (rows.next()) {
          String table = rows.getString(1);
          if (!tables.containsKey(table)) continue; // Flyway owns/validates its separate history table.
          String name = rows.getString(2);
          Column expected = tables.get(table).stream().filter(column -> column.name().equals(name))
              .findFirst().orElseThrow(OperationPhysicalSchema::invalid);
          if (!observed.computeIfAbsent(table, ignored -> new HashSet<>()).add(name)) throw invalid();
          String type = expected.type();
          String dataType = type.equals("varchar") ? (pg ? "character varying" : "varchar")
              : type.equals("text") ? (pg ? "text" : "longtext")
              : type.equals("integer") && !pg ? "int" : type;
          if (!dataType.equals(rows.getString(3)) || !"NO".equals(rows.getString(7))
              || !Objects.equals(name.equals("lock_marker") ? "0" : null, rows.getString(8))
              || !(pg ? "NEVER" : "").equals(rows.getString(9)) || !"NO".equals(rows.getString(10))) throw invalid();
          if (type.equals("varchar")) {
            if (rows.getLong(4) != expected.length() || rows.wasNull()
                || !Objects.equals(pg ? null : "ascii", rows.getString(5))
                || !(pg ? "C" : "ascii_bin").equals(rows.getString(6))
                || !(pg ? "varchar" : "varchar(" + expected.length() + ")").equals(rows.getString(11))) throw invalid();
          } else if (type.equals("text")) {
            if (!Objects.equals(pg ? null : "utf8mb4", rows.getString(5))
                || !Objects.equals(pg ? null : "utf8mb4_bin", rows.getString(6))
                || !(pg ? "text" : "longtext").equals(rows.getString(11))) throw invalid();
          } else {
            String rawType = pg ? switch (type) { case "smallint" -> "int2"; case "bigint" -> "int8"; default -> "int4"; }
                : dataType + "(?:\\([0-9]+\\))?";
            if (pg ? !rawType.equals(rows.getString(11)) : !rows.getString(11).matches(rawType)) throw invalid();
          }
        }
      }
    }
    for (var entry : tables.entrySet()) {
      if (!observed.getOrDefault(entry.getKey(), Set.of()).equals(
          entry.getValue().stream().map(Column::name).collect(java.util.stream.Collectors.toSet()))) throw invalid();
      requirePrimaryKey(connection, entry.getKey());
    }
    requireChecks(connection, pg, tables.keySet());
  }

  private static void requirePrimaryKey(Connection connection, String table) throws SQLException {
    List<String> expected = switch (table) {
      case "tp_business_operations" -> List.of("scope_hash", "request_key_hash");
      case "tp_business_operation_facts", "tp_operation_lifecycle_facts" -> List.of("operation_id", "sequence_number");
      case "tp_operation_authority_controls" -> List.of("scope_hash");
      default -> List.of("operation_id");
    };
    Map<Short, String> columns = new TreeMap<>();
    try (var rows = connection.getMetaData().getPrimaryKeys(connection.getCatalog(),
        connection.getMetaData().getDatabaseProductName().equals("PostgreSQL") ? "public" : null, table)) {
      while (rows.next()) {
        if (columns.put(rows.getShort("KEY_SEQ"), rows.getString("COLUMN_NAME")) != null) throw invalid();
      }
    }
    if (!List.copyOf(columns.values()).equals(expected)) throw invalid();
  }

  private static void requireChecks(Connection connection, boolean pg, Set<String> tables) throws SQLException {
    Map<String, Set<String>> checks = new HashMap<>();
    if (!pg) {
      // CHECK_CONSTRAINTS can hide every row from an otherwise valid table-scoped
      // runtime. SHOW CREATE needs only the existing table SELECT permission.
      // Never treat hidden metadata as an empty, acceptable constraint set.
      for (String table : tables) {
        try (var statement = connection.createStatement()) {
          statement.setQueryTimeout(5);
          // 'tables' comes only from the closed, internal table-name inventory above.
          try (var rows = statement.executeQuery("SHOW CREATE TABLE `" + table + "`")) {
            if (!rows.next() || !table.equals(rows.getString(1))) throw invalid();
            try (Reader reader = rows.getCharacterStream(2)) {
              if (reader == null) throw invalid();
              char[] buffer = new char[65537];
              int count = 0;
              while (count < buffer.length) {
                int read = reader.read(buffer, count, buffer.length - count);
                if (read == -1) break;
                if (read == 0) throw invalid();
                count += read;
              }
              if (count > 65536) throw invalid();
              checks.put(table, mariaDeclaredChecks(table, new String(buffer, 0, count)));
            } catch (IOException failure) {
              throw invalid();
            }
            if (rows.next()) throw invalid();
          }
        }
      }
    } else {
      String sql = """
        SELECT t.relname, pg_get_constraintdef(c.oid), c.convalidated, c.condeferrable, c.condeferred, c.contype
          FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
            JOIN pg_namespace n ON n.oid = t.relnamespace WHERE n.nspname = current_schema()
        """;
      try (var statement = connection.prepareStatement(sql)) {
        statement.setQueryTimeout(5);
        try (var rows = statement.executeQuery()) {
          while (rows.next()) {
            String table = rows.getString(1);
            if (!tables.contains(table)) continue;
            if (!rows.getBoolean(3) || rows.getBoolean(4) || rows.getBoolean(5)) throw invalid();
            if (!"c".equals(rows.getString(6))) continue;
            if (!checks.computeIfAbsent(table, ignored -> new HashSet<>()).add(rows.getString(2))) throw invalid();
          }
        }
      }
    }
    for (String table : tables) {
      Set<String> expected = switch (table) {
        case "tp_business_operations", "tp_operation_lifecycle_heads" -> Set.of(bytes("snapshot_json", 262144, pg));
        case "tp_business_operation_facts" -> Set.of(bytes("fact_json", 262144, pg), pg
            ? "CHECK (((sequence_number >= 1) AND (sequence_number <= 3)))" : "`sequence_number` between 1 and 3");
        case "tp_operation_lifecycle_facts" -> Set.of(bytes("fact_json", 262144, pg), bytes("actor_json", 4096, pg), pg
            ? "CHECK ((sequence_number >= 4))" : "`sequence_number` >= 4");
        case "tp_operation_review_bindings" -> Set.of(bytes("binding_json", 8192, pg));
        case "tp_operation_admissions" -> Set.of(bytes("admission_json", 16384, pg));
        case "tp_operation_admission_outbox" -> Set.of(bytes("outbox_json", 4096, pg));
        case "tp_operation_authority_controls" -> pg ? Set.of("CHECK ((lock_marker = 0))",
            "CHECK (((recovery_generation >= 1) AND (recovery_generation <= '9007199254740991'::bigint)))",
            "CHECK (((control_state)::text = ANY ((ARRAY['staged'::character varying, 'ready'::character varying, 'quarantined'::character varying])::text[])))")
            : Set.of("`lock_marker` = 0", "`recovery_generation` between 1 and 9007199254740991",
                "`control_state` in ('staged','ready','quarantined')");
        default -> throw invalid();
      };
      if (!expected.equals(checks.getOrDefault(table, Set.of()))) throw invalid();
    }
  }

  /** Closed SHOW CREATE lexer, not a general SQL parser or expression normalizer. */
  static Set<String> mariaDeclaredChecks(String table, String ddl) {
    if (ddl == null || ddl.length() > 65536 || !ddl.startsWith("CREATE TABLE `" + table + "` (\n")) throw invalid();
    Set<String> checks = new HashSet<>();
    int depth = 0;
    int start = -1;
    for (int index = 0; index < ddl.length(); index++) {
      char value = ddl.charAt(index);
      if (value == '\'' || value == '`') {
        char quote = value;
        boolean closed = false;
        while (++index < ddl.length()) {
          value = ddl.charAt(index);
          // No qualified declaration uses escaped quotes/identifiers. Refuse
          // alternative modes/escapes rather than guess what the server meant.
          if (value == '\\') throw invalid();
          if (value == quote) {
            if (index + 1 < ddl.length() && ddl.charAt(index + 1) == quote) throw invalid();
            closed = true;
            break;
          }
        }
        if (!closed) throw invalid();
        continue;
      }
      if (value == '"' || value == '#' || (value == '/' && index + 1 < ddl.length() && ddl.charAt(index + 1) == '*')
          || (value == '-' && index + 1 < ddl.length() && ddl.charAt(index + 1) == '-')) throw invalid();
      if (Character.isLetter(value) || value == '_') {
        int end = index + 1;
        while (end < ddl.length() && (Character.isLetterOrDigit(ddl.charAt(end)) || ddl.charAt(end) == '_')) end++;
        String token = ddl.substring(index, end);
        if ("COMMENT".equals(token)) throw invalid(); // No comment-backed constraint decoys.
        if ("CHECK".equals(token)) {
          if (depth != 1 || start != -1 || !ddl.startsWith(" (", end)) throw invalid();
          start = end + 1;
          index = start; // The CHECK opening parenthesis is handled here.
          depth++;
          continue;
        }
        index = end - 1;
      } else if (value == '(') {
        depth++;
      } else if (value == ')') {
        if (--depth < 0) throw invalid();
        if (start != -1 && depth == 1) {
          if (!checks.add(ddl.substring(start + 1, index))) throw invalid();
          start = -1;
        }
      }
    }
    if (depth != 0 || start != -1) throw invalid();
    return Set.copyOf(checks);
  }

  private static String bytes(String column, int maximum, boolean pg) {
    return pg ? "CHECK ((octet_length(" + column + ") <= " + maximum + "))"
        : "octet_length(`" + column + "`) <= " + maximum;
  }

  private static Column varchar(String name, int length) { return new Column(name, "varchar", length); }
  private static Column text(String name) { return new Column(name, "text", null); }
  private static Column integer(String name) { return new Column(name, "integer", null); }
  private static BusinessOperationStorageException invalid() {
    return new BusinessOperationStorageException(OPERATION_SCHEMA_NOT_READY);
  }
}
