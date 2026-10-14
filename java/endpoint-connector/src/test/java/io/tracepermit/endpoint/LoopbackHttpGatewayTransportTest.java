package io.tracepermit.endpoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.tracepermit.contracts.JsonSupport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class LoopbackHttpGatewayTransportTest {
  private static final String TOKEN = "sentinel-loopback-bearer";
  private static final String PROOF = "header.payload.signature";

  @Test
  void forwardsOnlyBoundedProtocolDataAndParsesJsonAndSseResponses() throws Exception {
    AtomicReference<String> authorization = new AtomicReference<>();
    AtomicReference<String> attestation = new AtomicReference<>();
    AtomicReference<String> requestBody = new AtomicReference<>();
    try (ServerFixture server = start(exchange -> {
      authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      attestation.set(exchange.getRequestHeaders().getFirst(
          LoopbackHttpGatewayTransport.SOURCE_HEADER));
      requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      send(exchange, 200, "application/json", "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}");
    })) {
      LoopbackHttpGatewayTransport transport = transport(server.uri());
      Optional<JsonNode> response = transport.forward(request(1), TOKEN, PROOF);

      assertThat(response).isPresent();
      assertThat(response.orElseThrow().path("id").asInt()).isOne();
      assertThat(authorization).hasValue("Bearer " + TOKEN);
      assertThat(attestation).hasValue(PROOF);
      assertThat(requestBody.get()).contains("\"method\":\"tools/list\"");
    }

    try (ServerFixture server = start(exchange -> send(
        exchange,
        200,
        "text/event-stream",
        "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}\n"
            + "data: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{}}\n\n"))) {
      JsonNode response = transport(server.uri()).forward(request(2), TOKEN, PROOF).orElseThrow();
      assertThat(response.path("id").asInt()).isEqualTo(2);
    }

    try (ServerFixture server = start(exchange -> send(exchange, 202, "application/json", ""))) {
      assertThat(transport(server.uri()).forward(request(3), TOKEN, PROOF)).isEmpty();
    }
  }

  @Test
  void propagatesOnlyBoundedGatewayErrorCodesAndNeverFollowsRedirects() throws Exception {
    try (ServerFixture server = start(exchange -> send(
        exchange,
        401,
        "application/json",
        "{\"error\":\"INVALID_SOURCE_ATTESTATION\",\"secret\":\"" + TOKEN + "\"}"))) {
      assertCode(
          () -> transport(server.uri()).forward(request(4), TOKEN, PROOF),
          "INVALID_SOURCE_ATTESTATION");
    }
    try (ServerFixture server = start(exchange -> {
      exchange.getResponseHeaders().set("Location", "https://example.invalid/escape");
      send(exchange, 302, "text/plain", "redirect");
    })) {
      assertCode(
          () -> transport(server.uri()).forward(request(5), TOKEN, PROOF),
          "GATEWAY_REJECTED");
    }
    try (ServerFixture server = start(exchange -> send(
        exchange, 500, "application/json", "{not-json"))) {
      assertCode(
          () -> transport(server.uri()).forward(request(6), TOKEN, PROOF),
          "GATEWAY_REJECTED");
    }
  }

  @Test
  void rejectsRemoteDestinationsInvalidTimeoutsAndUnavailableLoopback() throws Exception {
    for (String value : java.util.List.of(
        "https://127.0.0.1:8080/mcp",
        "http://example.invalid:8080/mcp",
        "http://localhost:8080/mcp",
        "http://[::1]:8080/mcp",
        "http://127.0.0.1/mcp",
        "http://127.0.0.1:8080/%6dcp",
        "http://127.0.0.1:8080/not-mcp",
        "http://user@127.0.0.1:8080/mcp",
        "http://127.0.0.1:8080/mcp?query=true")) {
      assertThatThrownBy(() -> transport(URI.create(value)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("loopback");
    }
    assertThatThrownBy(() -> new LoopbackHttpGatewayTransport(
        URI.create("http://127.0.0.1:8080/mcp"), Duration.ZERO, Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);

    int unavailablePort;
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      unavailablePort = socket.getLocalPort();
    }
    LoopbackHttpGatewayTransport unavailable = transport(
        URI.create("http://127.0.0.1:" + unavailablePort + "/mcp"));
    assertCode(
        () -> unavailable.forward(request(7), TOKEN, PROOF),
        "GATEWAY_UNAVAILABLE");
  }

  @Test
  void ignoresSystemProxySoBearerAndSourceProofStayOnLoopback() throws Exception {
    ProxySelector original = ProxySelector.getDefault();
    AtomicInteger proxySelections = new AtomicInteger();
    ProxySelector.setDefault(new ProxySelector() {
      @Override
      public List<Proxy> select(URI ignored) {
        proxySelections.incrementAndGet();
        return List.of(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 1)));
      }

      @Override
      public void connectFailed(URI ignored, SocketAddress address, IOException failure) {
        throw new AssertionError("The loopback connector must never select a proxy", failure);
      }
    });
    try (ServerFixture server = start(exchange -> send(
        exchange, 200, "application/json", "{\"jsonrpc\":\"2.0\",\"id\":1}"))) {
      assertThat(transport(server.uri()).forward(request(1), TOKEN, PROOF)).isPresent();
      assertThat(proxySelections).hasValue(0);
    } finally {
      ProxySelector.setDefault(original);
    }
  }

  @Test
  void boundsRequestsResponsesCredentialsAndAttestations() throws Exception {
    try (ServerFixture server = start(exchange -> send(
        exchange,
        200,
        "application/json",
        "x".repeat(256 * 1024 + 1)))) {
      assertCode(
          () -> transport(server.uri()).forward(request(8), TOKEN, PROOF),
          "GATEWAY_RESPONSE_TOO_LARGE");
    }
    try (ServerFixture server = start(exchange -> send(
        exchange, 200, "application/json", "{\"jsonrpc\":\"2.0\",\"id\":9}"))) {
      ObjectNode large = request(9);
      large.putObject("params").put("value", "x".repeat(64 * 1024));
      assertCode(
          () -> transport(server.uri()).forward(large, TOKEN, PROOF),
          "GATEWAY_REQUEST_TOO_LARGE");
      assertCode(
          () -> transport(server.uri()).forward(request(9), "bad\ntoken", PROOF),
          "CONNECTOR_CREDENTIAL_UNAVAILABLE");
      assertCode(
          () -> transport(server.uri()).forward(request(9), TOKEN, "bad\nproof"),
          "SOURCE_ATTESTATION_UNAVAILABLE");
    }
  }

  @Test
  void rejectsMalformedOrDuplicateGatewayJson() throws Exception {
    for (String body : java.util.List.of(
        "[]",
        "{not-json",
        "{\"jsonrpc\":\"2.0\",\"jsonrpc\":\"2.0\"}",
        "event: message\nnot-data: {}\n")) {
      try (ServerFixture server = start(exchange -> send(
          exchange, 200, "application/json", body))) {
        assertCode(
            () -> transport(server.uri()).forward(request(10), TOKEN, PROOF),
            "INVALID_GATEWAY_RESPONSE");
      }
    }
  }

  private static LoopbackHttpGatewayTransport transport(URI uri) {
    return new LoopbackHttpGatewayTransport(
        uri, Duration.ofSeconds(2), Duration.ofSeconds(5));
  }

  private static ObjectNode request(int id) {
    ObjectNode request = JsonSupport.MAPPER.createObjectNode();
    request.put("jsonrpc", "2.0");
    request.put("id", id);
    request.put("method", "tools/list");
    request.set("params", JsonSupport.MAPPER.createObjectNode());
    return request;
  }

  private static ServerFixture start(HttpHandler handler) throws IOException {
    HttpServer server = HttpServer.create(
        new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
    server.createContext("/mcp", handler);
    server.start();
    return new ServerFixture(server);
  }

  private static void send(
      HttpExchange exchange, int status, String contentType, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", contentType);
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private static void assertCode(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable operation, String expected) {
    assertThatThrownBy(operation)
        .isInstanceOf(ConnectorException.class)
        .extracting(error -> ((ConnectorException) error).code())
        .isEqualTo(expected);
  }

  private record ServerFixture(HttpServer server) implements AutoCloseable {
    URI uri() {
      return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
