package io.tracepermit.endpoint;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tracepermit.contracts.JsonSupport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class LoopbackHttpGatewayTransport implements GatewayTransport {
  public static final String SOURCE_HEADER = "tracepermit-source-attestation";
  private static final int MAX_REQUEST_BYTES = 64 * 1024;
  private static final int MAX_RESPONSE_BYTES = 256 * 1024;
  private static final String LOOPBACK_HOST = "127.0.0.1";
  private static final ObjectMapper STRICT_MAPPER =
      new ObjectMapper(
              JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private final URI gatewayUri;
  private final HttpClient client;
  private final Duration requestTimeout;

  public LoopbackHttpGatewayTransport(
      URI gatewayUri, Duration connectTimeout, Duration requestTimeout) {
    this.gatewayUri = validateGatewayUri(Objects.requireNonNull(gatewayUri));
    this.requestTimeout = positiveTimeout(requestTimeout);
    client = HttpClient.newBuilder()
        .connectTimeout(positiveTimeout(connectTimeout))
        .followRedirects(HttpClient.Redirect.NEVER)
        .proxy(HttpClient.Builder.NO_PROXY)
        .build();
  }

  @Override
  public Optional<JsonNode> forward(
      JsonNode request, String bearerToken, String compactSourceAttestation) {
    Objects.requireNonNull(request);
    SourceAttestationFactory.fingerprint(bearerToken);
    if (compactSourceAttestation == null
        || compactSourceAttestation.isBlank()
        || compactSourceAttestation.length() > 16 * 1024
        || compactSourceAttestation.indexOf('\r') >= 0
        || compactSourceAttestation.indexOf('\n') >= 0) {
      throw new ConnectorException(
          "SOURCE_ATTESTATION_UNAVAILABLE",
          "The source attestation is unavailable.");
    }
    byte[] body;
    try {
      body = JsonSupport.MAPPER.writeValueAsBytes(request);
    } catch (IOException exception) {
      throw unavailable(exception);
    }
    if (body.length > MAX_REQUEST_BYTES) {
      throw new ConnectorException(
          "GATEWAY_REQUEST_TOO_LARGE", "The gateway request is too large.");
    }

    HttpRequest httpRequest = HttpRequest.newBuilder(gatewayUri)
        .timeout(requestTimeout)
        .header("Authorization", "Bearer " + bearerToken)
        .header("Content-Type", "application/json")
        .header("Accept", "application/json, text/event-stream")
        .header(SOURCE_HEADER, compactSourceAttestation)
        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        .build();
    BoundedGatewayBodySubscriber subscriber = new BoundedGatewayBodySubscriber(MAX_RESPONSE_BYTES);
    CompletableFuture<HttpResponse<byte[]>> exchange = null;
    long started = System.nanoTime();
    boolean completed = false;
    try {
      exchange = client.sendAsync(httpRequest, ignored -> subscriber);
      long remaining = requestTimeout.toNanos() - (System.nanoTime() - started);
      if (remaining <= 0) throw new TimeoutException();
      HttpResponse<byte[]> response = exchange.get(remaining, TimeUnit.NANOSECONDS);
      if (System.nanoTime() - started >= requestTimeout.toNanos()) throw new TimeoutException();
      completed = true;
      byte[] responseBytes = response.body();
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw gatewayRejected(responseBytes);
      }
      if (responseBytes.length == 0) return Optional.empty();
      return Optional.of(parseResponse(responseBytes));
    } catch (ConnectorException exception) {
      throw exception;
    } catch (ExecutionException exception) {
      if (exception.getCause() instanceof ConnectorException failure) throw failure;
      throw unavailable(exception.getCause());
    } catch (TimeoutException | CancellationException exception) {
      throw unavailable(exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw unavailable(exception);
    } finally {
      if (!completed) {
        subscriber.cancel();
        if (exchange != null) exchange.cancel(true);
      }
    }
  }

  private static JsonNode parseResponse(byte[] bytes) {
    String text = new String(bytes, StandardCharsets.UTF_8).trim();
    String json = text;
    if (text.lines().map(String::trim).anyMatch(line -> line.startsWith("data:"))) {
      json = text.lines()
          .map(String::trim)
          .filter(line -> line.startsWith("data:"))
          .map(line -> line.substring("data:".length()).trim())
          .reduce((left, right) -> right)
          .orElseThrow(() -> new ConnectorException(
              "INVALID_GATEWAY_RESPONSE", "The gateway response is invalid."));
    }
    try {
      JsonNode parsed = STRICT_MAPPER.readTree(json);
      if (parsed == null || !parsed.isObject()) {
        throw new ConnectorException(
            "INVALID_GATEWAY_RESPONSE", "The gateway response is invalid.");
      }
      return parsed;
    } catch (ConnectorException exception) {
      throw exception;
    } catch (IOException exception) {
      throw new ConnectorException(
          "INVALID_GATEWAY_RESPONSE", "The gateway response is invalid.", exception);
    }
  }

  private static ConnectorException gatewayRejected(byte[] responseBytes) {
    String gatewayCode = "GATEWAY_REJECTED";
    if (responseBytes.length > 0) {
      try {
        JsonNode body = STRICT_MAPPER.readTree(responseBytes);
        String candidate = body == null ? "" : body.path("error").asText();
        if (candidate.matches("^[A-Z][A-Z0-9_]{1,63}$")) gatewayCode = candidate;
      } catch (IOException ignored) {
        // The public error remains bounded when the gateway body is malformed.
      }
    }
    return new ConnectorException(gatewayCode, "The gateway rejected the request.");
  }

  static URI validateGatewayUri(URI uri) {
    if (!"http".equals(uri.getScheme())
        || !LOOPBACK_HOST.equals(uri.getHost())
        || uri.getPort() < 1
        || uri.getUserInfo() != null
        || uri.getFragment() != null
        || uri.getQuery() != null
        || !"/mcp".equals(uri.getRawPath())) {
      throw new IllegalArgumentException(
          "The development gateway URL must be an explicit loopback HTTP /mcp endpoint.");
    }
    return uri;
  }

  private static Duration positiveTimeout(Duration value) {
    Objects.requireNonNull(value);
    if (value.compareTo(Duration.ofMillis(1)) < 0 || value.compareTo(Duration.ofSeconds(30)) > 0) {
      throw new IllegalArgumentException("Gateway timeout must be between 1 ms and 30 seconds.");
    }
    return value;
  }

  private static ConnectorException unavailable(Throwable cause) {
    return new ConnectorException(
        "GATEWAY_UNAVAILABLE", "The loopback gateway is unavailable.", cause);
  }
}
