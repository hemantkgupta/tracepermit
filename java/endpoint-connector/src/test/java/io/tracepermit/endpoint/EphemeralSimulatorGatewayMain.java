package io.tracepermit.endpoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.identity.EndpointKeyRegistry;
import io.tracepermit.identity.InMemorySourceAttestationReplayStore;
import io.tracepermit.identity.SourceAttestationException;
import io.tracepermit.identity.SourceAttestationVerifier;
import io.tracepermit.identity.StaticEndpointKeyRegistry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ephemeral local Java simulator gateway with exact counters for qualification.
 * Binds exclusively to numeric IPv4 loopback (127.0.0.1).
 */
public final class EphemeralSimulatorGatewayMain {
  private final HttpServer server;
  private final ExecutorService executor;
  private final SourceAttestationVerifier verifier;

  // Exact qualification counters
  private final AtomicInteger requestsReceived = new AtomicInteger();
  private final AtomicInteger initializeCount = new AtomicInteger();
  private final AtomicInteger toolsListCount = new AtomicInteger();
  private final AtomicInteger readAllowedCount = new AtomicInteger();
  private final AtomicInteger policyBlockedCount = new AtomicInteger();
  private final AtomicInteger unknownToolRejectedCount = new AtomicInteger();
  private final AtomicInteger expiredCredentialRejectedCount = new AtomicInteger();
  private final AtomicInteger proofMismatchRejectedCount = new AtomicInteger();
  private final AtomicInteger replayRejectedCount = new AtomicInteger();
  private final AtomicInteger targetExecutorInvocations = new AtomicInteger();

  public EphemeralSimulatorGatewayMain(Map<String, String> environment) throws Exception {
    String issuer = environment.getOrDefault("TRACEPERMIT_ENDPOINT_ISSUER", "tracepermit-endpoint:test");
    String keyId = environment.getOrDefault("TRACEPERMIT_ENDPOINT_KEY_ID", "endpoint-key-test");
    String deviceId = environment.getOrDefault("TRACEPERMIT_ENDPOINT_DEVICE_ID", "device-test");
    String spkiBase64 = environment.get("TRACEPERMIT_ENDPOINT_PUBLIC_KEY_SPKI_BASE64");

    if (spkiBase64 == null || spkiBase64.isBlank()) {
      throw new IllegalArgumentException("TRACEPERMIT_ENDPOINT_PUBLIC_KEY_SPKI_BASE64 is required");
    }
    byte[] spki = Base64.getDecoder().decode(spkiBase64.trim());
    PublicKey publicKey = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
    EndpointKeyRegistry.EnrolledEndpointKey enrolled =
        new EndpointKeyRegistry.EnrolledEndpointKey(issuer, keyId, deviceId, publicKey);

    StaticEndpointKeyRegistry registry = new StaticEndpointKeyRegistry(List.of(enrolled));
    InMemorySourceAttestationReplayStore replayStore = new InMemorySourceAttestationReplayStore();
    verifier = new SourceAttestationVerifier(registry, replayStore, Clock.systemUTC());

    InetSocketAddress socketAddress = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0);
    server = HttpServer.create(socketAddress, 0);
    executor = Executors.newFixedThreadPool(4);
    server.setExecutor(executor);

    server.createContext("/mcp", this::handleMcp);
    server.createContext("/counters", this::handleCounters);
  }

  public int port() {
    return server.getAddress().getPort();
  }

  public void start() {
    server.start();
  }

  public void stop() {
    server.stop(0);
    executor.shutdown();
    try {
      if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  private void handleCounters(HttpExchange exchange) throws IOException {
    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(405, -1);
      return;
    }
    ObjectNode node = JsonSupport.MAPPER.createObjectNode();
    node.put("requestsReceived", requestsReceived.get());
    node.put("initializeCount", initializeCount.get());
    node.put("toolsListCount", toolsListCount.get());
    node.put("readAllowedCount", readAllowedCount.get());
    node.put("policyBlockedCount", policyBlockedCount.get());
    node.put("unknownToolRejectedCount", unknownToolRejectedCount.get());
    node.put("expiredCredentialRejectedCount", expiredCredentialRejectedCount.get());
    node.put("proofMismatchRejectedCount", proofMismatchRejectedCount.get());
    node.put("replayRejectedCount", replayRejectedCount.get());
    node.put("targetExecutorInvocations", targetExecutorInvocations.get());

    byte[] bytes = JsonSupport.MAPPER.writeValueAsBytes(node);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private void handleMcp(HttpExchange exchange) throws IOException {
    requestsReceived.incrementAndGet();
    if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(405, -1);
      return;
    }

    String auth = exchange.getRequestHeaders().getFirst("Authorization");
    if (auth == null || !auth.startsWith("Bearer ")) {
      sendJson(exchange, 401, "{\"error\":\"MISSING_BEARER_TOKEN\"}");
      return;
    }
    String bearerToken = auth.substring("Bearer ".length()).trim();
    if (bearerToken.startsWith("expired") || bearerToken.contains("expired")) {
      expiredCredentialRejectedCount.incrementAndGet();
      sendJson(exchange, 401, "{\"error\":\"EXPIRED_BEARER_TOKEN\"}");
      return;
    }

    String attestation = exchange.getRequestHeaders().getFirst("TracePermit-Source-Attestation");
    if (attestation == null || attestation.isBlank()) {
      sendJson(exchange, 401, "{\"error\":\"MISSING_SOURCE_ATTESTATION\"}");
      return;
    }

    byte[] body = exchange.getRequestBody().readAllBytes();
    JsonNode request;
    try {
      request = JsonSupport.MAPPER.readTree(body);
    } catch (Exception e) {
      sendJson(exchange, 400, "{\"error\":\"MALFORMED_JSON\"}");
      return;
    }

    // Verify source attestation
    try {
      String fingerprint = SourceAttestationFactory.fingerprint(bearerToken);
      verifier.verify(attestation, request, fingerprint);
    } catch (SourceAttestationException e) {
      if (e.code() != null && e.code().contains("REPLAY")) {
        replayRejectedCount.incrementAndGet();
        sendJson(exchange, 409, "{\"error\":\"SOURCE_ATTESTATION_REPLAYED\"}");
      } else {
        proofMismatchRejectedCount.incrementAndGet();
        sendJson(exchange, 403, "{\"error\":\"INVALID_SOURCE_ATTESTATION\"}");
      }
      return;
    } catch (Exception e) {
      proofMismatchRejectedCount.incrementAndGet();
      sendJson(exchange, 403, "{\"error\":\"INVALID_SOURCE_ATTESTATION\"}");
      return;
    }

    String method = request.path("method").asText();
    JsonNode id = request.get("id");

    if ("initialize".equals(method)) {
      initializeCount.incrementAndGet();
      ObjectNode response = JsonSupport.MAPPER.createObjectNode();
      response.put("jsonrpc", "2.0");
      response.set("id", id != null ? id : JsonSupport.MAPPER.nullNode());
      ObjectNode result = response.putObject("result");
      result.put("protocolVersion", "2025-06-18");
      result.putObject("capabilities").putObject("tools");
      result.putObject("serverInfo").put("name", "TracePermit").put("version", "0.1.0");
      sendJson(exchange, 200, JsonSupport.MAPPER.writeValueAsString(response));
      return;
    }

    if ("notifications/initialized".equals(method)) {
      exchange.sendResponseHeaders(200, -1);
      return;
    }

    if ("tools/list".equals(method)) {
      toolsListCount.incrementAndGet();
      ObjectNode response = JsonSupport.MAPPER.createObjectNode();
      response.put("jsonrpc", "2.0");
      response.set("id", id != null ? id : JsonSupport.MAPPER.nullNode());
      ObjectNode result = response.putObject("result");
      var tools = result.putArray("tools");
      tools.addObject().put("name", "aws.logs.filter_log_events").put("description", "Filter logs");
      tools.addObject().put("name", "aws.ec2.terminate_instances").put("description", "Terminate EC2 instances");
      sendJson(exchange, 200, JsonSupport.MAPPER.writeValueAsString(response));
      return;
    }

    if ("tools/call".equals(method)) {
      String toolName = request.path("params").path("name").asText();
      if ("aws.logs.filter_log_events".equals(toolName)) {
        readAllowedCount.incrementAndGet();
        targetExecutorInvocations.incrementAndGet();
        ObjectNode response = JsonSupport.MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id != null ? id : JsonSupport.MAPPER.nullNode());
        ObjectNode result = response.putObject("result");
        var content = result.putArray("content");
        content.addObject().put("type", "text").put("text",
            "{\"evaluation\":{\"decision\":{\"outcome\":\"allow\"}},\"execution\":{\"status\":\"executed\",\"result\":{\"mode\":\"simulated\",\"events\":[]}}}");
        sendJson(exchange, 200, JsonSupport.MAPPER.writeValueAsString(response));
        return;
      }

      if ("aws.ec2.terminate_instances".equals(toolName)) {
        policyBlockedCount.incrementAndGet();
        // Policy denied before executor: do NOT increment targetExecutorInvocations!
        ObjectNode response = JsonSupport.MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id != null ? id : JsonSupport.MAPPER.nullNode());
        ObjectNode result = response.putObject("result");
        result.put("isError", true);
        var content = result.putArray("content");
        content.addObject().put("type", "text").put("text",
            "{\"evaluation\":{\"decision\":{\"outcome\":\"block\"}},\"execution\":{\"status\":\"denied\",\"reason\":\"Policy denies simulated termination\"}}");
        sendJson(exchange, 200, JsonSupport.MAPPER.writeValueAsString(response));
        return;
      }

      // Unknown tool rejection: do NOT increment targetExecutorInvocations!
      unknownToolRejectedCount.incrementAndGet();
      ObjectNode response = JsonSupport.MAPPER.createObjectNode();
      response.put("jsonrpc", "2.0");
      response.set("id", id != null ? id : JsonSupport.MAPPER.nullNode());
      ObjectNode error = response.putObject("error");
      error.put("code", -32602);
      error.put("message", "Unknown tool: " + toolName);
      sendJson(exchange, 200, JsonSupport.MAPPER.writeValueAsString(response));
      return;
    }

    // Any other method
    exchange.sendResponseHeaders(400, -1);
  }

  private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  public static void main(String[] args) throws Exception {
    EphemeralSimulatorGatewayMain gateway = new EphemeralSimulatorGatewayMain(System.getenv());
    gateway.start();
    Runtime.getRuntime().addShutdownHook(new Thread(gateway::stop));
    System.out.println("GATEWAY_READY port=" + gateway.port());
    System.out.flush();

    // Wait for EOF or shutdown line on stdin
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
      while (true) {
        String line = reader.readLine();
        if (line == null || "SHUTDOWN".equalsIgnoreCase(line.trim())) {
          break;
        }
      }
    }
    gateway.stop();
    System.exit(0);
  }
}
