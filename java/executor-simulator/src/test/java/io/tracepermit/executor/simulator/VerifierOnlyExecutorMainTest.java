package io.tracepermit.executor.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.JsonSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class VerifierOnlyExecutorMainTest {
  @TempDir Path directory;

  @Test
  void rejectsInvalidCommandsWithoutPrintingInput() throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    int status = VerifierOnlyExecutorMain.run(
        new String[] {"--unsafe"},
        new ByteArrayInputStream("credential=must-not-leak".getBytes(StandardCharsets.UTF_8)),
        output);

    assertThat(status).isEqualTo(2);
    assertThat(json(output).path("code").asText()).isEqualTo("INVALID_EXECUTOR_COMMAND");
    assertThat(output.toString(StandardCharsets.UTF_8)).doesNotContain("credential", "must-not-leak");
  }

  @Test
  void checkValidatesTrustOfflineAndEmitsOnlySafeVersionMetadata() throws IOException {
    Path config = ExecutorTestFixtures.configuration(
        directory, ExecutorTestFixtures.keyPair(), 5432, "tracepermit", "tracepermit",
        "runtime-generated-test-password");
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    int status = VerifierOnlyExecutorMain.run(
        new String[] {"--check", config.toString()}, InputStreamFixtures.empty(), output);

    JsonNode response = json(output);
    assertThat(status).isZero();
    assertThat(response.path("status").asText()).isEqualTo("ready");
    assertThat(response.path("mode").asText()).isEqualTo("verifier_only_simulator");
    assertThat(response.path("algorithm").asText()).isEqualTo("EdDSA");
    assertThat(response.path("keyFingerprint").asText()).matches("sha256:[a-f0-9]{64}");
    assertThat(response.path("routeCount").asInt()).isOne();
    assertThat(output.toString(StandardCharsets.UTF_8)).doesNotContain(
        "runtime-generated-test-password", "tracepermit.spki", "database.secret", "postgres");
  }

  @Test
  void executeRejectsMalformedInputBeforeDatabaseAccessAndRedactsIt() throws IOException {
    Path config = ExecutorTestFixtures.configuration(
        directory, ExecutorTestFixtures.keyPair(), 1, "tracepermit", "tracepermit",
        "runtime-generated-test-password");
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    int status = VerifierOnlyExecutorMain.run(
        new String[] {"--execute", config.toString()},
        new ByteArrayInputStream("token=must-not-leak".getBytes(StandardCharsets.UTF_8)), output);

    assertThat(status).isEqualTo(4);
    assertThat(json(output).path("code").asText()).isEqualTo("EXECUTION_REJECTED");
    assertThat(output.toString(StandardCharsets.UTF_8)).doesNotContain("token", "must-not-leak");
  }

  @Test
  void invalidConfigurationAndDependencyFailuresUseBoundedErrors() throws IOException {
    ByteArrayOutputStream invalidOutput = new ByteArrayOutputStream();
    int invalid = VerifierOnlyExecutorMain.run(
        new String[] {"--check", directory.resolve("private-key-secret").toString()},
        InputStreamFixtures.empty(), invalidOutput);
    assertThat(invalid).isEqualTo(2);
    assertThat(json(invalidOutput).path("code").asText()).isEqualTo("INVALID_EXECUTOR_CONFIGURATION");
    assertThat(invalidOutput.toString(StandardCharsets.UTF_8)).doesNotContain("private-key-secret");

    var keys = ExecutorTestFixtures.keyPair();
    Path config = ExecutorTestFixtures.configuration(
        directory, keys, 1, "tracepermit", "tracepermit", "runtime-generated-test-password");
    JsonNode event = ExecutorTestFixtures.action();
    String permit = ExecutorTestFixtures.signer(
        keys, ExecutorTestFixtures.clock(java.time.Instant.now()), new java.util.concurrent.atomic.AtomicInteger())
        .issue(event, ExecutorTestFixtures.evaluation(event), ExecutorTestFixtures.AUDIENCE);
    ObjectNode request = JsonSupport.MAPPER.createObjectNode();
    request.put("schemaVersion", "1.0");
    request.put("permit", permit);
    request.set("action", event);
    ByteArrayOutputStream unavailableOutput = new ByteArrayOutputStream();
    int unavailable = VerifierOnlyExecutorMain.run(
        new String[] {"--execute", config.toString()},
        new ByteArrayInputStream(JsonSupport.MAPPER.writeValueAsBytes(request)), unavailableOutput);
    assertThat(unavailable).isEqualTo(3);
    assertThat(json(unavailableOutput).path("code").asText())
        .isEqualTo("EXECUTOR_DEPENDENCY_UNAVAILABLE");
    assertThat(unavailableOutput.toString(StandardCharsets.UTF_8)).doesNotContain(permit);
  }

  @Test
  void closedOutputStreamCannotTurnFailureIntoExecution() {
    int status = VerifierOnlyExecutorMain.run(
        new String[] {"--invalid"}, InputStreamFixtures.empty(), new OutputStream() {
          @Override public void write(int value) throws IOException { throw new IOException("closed"); }
        });
    assertThat(status).isEqualTo(2);
  }

  private static JsonNode json(ByteArrayOutputStream output) throws IOException {
    return JsonSupport.MAPPER.readTree(output.toByteArray());
  }

  private static final class InputStreamFixtures {
    private InputStreamFixtures() {}

    static ByteArrayInputStream empty() {
      return new ByteArrayInputStream(new byte[0]);
    }
  }
}
