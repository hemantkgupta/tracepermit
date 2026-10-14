package io.tracepermit.endpoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.JsonSupport;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

final class StdioMcpServerTest {
  private static final String SECRET = "sentinel-bearer-secret-never-output";
  private static final Instant NOW = Instant.parse("2030-01-01T00:00:30Z");

  @Test
  void processesTheSupportedSubsetSequentiallyPreservesIdsAndStopsCleanlyAtEof()
      throws Exception {
    List<JsonNode> forwarded = new ArrayList<>();
    GatewayTransport transport = (request, token, proof) -> {
      forwarded.add(request.deepCopy());
      return request.has("id")
          ? Optional.of(response(request.get("id")))
          : Optional.empty();
    };
    String input = String.join("\n",
        "{\"jsonrpc\":\"2.0\",\"id\":\"init-1\",\"method\":\"initialize\",\"params\":{}}",
        "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}",
        "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}",
        "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"aws.logs.filter_log_events\",\"arguments\":{}}}");

    RunResult result = run(input, transport);

    assertThat(result.exitCode()).isZero();
    assertThat(result.messages()).extracting(item -> item.path("id").asText())
        .containsExactly("init-1", "2", "3");
    assertThat(forwarded).extracting(item -> item.path("method").asText())
        .containsExactly(
            "initialize", "notifications/initialized", "tools/list", "tools/call");
    assertThat(result.stderr()).isEmpty();
  }

  @Test
  void returnsBoundedErrorsForMalformedInvalidAndUnsupportedMessagesThenContinues()
      throws Exception {
    AtomicInteger forwards = new AtomicInteger();
    GatewayTransport transport = (request, token, proof) -> {
      forwards.incrementAndGet();
      return Optional.of(response(request.get("id")));
    };
    String input = String.join("\n",
        "{",
        "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"resources/list\",\"params\":{}}",
        "{\"jsonrpc\":\"2.0\",\"id\":true,\"method\":\"tools/list\",\"params\":{}}",
        "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/list\",\"params\":{},\"identity\":{}}",
        "{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tools/list\",\"params\":{}}");

    RunResult result = run(input, transport);

    assertThat(result.exitCode()).isZero();
    assertThat(result.messages()).hasSize(5);
    assertThat(result.messages().subList(0, 4))
        .extracting(item -> item.path("error").path("data").path("tracepermitCode").asText())
        .containsExactly(
            "MALFORMED_JSON_RPC",
            "UNSUPPORTED_MCP_METHOD",
            "INVALID_JSON_RPC",
            "INVALID_JSON_RPC");
    assertThat(result.messages().get(4).path("id").asInt()).isEqualTo(10);
    assertThat(forwards).hasValue(1);
  }

  @Test
  void rejectsAnOversizedFrameBeforeUnboundedAllocation() throws Exception {
    String oversized = "x".repeat(StdioMcpServer.MAX_FRAME_BYTES + 1);

    RunResult result = run(oversized, (request, token, proof) -> {
      throw new AssertionError("Oversized input must not be forwarded.");
    });

    assertThat(result.exitCode()).isEqualTo(2);
    assertThat(result.messages()).singleElement().satisfies(message -> {
      assertThat(message.path("id").isNull()).isTrue();
      assertThat(message.path("error").path("data").path("tracepermitCode").asText())
          .isEqualTo("FRAME_TOO_LARGE");
    });
    assertThat(result.stdout().length()).isLessThan(1024);
  }

  @Test
  void gatewayFailuresAndCorrelationErrorsNeverExposeSecretsOrRawArguments()
      throws Exception {
    List<String> proofs = new ArrayList<>();
    GatewayTransport rejected = (request, token, proof) -> {
      proofs.add(proof);
      throw new ConnectorException(
          "GATEWAY_UNAVAILABLE", "Failure containing " + SECRET + " must stay internal.");
    };
    String sensitiveRequest =
        "{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"tools/call\",\"params\":{\"name\":\"aws.logs.filter_log_events\",\"arguments\":{\"filterPattern\":\"sentinel-raw-argument\"}}}";

    RunResult rejectedResult = run(sensitiveRequest, rejected);
    RunResult wrongIdResult = run(
        "{\"jsonrpc\":\"2.0\",\"id\":12,\"method\":\"tools/list\",\"params\":{}}",
        (request, token, proof) -> Optional.of(response(JsonSupport.MAPPER.getNodeFactory().numberNode(99))));

    String captured = rejectedResult.stdout() + rejectedResult.stderr()
        + wrongIdResult.stdout() + wrongIdResult.stderr();
    assertThat(captured)
        .doesNotContain(SECRET, "sentinel-raw-argument", proofs.get(0));
    assertThat(rejectedResult.messages().get(0)
        .path("error").path("data").path("tracepermitCode").asText())
        .isEqualTo("GATEWAY_UNAVAILABLE");
    assertThat(wrongIdResult.messages().get(0)
        .path("error").path("data").path("tracepermitCode").asText())
        .isEqualTo("INVALID_GATEWAY_RESPONSE");
  }

  @Test
  void acceptsCrLfAndAFinalFrameWithoutANewline() throws Exception {
    String input =
        "{\"jsonrpc\":\"2.0\",\"id\":21,\"method\":\"tools/list\",\"params\":{}}\r\n"
            + "{\"jsonrpc\":\"2.0\",\"id\":22,\"method\":\"tools/list\",\"params\":{}}";

    RunResult result = run(input, (request, token, proof) ->
        Optional.of(response(request.get("id"))));

    assertThat(result.messages()).extracting(item -> item.path("id").asInt())
        .containsExactly(21, 22);
  }

  @Test
  void rejectsWrongRequestFormsAndBoundsUnexpectedGatewayFailures() throws Exception {
    String wrongForms = String.join("\n",
        "{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"params\":{}}",
        "{\"jsonrpc\":\"2.0\",\"id\":31,\"method\":\"notifications/initialized\",\"params\":{}}");
    RunResult invalid = run(wrongForms, (request, token, proof) -> {
      throw new AssertionError("Invalid forms must not be forwarded.");
    });
    RunResult empty = run(
        "{\"jsonrpc\":\"2.0\",\"id\":32,\"method\":\"tools/list\",\"params\":{}}",
        (request, token, proof) -> Optional.empty());
    RunResult unexpected = run(
        "{\"jsonrpc\":\"2.0\",\"id\":33,\"method\":\"tools/list\",\"params\":{}}",
        (request, token, proof) -> {
          throw new IllegalStateException("sentinel-unexpected-gateway-detail");
        });

    assertThat(invalid.messages())
        .extracting(item -> item.path("error").path("data").path("tracepermitCode").asText())
        .containsExactly("INVALID_JSON_RPC", "INVALID_JSON_RPC");
    assertThat(empty.messages().getFirst().path("error").path("data")
        .path("tracepermitCode").asText()).isEqualTo("INVALID_GATEWAY_RESPONSE");
    assertThat(unexpected.messages().getFirst().path("error").path("data")
        .path("tracepermitCode").asText()).isEqualTo("CONNECTOR_FAILURE");
    assertThat(unexpected.stdout() + unexpected.stderr())
        .doesNotContain("sentinel-unexpected-gateway-detail");
  }

  @Test
  void boundsStdioReadAndWriteFailures() {
    ByteArrayOutputStream readDiagnostics = new ByteArrayOutputStream();
    InputStream failedInput = new InputStream() {
      @Override
      public int read() throws IOException {
        throw new IOException("sentinel-read-detail");
      }
    };
    int readExit = new StdioMcpServer(
        failedInput,
        new ByteArrayOutputStream(),
        new PrintStream(readDiagnostics, true, StandardCharsets.UTF_8),
        connector((request, token, proof) -> Optional.empty())).run();

    ByteArrayOutputStream writeDiagnostics = new ByteArrayOutputStream();
    OutputStream failedOutput = new OutputStream() {
      @Override
      public void write(int value) throws IOException {
        throw new IOException("sentinel-write-detail");
      }
    };
    int writeExit = new StdioMcpServer(
        new ByteArrayInputStream(
            "{\"jsonrpc\":\"2.0\",\"id\":34,\"method\":\"tools/list\",\"params\":{}}"
                .getBytes(StandardCharsets.UTF_8)),
        failedOutput,
        new PrintStream(writeDiagnostics, true, StandardCharsets.UTF_8),
        connector((request, token, proof) -> Optional.of(response(request.get("id")))))
        .run();

    assertThat(readExit).isEqualTo(2);
    assertThat(readDiagnostics.toString(StandardCharsets.UTF_8))
        .contains("STDIO_READ_FAILED")
        .doesNotContain("sentinel-read-detail");
    assertThat(writeExit).isEqualTo(2);
    assertThat(writeDiagnostics.toString(StandardCharsets.UTF_8))
        .contains("STDIO_WRITE_FAILED")
        .doesNotContain("sentinel-write-detail");
  }

  private static RunResult run(String input, GatewayTransport transport) throws Exception {
    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    EndpointConnector connector = connector(transport);
    StdioMcpServer server = new StdioMcpServer(
        new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
        stdout,
        new PrintStream(stderr, true, StandardCharsets.UTF_8),
        connector);
    int exitCode = server.run();
    String output = stdout.toString(StandardCharsets.UTF_8);
    List<JsonNode> messages = new ArrayList<>();
    for (String line : output.lines().toList()) {
      if (!line.isBlank()) messages.add(JsonSupport.MAPPER.readTree(line));
    }
    return new RunResult(
        exitCode,
        output,
        stderr.toString(StandardCharsets.UTF_8),
        List.copyOf(messages));
  }

  private static EndpointConnector connector(GatewayTransport transport) {
    KeyPair keys = ed25519();
    EndpointKeyProvider keyProvider = new EndpointKeyProvider() {
      @Override
      public KeyMetadata metadata() {
        return new KeyMetadata(
            "tracepermit-endpoint:device-simulated-stdio",
            "endpoint-key-stdio",
            "device-simulated-stdio");
      }

      @Override
      public byte[] sign(byte[] signingInput) {
        try {
          Signature signature = Signature.getInstance("Ed25519");
          signature.initSign(keys.getPrivate());
          signature.update(signingInput);
          return signature.sign();
        } catch (GeneralSecurityException exception) {
          throw new IllegalStateException(exception);
        }
      }
    };
    ProcessObservation observation = new ProcessObservation(
        77,
        "device-simulated-stdio",
        "linux",
        "development",
        "codex",
        Path.of("/simulated/codex"),
        "sha256:" + "3".repeat(64),
        "terminal",
        NOW.minusSeconds(60),
        true);
    ProcessObserver observer = new ProcessObserver() {
      @Override
      public ProcessObservation observe() {
        return observation;
      }

      @Override
      public void verifyStable(ProcessObservation ignored) {}
    };
    AtomicInteger counter = new AtomicInteger();
    SourceAttestationFactory factory = new SourceAttestationFactory(
        keyProvider,
        Clock.fixed(NOW, ZoneOffset.UTC),
        () -> new UUID(0x018f000000007000L, 0x8000000000000000L + counter.incrementAndGet())
            .toString(),
        Duration.ofSeconds(30));
    return new EndpointConnector(observer, () -> SECRET, factory, transport);
  }

  private static ObjectNode response(JsonNode id) {
    ObjectNode response = JsonSupport.MAPPER.createObjectNode();
    response.put("jsonrpc", "2.0");
    response.set("id", id.deepCopy());
    response.set("result", JsonSupport.MAPPER.createObjectNode());
    return response;
  }

  private static KeyPair ed25519() {
    try {
      return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private record RunResult(
      int exitCode, String stdout, String stderr, List<JsonNode> messages) {}
}
