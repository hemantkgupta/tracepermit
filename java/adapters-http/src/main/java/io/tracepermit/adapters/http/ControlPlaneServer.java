package io.tracepermit.adapters.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.simulator.StatefulTargetSimulator;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public final class ControlPlaneServer implements AutoCloseable {
  private static final Set<String> ALLOWED_ORIGINS =
      Set.of("http://127.0.0.1:5173", "http://localhost:5173");
  private static final Pattern EVENT_ID = Pattern.compile(
      "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");
  private static final Pattern POLICY_ID = Pattern.compile("[a-z0-9][a-z0-9-]{2,63}");
  private static final Pattern POLICY_VERSION = Pattern.compile("[1-9][0-9]{0,8}");
  private final HttpServer server;
  private final ExecutorService executor;
  private final InMemoryActionFeed actionFeed;
  private final InMemoryControlPlaneViews views;
  private final StatefulTargetSimulator simulator;
  private final Clock clock;
  private final DevelopmentRuntimeInfo runtimeInfo;

  private ControlPlaneServer(
      HttpServer server,
      ExecutorService executor,
      InMemoryActionFeed actionFeed,
      InMemoryControlPlaneViews views,
      StatefulTargetSimulator simulator,
      Clock clock,
      DevelopmentRuntimeInfo runtimeInfo) {
    this.server = server;
    this.executor = executor;
    this.actionFeed = actionFeed;
    this.views = views;
    this.simulator = simulator;
    this.clock = clock;
    this.runtimeInfo = runtimeInfo;
  }

  public static ControlPlaneServer start(
      InetSocketAddress address,
      InMemoryActionFeed actionFeed,
      InMemoryControlPlaneViews views,
      StatefulTargetSimulator simulator,
      Clock clock) {
    return start(
        address,
        actionFeed,
        views,
        simulator,
        clock,
        DevelopmentRuntimeInfo.from(Map.of()));
  }

  public static ControlPlaneServer start(
      InetSocketAddress address,
      InMemoryActionFeed actionFeed,
      InMemoryControlPlaneViews views,
      StatefulTargetSimulator simulator,
      Clock clock,
      DevelopmentRuntimeInfo runtimeInfo) {
    InetAddress resolved = address.getAddress();
    if (resolved == null || !resolved.isLoopbackAddress()) {
      throw new IllegalArgumentException("The development control-plane API must bind to loopback.");
    }
    try {
      HttpServer server = HttpServer.create(address, 0);
      ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
      ControlPlaneServer controlPlane =
          new ControlPlaneServer(
              server, executor, actionFeed, views, simulator, clock, runtimeInfo);
      server.createContext("/", controlPlane::handle);
      server.setExecutor(executor);
      server.start();
      return controlPlane;
    } catch (IOException exception) {
      throw new IllegalStateException("Cannot start the development control-plane API.", exception);
    }
  }

  public int port() {
    return server.getAddress().getPort();
  }

  @Override
  public void close() {
    server.stop(0);
    executor.close();
  }

  private void handle(HttpExchange exchange) throws IOException {
    try {
      if (!validHost(exchange)) {
        sendError(exchange, 400, "INVALID_HOST", "The request Host is not allowed.");
        return;
      }
      String origin = exchange.getRequestHeaders().getFirst("Origin");
      if (origin != null && !ALLOWED_ORIGINS.contains(origin)) {
        sendError(exchange, 403, "ORIGIN_NOT_ALLOWED", "The request Origin is not allowed.");
        return;
      }
      if (origin != null) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
        exchange.getResponseHeaders().set("Vary", "Origin");
      }
      if ("OPTIONS".equals(exchange.getRequestMethod())) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
        exchange.sendResponseHeaders(204, -1);
        return;
      }
      if (!"GET".equals(exchange.getRequestMethod())) {
        exchange.getResponseHeaders().set("Allow", "GET, OPTIONS");
        sendError(exchange, 405, "METHOD_NOT_ALLOWED", "This development API is read-only.");
        return;
      }

      String path = exchange.getRequestURI().getPath();
      if ("/health".equals(path)) {
        ObjectNode response = JsonSupport.MAPPER.createObjectNode();
        response.put("status", "ok");
        response.put("service", "tracepermit-java-control-plane");
        response.put("mode", "simulated");
        send(exchange, 200, response);
      } else if ("/ready".equals(path)) {
        ObjectNode response = JsonSupport.MAPPER.createObjectNode();
        response.put("ready", true);
        response.put("checkedAt", clock.instant().toString());
        response.put("mode", "simulated-loopback");
        send(exchange, 200, response);
      } else if ("/api/v1/overview".equals(path)) {
        send(exchange, 200, JsonSupport.MAPPER.valueToTree(actionFeed.overview()));
      } else if ("/api/v1/system-info".equals(path)) {
        send(exchange, 200, JsonSupport.MAPPER.valueToTree(runtimeInfo));
      } else if ("/api/v1/actions".equals(path)) {
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        int limit = parseLimit(query.get("limit"));
        ActionFeedPage page = actionFeed.list(
            query.get("decision"), query.get("severity"), query.get("origin"), query.get("q"), limit);
        send(exchange, 200, JsonSupport.MAPPER.valueToTree(page));
      } else if (path.startsWith("/api/v1/actions/")) {
        String eventId = identifier(
            path.substring("/api/v1/actions/".length()), EVENT_ID);
        ActionFeedEntry entry = actionFeed.find(eventId);
        if (entry == null) {
          sendError(exchange, 404, "ACTION_NOT_FOUND", "The requested simulated action does not exist.");
        } else {
          send(exchange, 200, JsonSupport.MAPPER.valueToTree(entry));
        }
      } else if ("/api/v1/evidence-integrity".equals(path)) {
        send(exchange, 200, JsonSupport.MAPPER.valueToTree(views.evidencePage()));
      } else if (path.startsWith("/api/v1/evidence-integrity/")) {
        String eventId = identifier(
            path.substring("/api/v1/evidence-integrity/".length()), EVENT_ID);
        EvidenceIntegrityDetail detail = views.evidence(eventId);
        if (detail == null) {
          sendError(exchange, 404, "EVIDENCE_NOT_FOUND", "The requested evidence stream does not exist.");
        } else {
          send(exchange, 200, JsonSupport.MAPPER.valueToTree(detail));
        }
      } else if ("/api/v1/policy-versions".equals(path)) {
        send(exchange, 200, JsonSupport.MAPPER.valueToTree(views.policyPage()));
      } else if (path.startsWith("/api/v1/policy-versions/")) {
        String[] parts = path.substring("/api/v1/policy-versions/".length()).split("/", -1);
        if (parts.length != 2) throw new InvalidIdentifierException();
        String policyId = identifier(parts[0], POLICY_ID);
        int version = policyVersion(parts[1]);
        PolicyVersionDetail detail = views.policy(policyId, version);
        if (detail == null) {
          sendError(exchange, 404, "POLICY_VERSION_NOT_FOUND", "The requested policy version does not exist.");
        } else {
          send(exchange, 200, JsonSupport.MAPPER.valueToTree(detail));
        }
      } else if ("/api/v1/lineages".equals(path)) {
        send(exchange, 200, JsonSupport.MAPPER.valueToTree(views.lineagePage()));
      } else if (path.startsWith("/api/v1/lineages/")) {
        String traceId = identifier(
            path.substring("/api/v1/lineages/".length()), TRACE_ID);
        LineageDetail detail = views.lineage(traceId);
        if (detail == null) {
          sendError(exchange, 404, "LINEAGE_NOT_FOUND", "The requested lineage does not exist.");
        } else {
          send(exchange, 200, JsonSupport.MAPPER.valueToTree(detail));
        }
      } else if ("/api/v1/simulator/state".equals(path)) {
        send(exchange, 200, simulator.snapshot());
      } else {
        sendError(exchange, 404, "NOT_FOUND", "The requested resource does not exist.");
      }
    } catch (InvalidIdentifierException exception) {
      sendError(exchange, 400, "INVALID_IDENTIFIER", "The resource identifier is malformed.");
    } catch (IllegalArgumentException exception) {
      sendError(exchange, 400, "INVALID_QUERY", "The request query is invalid.");
    } catch (RuntimeException exception) {
      sendError(exchange, 500, "INTERNAL_ERROR", "The simulated control plane could not process the request.");
    } finally {
      exchange.close();
    }
  }

  private boolean validHost(HttpExchange exchange) {
    String host = exchange.getRequestHeaders().getFirst("Host");
    if (host == null) return false;
    String expectedPort = ":" + port();
    return host.equals("127.0.0.1" + expectedPort)
        || host.equals("localhost" + expectedPort)
        || host.equals("[::1]" + expectedPort);
  }

  private static int parseLimit(String value) {
    if (value == null) return 50;
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("Invalid limit", exception);
    }
  }

  private static Map<String, String> parseQuery(String rawQuery) {
    Map<String, String> values = new LinkedHashMap<>();
    if (rawQuery == null || rawQuery.isBlank()) return values;
    for (String pair : rawQuery.split("&")) {
      String[] parts = pair.split("=", 2);
      String name = decode(parts[0]);
      String value = parts.length == 2 ? decode(parts[1]) : "";
      if (!Set.of("decision", "severity", "origin", "q", "limit").contains(name)
          || values.putIfAbsent(name, value) != null) {
        throw new IllegalArgumentException("Unsupported or repeated query parameter");
      }
    }
    return values;
  }

  private static String decode(String value) {
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }

  private static String identifier(String encoded, Pattern pattern) {
    try {
      String value = decode(encoded);
      if (!pattern.matcher(value).matches()) throw new InvalidIdentifierException();
      return value;
    } catch (IllegalArgumentException exception) {
      throw new InvalidIdentifierException();
    }
  }

  private static int policyVersion(String encoded) {
    String value = identifier(encoded, POLICY_VERSION);
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException exception) {
      throw new InvalidIdentifierException();
    }
  }

  private static void sendError(
      HttpExchange exchange, int status, String code, String message) throws IOException {
    ObjectNode error = JsonSupport.MAPPER.createObjectNode();
    error.put("code", code);
    error.put("message", message);
    ObjectNode body = JsonSupport.MAPPER.createObjectNode();
    body.set("error", error);
    send(exchange, status, body);
  }

  private static void send(HttpExchange exchange, int status, JsonNode body) throws IOException {
    byte[] bytes = JsonSupport.MAPPER.writeValueAsBytes(body);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }

  private static final class InvalidIdentifierException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
}
