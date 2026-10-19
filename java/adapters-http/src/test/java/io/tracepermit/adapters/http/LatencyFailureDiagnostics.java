package io.tracepermit.adapters.http;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.regex.Pattern;

/** Test-harness-only failure categories. Never retain or publish raw error/log text. */
final class LatencyFailureDiagnostics {
  private static final Pattern HTTP_STATUS = Pattern.compile(
      "(?:HTTP_REVIEW_STATUS|MCP_(?:INIT|NOTIF|REQUEST|STATUS|RESUME)_HTTP_STATUS)_[1-5][0-9]{2}");
  private static final Set<String> FIXED_CODES = Set.of(
      "TOOL_ERROR_APPROVAL_NOT_EXECUTABLE", "TOOL_ERROR_SECURITY_STATE_UNAVAILABLE",
      "OPERATION_FAILED_IOException", "OPERATION_FAILED_ConnectException",
      "OPERATION_FAILED_IllegalStateException", "OPERATION_FAILED_IllegalArgumentException",
      "JSON_PARSE_ERROR", "MISSING_REQUEST_ID_OR_HASH", "TIMEOUT");
  private static final List<String> DATABASE_MARKERS = List.of(
      "deadlock detected", "duplicate key value violates unique constraint",
      "canceling statement due to statement timeout", "could not serialize access",
      "remaining connection slots are reserved", "too many clients already");
  private static final List<String> DATABASE_CODES = List.of(
      "PG_DEADLOCK", "PG_UNIQUE_VIOLATION", "PG_STATEMENT_TIMEOUT",
      "PG_SERIALIZATION_FAILURE", "PG_CONNECTION_LIMIT", "PG_CONNECTION_LIMIT_CLIENTS");
  private final AtomicIntegerArray databaseCounts = new AtomicIntegerArray(DATABASE_CODES.size());

  static Map<String, Integer> operationCategories(List<String> codes) {
    Map<String, Integer> counts = new TreeMap<>();
    for (String code : codes) {
      String category = code != null && code.length() <= 80
          && (HTTP_STATUS.matcher(code).matches() || FIXED_CODES.contains(code))
          ? code : "OTHER_OPERATION_FAILURE";
      if (!counts.containsKey(category) && counts.size() >= 8) category = "OTHER_OPERATION_FAILURE";
      counts.merge(category, 1, (left, right) -> Math.min(65_535, left + right));
    }
    return Collections.unmodifiableMap(counts);
  }

  void observeDatabaseLog(String text) {
    // Search only fixed phrases; statement text, parameters and identifiers are discarded.
    for (int index = 0; index < DATABASE_MARKERS.size(); index++) {
      if (text.contains(DATABASE_MARKERS.get(index))) {
        databaseCounts.getAndUpdate(index, value -> Math.min(65_535, value + 1));
      }
    }
  }

  Map<String, Integer> databaseCategories() {
    Map<String, Integer> counts = new TreeMap<>();
    for (int index = 0; index < DATABASE_CODES.size(); index++) {
      int count = databaseCounts.get(index);
      if (count > 0) counts.put(DATABASE_CODES.get(index), count);
    }
    return Collections.unmodifiableMap(counts);
  }
}
