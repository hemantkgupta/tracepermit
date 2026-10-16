package io.tracepermit.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.identity.SourceAttestationVerifier;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.security.KeyPairGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FileProcessObservationRecorderTest {
  private static final Instant NOW = Instant.parse("2030-01-01T00:01:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  @TempDir Path temporary;

  @Test
  void recordsOnlyAllowlistedStableFactsAndCreatesPrivateUniqueFiles() throws Exception {
    Path directory = privateDirectory();
    try (FileProcessObservationRecorder first = recorder(directory);
        FileProcessObservationRecorder second = recorder(directory)) {
      assertThat(first.path()).isNotEqualTo(second.path());
      assertThat(Files.getPosixFilePermissions(first.path()))
          .isEqualTo(PosixFilePermissions.fromString("rw-------"));
      ProcessObservation observed = observation();
      assertThat(Files.size(first.path())).isZero();
      first.record(observed, request());
      JsonNode record = JsonSupport.MAPPER.readTree(Files.readString(first.path()));
      assertThat(record.size()).isEqualTo(11);
      assertThat(record.path("requestHash").asText())
          .isEqualTo(SourceAttestationVerifier.hashRequest(request()));
      assertThat(record.path("sequence").asInt()).isEqualTo(1);
      assertThat(record.path("capturedAt").asText()).isEqualTo(NOW.toString());
      assertThat(record.path("stage").asText()).isEqualTo("stability_checked_before_forward");
      assertThat(record.path("process").size()).isEqualTo(5);
      assertThat(record.path("process").path("executableName").asText()).isEqualTo("fixture-client");
      assertThat(record.path("process").path("executableHash").asText())
          .isEqualTo("sha256:" + "a".repeat(64));
      assertThat(record.path("process").path("startedAt").asText())
          .isEqualTo(NOW.minusSeconds(60).toString());
      assertThat(record.path("terminalAttached").asBoolean()).isFalse();
      assertThat(record.toString()).doesNotContain("private-device-id", "sensitive-path",
          "bearer", "credential", "arguments", "attestation", "executablePath", "sensitive-argument-value");
      assertThat(Files.size(second.path())).isZero();
    }
  }

  @Test
  void requiresAnExistingAbsoluteCanonicalOwnerOnlyDirectory() throws Exception {
    Path directory = privateDirectory();
    assertUnavailable(() -> recorder(Path.of("relative-directory")));
    assertUnavailable(() -> recorder(directory.resolve("missing")));
    Path link = directory.getParent().resolve("directory-link");
    Files.createSymbolicLink(link, directory);
    assertUnavailable(() -> recorder(link));
    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-xr-x"));
    assertUnavailable(() -> recorder(directory));
    try (var files = Files.list(directory)) {
      assertThat(files.toList()).isEmpty();
    }
  }

  @Test
  void refusesPermissionChangesAndDoesNotSilentlyDisableCapture() throws Exception {
    Path directory = privateDirectory();
    try (FileProcessObservationRecorder recorder = recorder(directory)) {
      Files.setPosixFilePermissions(recorder.path(), PosixFilePermissions.fromString("rw-r--r--"));
      assertUnavailable(() -> recorder.record(observation(), request()));
      assertThat(Files.size(recorder.path())).isZero();
      Files.setPosixFilePermissions(recorder.path(), PosixFilePermissions.fromString("rw-------"));
      Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-xr-x"));
      assertUnavailable(() -> recorder.record(observation(), request()));
    }
  }

  @Test
  void refusesUnlinkedReplacedSymlinkedOrHardlinkedCaptureFiles() throws Exception {
    Path directory = privateDirectory();
    try (FileProcessObservationRecorder recorder = recorder(directory)) {
      Path moved = directory.resolve("moved.jsonl");
      Files.move(recorder.path(), moved);
      assertUnavailable(() -> recorder.record(observation(), request()));
      Files.createSymbolicLink(recorder.path(), moved);
      assertUnavailable(() -> recorder.record(observation(), request()));
      Files.delete(recorder.path());
      Files.writeString(recorder.path(), "replacement");
      Files.setPosixFilePermissions(recorder.path(), PosixFilePermissions.fromString("rw-------"));
      assertUnavailable(() -> recorder.record(observation(), request()));
      assertThat(Files.readString(recorder.path())).isEqualTo("replacement");
      assertThat(Files.size(moved)).isZero();
    }
    try (FileProcessObservationRecorder recorder = recorder(directory)) {
      Files.createLink(directory.resolve("extra-link.jsonl"), recorder.path());
      assertUnavailable(() -> recorder.record(observation(), request()));
    }
  }

  @Test
  void refusesDirectoryReplacementAndUnexpectedFileGrowth() throws Exception {
    Path directory = privateDirectory();
    try (FileProcessObservationRecorder recorder = recorder(directory)) {
      Files.writeString(recorder.path(), "external data");
      assertUnavailable(() -> recorder.record(observation(), request()));
      Path moved = directory.resolveSibling("moved-directory");
      Files.move(directory, moved);
      Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(
          PosixFilePermissions.fromString("rwx------")));
      assertUnavailable(() -> recorder.record(observation(), request()));
    }
  }

  @Test
  void boundsRecordCount() throws Exception {
    try (FileProcessObservationRecorder recorder = recorder(privateDirectory())) {
      for (int index = 0; index < FileProcessObservationRecorder.MAX_RECORDS; index++) {
        recorder.record(observation(), request());
      }
      assertUnavailable(() -> {
        recorder.record(observation(), request());
      });
      assertThat(Files.readAllLines(recorder.path())).hasSize(32);
      JsonNode last = JsonSupport.MAPPER.readTree(Files.readAllLines(recorder.path()).get(31));
      assertThat(last.path("sequence").asInt()).isEqualTo(32);
    }
  }

  @Test
  void encodedRecordsAlsoHaveAnIndependentByteLimit() throws Exception {
    ProcessObservation expanded = new ProcessObservation(42, "private-device-id", "linux",
        "development", Character.toString(1).repeat(128), Path.of("/sensitive-path/fixture-client"),
        "sha256:" + "a".repeat(64), Character.toString(1).repeat(128), NOW.minusSeconds(60), false);
    try (FileProcessObservationRecorder recorder = recorder(privateDirectory())) {
      int accepted = 0;
      while (accepted < FileProcessObservationRecorder.MAX_RECORDS) {
        try {
          recorder.record(expanded, request());
          accepted++;
        } catch (ConnectorException failure) {
          assertThat(failure.code()).isEqualTo("ENDPOINT_CAPTURE_UNAVAILABLE");
          break;
        }
      }
      assertThat(accepted).isBetween(1, FileProcessObservationRecorder.MAX_RECORDS - 1);
      assertThat(Files.size(recorder.path())).isLessThanOrEqualTo(64 * 1024);
      assertThat(Files.readAllLines(recorder.path())).hasSize(accepted);
    }
  }

  @Test
  void mainDefaultsToNoCaptureAndRejectsInvalidExplicitConfigurationWithoutDetails()
      throws Exception {
    Path directory = privateDirectory();
    Path key = temporary.resolve("key.pk8");
    Files.write(key, KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate().getEncoded());
    Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-------"));
    var environment = new HashMap<String, String>();
    environment.put("TRACEPERMIT_ENDPOINT_ISSUER", "tracepermit-endpoint:fixture");
    environment.put("TRACEPERMIT_ENDPOINT_KEY_ID", "fixture-key");
    environment.put("TRACEPERMIT_ENDPOINT_DEVICE_ID", "fixture-device");
    environment.put("TRACEPERMIT_ENDPOINT_PRIVATE_KEY_PKCS8_PATH", key.toString());
    environment.put("TRACEPERMIT_GATEWAY_URL", "http://127.0.0.1:1/mcp");
    environment.put("TRACEPERMIT_CONNECTOR_BEARER_TOKEN", "synthetic-fixture-token");
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
    assertThat(EndpointConnectorMain.run(environment, new ByteArrayInputStream(new byte[0]),
        output, new PrintStream(diagnostics))).isZero();
    try (var files = Files.list(directory)) { assertThat(files.toList()).isEmpty(); }
    environment.put("TRACEPERMIT_ENDPOINT_OBSERVATION_DIRECTORY", directory.toString());
    assertThat(EndpointConnectorMain.run(environment, new ByteArrayInputStream(new byte[0]),
        output, new PrintStream(diagnostics))).isZero();
    try (var files = Files.list(directory)) { assertThat(files.toList()).hasSize(1); }
    environment.put("TRACEPERMIT_ENDPOINT_OBSERVATION_DIRECTORY", "sensitive-relative-location");
    assertThat(EndpointConnectorMain.run(environment, new ByteArrayInputStream(new byte[0]),
        output, new PrintStream(diagnostics))).isEqualTo(2);
    assertThat(output.toString()).isEmpty();
    assertThat(diagnostics.toString()).isEqualTo("TracePermit endpoint connector: CONFIGURATION_INVALID\n");
    environment.put("TRACEPERMIT_ENDPOINT_OBSERVATION_DIRECTORY", "");
    assertThat(EndpointConnectorMain.run(environment, new ByteArrayInputStream(new byte[0]),
        output, new PrintStream(new ByteArrayOutputStream()))).isEqualTo(2);
  }

  @Test
  void closedCaptureDoesNotReopenOrOverwriteEarlierFacts() throws Exception {
    FileProcessObservationRecorder recorder = recorder(privateDirectory());
    recorder.record(observation(), request());
    recorder.close();
    recorder.close();
    assertUnavailable(() -> recorder.record(observation(), request()));
    assertThat(Files.readAllLines(recorder.path())).hasSize(1);
  }

  private Path privateDirectory() throws IOException {
    Path directory = temporary.toRealPath().resolve("capture");
    return Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(
        PosixFilePermissions.fromString("rwx------")));
  }

  private static FileProcessObservationRecorder recorder(Path directory) {
    return new FileProcessObservationRecorder(directory, CLOCK);
  }

  private static JsonNode request() {
    var request = JsonSupport.MAPPER.createObjectNode().put("jsonrpc", "2.0")
        .put("id", 1).put("method", "tools/call");
    request.putObject("params").put("sentinel", "sensitive-argument-value");
    return request;
  }

  private static ProcessObservation observation() {
    return new ProcessObservation(42, "private-device-id", "linux", "development",
        "fixture-client", Path.of("/sensitive-path/fixture-client"), "sha256:" + "a".repeat(64),
        "fixture-parent", NOW.minusSeconds(60), false);
  }

  private static void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    assertThatThrownBy(action).isInstanceOf(ConnectorException.class)
        .hasMessage("The requested private process-observation capture is unavailable.")
        .extracting(error -> ((ConnectorException) error).code())
        .isEqualTo("ENDPOINT_CAPTURE_UNAVAILABLE");
  }
}
