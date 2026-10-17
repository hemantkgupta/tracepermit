package io.tracepermit.adapters.http;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.ApprovalHistoryQuery;
import io.tracepermit.execution.ApprovalRequestState;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Set;

/** Strict metadata query and unsigned continuation position. Never supplies read authority. */
record ApprovalHistoryParameters(ApprovalHistoryQuery query, String contextHash) {
  private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  static ApprovalHistoryParameters parse(String rawQuery, String tenant, String principal, String owner) {
    try {
      var values = new HashMap<String, String>();
      if (rawQuery != null) {
        if (rawQuery.isEmpty() || rawQuery.length() > 4096) throw invalid();
        String[] pairs = rawQuery.split("&", -1);
        if (pairs.length > 5) throw invalid();
        for (String pair : pairs) {
          int separator = pair.indexOf('=');
          if (separator < 1) throw invalid();
          String name = URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8);
          String value = URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
          if (!Set.of("limit", "status", "toolName", "approvalRequestId", "cursor").contains(name)
              || value.isEmpty() || values.putIfAbsent(name, value) != null) throw invalid();
        }
      }
      String limitText = values.getOrDefault("limit", "50");
      if (!limitText.matches("[1-9][0-9]?")) throw invalid();
      int limit = Integer.parseInt(limitText);
      String status = values.get("status"), tool = values.get("toolName"), id = values.get("approvalRequestId");
      // Validate filters before cursor decoding; no free text or caller-selected identity.
      new ApprovalHistoryQuery(limit, status, tool, id, null, null);
      var context = JsonSupport.MAPPER.createObjectNode().put("tenant", tenant).put("principal", principal)
          .put("owner", owner).put("status", status).put("toolName", tool).put("approvalRequestId", id);
      String contextHash = CanonicalJson.sha256(context);
      Instant before = null;
      String after = null;
      if (values.containsKey("cursor")) {
        String encoded = values.get("cursor");
        if (encoded.length() > 2048 || !encoded.matches("[A-Za-z0-9_-]+")) throw invalid();
        byte[] bytes = Base64.getUrlDecoder().decode(encoded);
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(encoded)) throw invalid();
        JsonNode cursor = JSON.readTree(bytes);
        if (!cursor.isObject() || cursor.size() != 4 || !cursor.path("schemaVersion").isTextual()
            || !"1.0".equals(cursor.path("schemaVersion").asText())
            || !cursor.path("createdAt").isTextual() || !cursor.path("afterRequestId").isTextual()
            || !cursor.path("contextHash").isTextual()
            || !contextHash.equals(cursor.path("contextHash").asText())) throw invalid();
        before = Instant.parse(cursor.path("createdAt").asText());
        after = cursor.path("afterRequestId").asText();
      }
      return new ApprovalHistoryParameters(new ApprovalHistoryQuery(limit, status, tool, id, before, after), contextHash);
    } catch (Exception failure) {
      throw invalid(); // Bounded error; never include query, cursor or underlying diagnostic.
    }
  }

  String nextCursor(ApprovalRequestState last) {
    var cursor = JsonSupport.MAPPER.createObjectNode().put("schemaVersion", "1.0")
        .put("createdAt", last.binding().createdAt().toString())
        .put("afterRequestId", last.binding().approvalRequestId()).put("contextHash", contextHash);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(
        CanonicalJson.canonicalize(cursor).getBytes(StandardCharsets.UTF_8));
  }

  private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid approval history query."); }
}
