package io.tracepermit.agentcore.cli;

import static io.tracepermit.agentcore.cli.SyntheticIdentity.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.Permission;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class BoundaryTest {
  @Test void completeOfflineConfigNoDnsNetworkOrJdbc() throws Exception {
    var frame = frames(bytes(config())); var calls = new AtomicInteger();
    // Start providers/drivers before installing the guard. No driver may be called during check-config.
    DriverManager.getDrivers();
    var driver = new java.sql.Driver() {
      @Override public java.sql.Connection connect(String url, java.util.Properties info) {
        calls.incrementAndGet(); throw new AssertionError("JDBC_FORBIDDEN");
      }
      @Override public boolean acceptsURL(String url) { return true; }
      @Override public java.sql.DriverPropertyInfo[] getPropertyInfo(String url, java.util.Properties info) { return new java.sql.DriverPropertyInfo[0]; }
      @Override public int getMajorVersion() { return 1; }
      @Override public int getMinorVersion() { return 0; }
      @Override public boolean jdbcCompliant() { return false; }
      @Override public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }
    };
    DriverManager.registerDriver(driver);
    guarded(frame, calls);
    DriverManager.deregisterDriver(driver);
    assertThat(calls.get()).isZero();
  }

  @SuppressWarnings("removal")
  private static void guarded(byte[] frame, AtomicInteger calls) {
    SecurityManager original = System.getSecurityManager();
    System.setSecurityManager(new SecurityManager() {
      @Override public void checkPermission(Permission permission) { }
      @Override public void checkConnect(String host, int port) { calls.incrementAndGet(); throw new AssertionError("NETWORK_OR_DNS_FORBIDDEN"); }
      @Override public void checkListen(int port) { calls.incrementAndGet(); throw new AssertionError("LISTENER_FORBIDDEN"); }
    });
    try {
      assertThat(Worker.execute(new ByteArrayInputStream(frame), "check-config").outcome()).isEqualTo("CONFIG_VALID");
      assertThat(Worker.execute(new ByteArrayInputStream(new byte[]{0}), "check-config").outcome()).isEqualTo("INPUT_INVALID");
      var remote = config(); ((ObjectNode) remote.get("replay")).put("jdbcUrl", "jdbc:postgresql://remote.invalid/db");
      assertThat(Worker.execute(new ByteArrayInputStream(frames(bytes(remote))), "check-config").outcome()).isEqualTo("CONFIG_INVALID");
    } finally { System.setSecurityManager(original); }
  }

  @ParameterizedTest @ValueSource(strings = {"localhost", "::1", "127.0.0.2", "2130706433", "127.0.0.1,remote", "remote.invalid", "127.0.0.1?socketFactory=x"})
  void alternateHostNeverAccepted(String host) {
    var c = config(); ((ObjectNode)c.get("replay")).put("host", host); invalid(c);
  }

  @ParameterizedTest @ValueSource(strings = {"url", "jdbcUrl", "properties", "socketFactory", "options", "sslFactory", "privateKey", "humanAuthorization", "authenticated"})
  void operatorSchemaClosedAtEveryLevel(String field) {
    for (String level : List.of("", "provider", "enrollment", "target", "replay", "operations")) {
      var c = config(); ((ObjectNode)(level.isEmpty() ? c : c.get(level))).put(field, "forbidden"); invalid(c);
    }
  }

  @ParameterizedTest @ValueSource(strings = {"profile", "algorithm", "jwtType", "origin", "kid", "publicKey", "capacity", "budget", "scope", "client", "tenant", "target", "sameStore", "catalog", "port", "engine", "surrogate", "control", "issuerBytes"})
  void invalidTrustAndDriverSettingsFailOffline(String kind) {
    var c = config(); var p = (ObjectNode)c.get("provider"); var e = (ObjectNode)c.get("enrollment"); var db = (ObjectNode)c.get("replay");
    switch (kind) {
      case "profile" -> c.put("profile", "live");
      case "algorithm" -> p.put("algorithm", "none");
      case "jwtType" -> p.put("jwtType", "JWT");
      case "origin" -> p.put("origin", "MCP");
      case "kid" -> p.put("keyId", " ");
      case "publicKey" -> p.put("publicKeySpki", "private-key-rejected");
      case "capacity" -> c.put("replayCapacity", 0);
      case "budget" -> c.put("replayDmlMillis", 0);
      case "scope" -> p.putArray("supportedScopes").add("operations.other");
      case "client" -> e.put("clientId", "other");
      case "tenant" -> e.put("tenantId", "other");
      case "target" -> ((ObjectNode)c.get("target")).put("repository", "remote/production");
      case "sameStore" -> c.set("operations", c.get("replay").deepCopy());
      case "catalog" -> db.put("catalog", "db?socketFactory=escape");
      case "port" -> db.put("port", 65536);
      case "engine" -> db.put("engine", "MYSQL");
      case "surrogate" -> e.put("workspaceId", "\ud800");
      case "control" -> e.put("workspaceId", "control\n");
      case "issuerBytes" -> p.put("issuer", ISSUER + "/" + "x".repeat(256));
      default -> throw new AssertionError();
    }
    invalid(c);
  }

  @Test void duplicateUtf8TrailingTruncatedOversizedAndExtraFrames() {
    byte[] valid = bytes(config());
    byte[][] malformed = {
      frames("{\"profile\":1,\"profile\":2}".getBytes(StandardCharsets.UTF_8)),
      frames(new byte[]{(byte)0xc3, 0x28}), frames("{} {}".getBytes(StandardCharsets.UTF_8)),
      new byte[]{0,1,0,1}, new byte[]{-1,-1,-1,-1}, new byte[0],
      Arrays.copyOf(frames(valid), valid.length + 3), frames(valid, valid)
    };
    for (byte[] frame : malformed)
      assertThat(Worker.execute(new ByteArrayInputStream(frame), "check-config").exitCode()).isNotZero();
    // A binary frame needs no newline. Whitespace is part of JSON only, never a frame delimiter.
    assertThat(Worker.execute(new ByteArrayInputStream(frames(valid)), "check-config").outcome()).isEqualTo("CONFIG_VALID");
  }

  @ParameterizedTest @ValueSource(strings = {"schema", "key", "title", "body", "unknown", "duplicate", "utf8", "trailing", "surrogate"})
  void requestMustValidateBeforeAnyFactoryCall(String kind) {
    var body = request();
    switch (kind) {
      case "schema" -> body.put("schemaVersion", "2.0");
      case "key" -> body.put("clientRequestKey", "key/escape");
      case "title" -> body.put("title", " ");
      case "body" -> body.put("body", "x".repeat(16_385));
      case "unknown" -> body.put("verified", true);
      case "surrogate" -> body.put("title", "\udc00");
      default -> { }
    }
    byte[] request = switch (kind) {
      case "duplicate" -> "{\"title\":1,\"title\":2}".getBytes(StandardCharsets.UTF_8);
      case "utf8" -> new byte[]{(byte)0xff};
      case "trailing" -> "{} {}".getBytes(StandardCharsets.UTF_8);
      case "surrogate" -> "{\"schemaVersion\":\"1.0\",\"clientRequestKey\":\"key\",\"title\":\"\\udc00\"}".getBytes(StandardCharsets.UTF_8);
      default -> bytes(body);
    };
    var input = frames(bytes(config()), "Bearer unavailable".getBytes(StandardCharsets.UTF_8), request);
    assertThat(Worker.execute(new ByteArrayInputStream(input), "register-once").outcome()).isEqualTo("INPUT_INVALID");
  }

  @Test void minimalReceiptAndFixedErrorsCannotSmuggleWorkerText() {
    for (String code : List.of("CONFIG_VALID", "INPUT_INVALID", "CONFIG_INVALID", "NOT_READY", "REJECTED", "UNKNOWN", "USAGE")) {
      var r = Receipt.failure(code); assertThat(Receipt.decode(r.bytes(), r.exitCode())).isEqualTo(r);
      assertThat(Receipt.decode(r.bytes(), r.exitCode() == 0 ? 1 : 0).outcome()).isEqualTo("UNKNOWN");
    }
    var r = new Receipt("CREATED", true, "operation-1"); assertThat(Receipt.decode(r.bytes(), 0)).isEqualTo(r);
    assertThat(Receipt.decode("canary".getBytes(StandardCharsets.UTF_8), 0).outcome()).isEqualTo("UNKNOWN");
    assertThatThrownBy(() -> new Receipt("CREATED", false, "operation")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Receipt("UNKNOWN", true, "operation")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Receipt("canary", null, null)).isInstanceOf(IllegalArgumentException.class);
    var failure = new CliFailure("UNKNOWN"); failure.initCause(new Exception("canary")); failure.addSuppressed(new Exception("canary"));
    assertThat(failure.getCause()).isNull(); assertThat(failure.getSuppressed()).isEmpty();
    assertThat(Worker.execute(new ByteArrayInputStream(new byte[0]), "execute").outcome()).isEqualTo("USAGE");
  }

  @Test void receiptStringTypesClosedAgainstNumericAndTypeCoercion() {
    String base = "{\"schemaVersion\":\"1.0\",\"profile\":\"%s\",\"outcome\":\"CREATED\",\"executionEnabled\":false,\"created\":true,\"operationId\":\"op-1\"}"
        .formatted(Protocol.PROFILE);
    assertThat(Receipt.decode(base.getBytes(StandardCharsets.UTF_8), 0).outcome()).isEqualTo("CREATED");

    // Single-property type coercion vectors must all be rejected as UNKNOWN
    String numVersion = base.replace("\"schemaVersion\":\"1.0\"", "\"schemaVersion\":1.0");
    assertThat(Receipt.decode(numVersion.getBytes(StandardCharsets.UTF_8), 0).outcome())
        .as("numeric schemaVersion 1.0 must not coerce to string").isEqualTo("UNKNOWN");

    String boolVersion = base.replace("\"schemaVersion\":\"1.0\"", "\"schemaVersion\":true");
    assertThat(Receipt.decode(boolVersion.getBytes(StandardCharsets.UTF_8), 0).outcome())
        .as("boolean schemaVersion").isEqualTo("UNKNOWN");

    String numProfile = base.replace("\"profile\":\"" + Protocol.PROFILE + "\"", "\"profile\":100");
    assertThat(Receipt.decode(numProfile.getBytes(StandardCharsets.UTF_8), 0).outcome())
        .as("numeric profile").isEqualTo("UNKNOWN");

    String boolProfile = base.replace("\"profile\":\"" + Protocol.PROFILE + "\"", "\"profile\":false");
    assertThat(Receipt.decode(boolProfile.getBytes(StandardCharsets.UTF_8), 0).outcome())
        .as("boolean profile").isEqualTo("UNKNOWN");

    String numOutcome = base.replace("\"outcome\":\"CREATED\"", "\"outcome\":0");
    assertThat(Receipt.decode(numOutcome.getBytes(StandardCharsets.UTF_8), 0).outcome())
        .as("numeric outcome").isEqualTo("UNKNOWN");

    String boolOutcome = base.replace("\"outcome\":\"CREATED\"", "\"outcome\":true");
    assertThat(Receipt.decode(boolOutcome.getBytes(StandardCharsets.UTF_8), 0).outcome())
        .as("boolean outcome").isEqualTo("UNKNOWN");

    String numExecution = base.replace("\"executionEnabled\":false", "\"executionEnabled\":0");
    assertThat(Receipt.decode(numExecution.getBytes(StandardCharsets.UTF_8), 0).outcome())
        .as("numeric executionEnabled").isEqualTo("UNKNOWN");

    String strExecution = base.replace("\"executionEnabled\":false", "\"executionEnabled\":\"false\"");
    assertThat(Receipt.decode(strExecution.getBytes(StandardCharsets.UTF_8), 0).outcome())
        .as("string executionEnabled").isEqualTo("UNKNOWN");

    String numCreated = base.replace("\"created\":true", "\"created\":1");
    assertThat(Receipt.decode(numCreated.getBytes(StandardCharsets.UTF_8), 0).outcome())
        .as("numeric created").isEqualTo("UNKNOWN");

    String strCreated = base.replace("\"created\":true", "\"created\":\"true\"");
    assertThat(Receipt.decode(strCreated.getBytes(StandardCharsets.UTF_8), 0).outcome())
        .as("string created").isEqualTo("UNKNOWN");

    String numOpId = base.replace("\"operationId\":\"op-1\"", "\"operationId\":12345");
    assertThat(Receipt.decode(numOpId.getBytes(StandardCharsets.UTF_8), 0).outcome())
        .as("numeric operationId").isEqualTo("UNKNOWN");

    String boolOpId = base.replace("\"operationId\":\"op-1\"", "\"operationId\":false");
    assertThat(Receipt.decode(boolOpId.getBytes(StandardCharsets.UTF_8), 0).outcome())
        .as("boolean operationId").isEqualTo("UNKNOWN");
  }

  private static void invalid(ObjectNode config) {
    assertThat(Worker.execute(new ByteArrayInputStream(frames(bytes(config))), "check-config").outcome()).isEqualTo("CONFIG_INVALID");
  }
}
