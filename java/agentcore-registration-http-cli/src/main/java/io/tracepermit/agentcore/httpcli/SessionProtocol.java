package io.tracepermit.agentcore.httpcli;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Strict bounded framing for worker and public lifecycle receipts. */
final class SessionProtocol {
  static final String PROFILE = "tracepermit-local-synthetic-registration-http-session-v1";
  static final int MAXIMUM_RECEIPT_BYTES = 1_024;
  private static final Set<String> FIELDS =
      Set.of(
          "schemaVersion",
          "profile",
          "phase",
          "outcome",
          "host",
          "port",
          "executionEnabled",
          "rollbackConfirmed");
  private static final ObjectMapper JSON =
      new ObjectMapper(
              JsonFactory.builder()
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(2)
                          .maxNameLength(32)
                          .maxStringLength(128)
                          .maxNumberLength(8)
                          .build())
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private SessionProtocol() {}

  static byte[] bytes(SessionReceipt receipt) {
    try {
      var json = JSON.createObjectNode();
      json.put("schemaVersion", "1.0");
      json.put("profile", PROFILE);
      json.put("phase", receipt.phase());
      json.put("outcome", receipt.outcome());
      if (receipt.host() == null) json.putNull("host");
      else json.put("host", receipt.host());
      if (receipt.port() == null) json.putNull("port");
      else json.put("port", receipt.port());
      json.put("executionEnabled", false);
      json.put("rollbackConfirmed", false);
      byte[] value = JSON.writeValueAsBytes(json);
      if (value.length < 1 || value.length > MAXIMUM_RECEIPT_BYTES) {
        throw new IllegalStateException("SESSION_RECEIPT_TOO_LARGE");
      }
      return value;
    } catch (RuntimeException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new IllegalStateException("SESSION_RECEIPT_INVALID", null);
    }
  }

  static Optional<SessionReceipt> readFrame(InputStream source) {
    try {
      int first = source.read();
      if (first < 0) return Optional.empty();
      byte[] lengthBytes = source.readNBytes(3);
      if (lengthBytes.length != 3) throw new IllegalArgumentException("SESSION_FRAME_INVALID");
      int length =
          (first << 24)
              | ((lengthBytes[0] & 255) << 16)
              | ((lengthBytes[1] & 255) << 8)
              | (lengthBytes[2] & 255);
      if (length < 1 || length > MAXIMUM_RECEIPT_BYTES) {
        throw new IllegalArgumentException("SESSION_FRAME_INVALID");
      }
      byte[] value = source.readNBytes(length);
      if (value.length != length) throw new IllegalArgumentException("SESSION_FRAME_INVALID");
      JsonNode json = JSON.readTree(value);
      if (json == null || !json.isObject()) throw new IllegalArgumentException("SESSION_RECEIPT_INVALID");
      var names = new HashSet<String>();
      json.fieldNames().forEachRemaining(names::add);
      if (!names.equals(FIELDS)
          || !text(json, "schemaVersion").equals("1.0")
          || !text(json, "profile").equals(PROFILE)
          || !json.path("executionEnabled").isBoolean()
          || json.path("executionEnabled").booleanValue()
          || !json.path("rollbackConfirmed").isBoolean()
          || json.path("rollbackConfirmed").booleanValue()) {
        throw new IllegalArgumentException("SESSION_RECEIPT_INVALID");
      }
      String host = null;
      if (!json.path("host").isNull()) host = text(json, "host");
      Integer port = null;
      if (!json.path("port").isNull()) {
        if (!json.path("port").isIntegralNumber() || !json.path("port").canConvertToInt()) {
          throw new IllegalArgumentException("SESSION_RECEIPT_INVALID");
        }
        port = json.path("port").intValue();
      }
      return Optional.of(
          new SessionReceipt(text(json, "phase"), text(json, "outcome"), host, port));
    } catch (RuntimeException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new IllegalArgumentException("SESSION_FRAME_INVALID", null);
    }
  }

  static boolean send(OutputStream target, SessionReceipt receipt, Duration budget) {
    byte[] value = bytes(receipt);
    var failed = new AtomicBoolean();
    Thread writer =
        daemon(
            "registration-http-receipt",
            () -> {
              try {
                var output = new DataOutputStream(target);
                output.writeInt(value.length);
                output.write(value);
                output.flush();
                if (target instanceof java.io.PrintStream print && print.checkError()) {
                  failed.set(true);
                }
              } catch (Exception failure) {
                failed.set(true);
              }
            });
    try {
      writer.join(Math.max(1, budget.toMillis()));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    }
    return !writer.isAlive() && !failed.get();
  }

  static Thread daemon(String name, Runnable action) {
    Thread thread = new Thread(action, name);
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  private static String text(JsonNode json, String field) {
    JsonNode value = json.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException("SESSION_RECEIPT_INVALID");
    }
    return value.textValue();
  }
}
