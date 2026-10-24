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
      "JSON_PARSE_ERROR", "MISSING_REQUEST_ID_OR_HASH", "TIMEOUT", "OTHER_OPERATION_FAILURE",
      "HTTP_IO_RESET", "HTTP_IO_EOF", "HTTP_IO_CLOSED", "HTTP_IO_CONNECT", "HTTP_IO_HEADER_EOF", "HTTP_IO_OTHER",
      "CANNOT_QUERY_STATUS_MISSING_ID", "CANNOT_REVIEW_MISSING_ID_OR_HASH", "CANNOT_RESUME_MISSING_ID_OR_HASH");
  private static final List<String> DATABASE_MARKERS = List.of(
      "deadlock detected", "duplicate key value violates unique constraint",
      "canceling statement due to statement timeout", "could not serialize access",
      "remaining connection slots are reserved", "too many clients already");
  private static final List<String> DATABASE_CODES = List.of(
      "PG_DEADLOCK", "PG_UNIQUE_VIOLATION", "PG_STATEMENT_TIMEOUT",
      "PG_SERIALIZATION_FAILURE", "PG_CONNECTION_LIMIT", "PG_CONNECTION_LIMIT_CLIENTS");
  private final AtomicIntegerArray databaseCounts = new AtomicIntegerArray(DATABASE_CODES.size());
  private final AtomicIntegerArray admissionCounts = new AtomicIntegerArray(4);

  static boolean isFixedOperationCode(String code) {
    return code != null && code.length() <= 80
        && (FIXED_CODES.contains(code) || HTTP_STATUS.matcher(code).matches());
  }

  /** Inspect bounded cause types/fixed markers, never retain or return exception text. */
  static String transportCategory(Throwable failure) {
    boolean eof = false, closed = false, connect = false, reset = false, headerEof = false;
    var seen = Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
    for (int depth = 0; failure != null && depth < 8 && seen.add(failure); depth++, failure = failure.getCause()) {
      eof |= failure instanceof java.io.EOFException;
      closed |= failure instanceof java.nio.channels.ClosedChannelException;
      connect |= failure instanceof java.net.ConnectException;
      if (failure instanceof java.net.SocketException) {
        reset |= "Connection reset".equals(failure.getMessage()) || "Connection reset by peer".equals(failure.getMessage());
      }
      headerEof |= failure instanceof java.io.IOException
          && "HTTP/1.1 header parser received no bytes".equals(failure.getMessage());
    }
    return reset ? "HTTP_IO_RESET" : connect ? "HTTP_IO_CONNECT" : eof ? "HTTP_IO_EOF"
        : closed ? "HTTP_IO_CLOSED" : headerEof ? "HTTP_IO_HEADER_EOF" : "HTTP_IO_OTHER";
  }

  void admissionRejected(boolean barrier, int active, int queued) {
    admissionCounts.getAndUpdate(barrier ? 1 : 0, value -> Math.min(65_535, value + 1));
    admissionCounts.getAndUpdate(2, value -> Math.max(value, Math.min(64, active)));
    admissionCounts.getAndUpdate(3, value -> Math.max(value, Math.min(1024, queued)));
  }

  Map<String, Integer> admissionCategories() {
    if (admissionCounts.get(0) == 0 && admissionCounts.get(1) == 0) return Map.of();
    return Collections.unmodifiableMap(new TreeMap<>(Map.of(
        "HTTP_EXCHANGE_REJECTED", admissionCounts.get(0), "QUIESCENCE_TASK_REJECTED", admissionCounts.get(1),
        "REJECT_MAX_ACTIVE", admissionCounts.get(2), "REJECT_MAX_QUEUED", admissionCounts.get(3))));
  }

  static Map<String, Integer> operationCategories(List<String> codes) {
    Map<String, Integer> counts = new TreeMap<>();
    for (String code : codes) {
      String category = isFixedOperationCode(code) ? code : "OTHER_OPERATION_FAILURE";
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
