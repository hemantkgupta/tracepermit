package io.tracepermit.adapters.http.requester;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** One flow, one callback attempt, exact numeric IPv4 loopback. */
final class RequesterCallbackListener {
  @FunctionalInterface interface BrowserOpener { void open(URI authorizationUri) throws Exception; }

  private static final int MAXIMUM_HEAD_BYTES = 16 * 1024;
  private static final int MAXIMUM_QUERY_BYTES = 8 * 1024;
  private static final int MAXIMUM_HEADERS = 32;
  private final URI callbackUri;
  private final Duration flowTimeout;
  private final Duration connectionTimeout;

  RequesterCallbackListener(URI callbackUri) {
    this(callbackUri, Duration.ofSeconds(120), Duration.ofSeconds(5));
  }

  RequesterCallbackListener(URI callbackUri, Duration flowTimeout, Duration connectionTimeout) {
    this.callbackUri = callbackUri;
    this.flowTimeout = bounded(flowTimeout, 120);
    this.connectionTimeout = bounded(connectionTimeout, 5);
  }

  String await(String expectedState, String expectedIssuer, URI authorizationUri,
      BrowserOpener browser) {
    try (ServerSocket listener = new ServerSocket()) {
      listener.setReuseAddress(false);
      try {
        listener.bind(new InetSocketAddress(
            InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), callbackUri.getPort()), 1);
      } catch (BindException failure) {
        throw new InstalledRequesterFailure("CALLBACK_LISTENER_CONFLICT");
      }
      listener.setSoTimeout(Math.toIntExact(flowTimeout.toMillis()));
      browser.open(authorizationUri);
      try (Socket connection = listener.accept()) {
        connection.setSoTimeout(Math.toIntExact(connectionTimeout.toMillis()));
        try {
          Map<String, String> parameters = parse(connection);
          if (!expectedState.equals(parameters.get("state"))
              || !expectedIssuer.equals(parameters.get("iss"))) {
            respond(connection, 400, "Sign-in rejected.");
            throw new InstalledRequesterFailure("REQUESTER_STATE_MISMATCH");
          }
          respond(connection, 200, "Sign-in received. Return to the terminal.");
          return parameters.get("code");
        } catch (InstalledRequesterFailure failure) {
          if (!"REQUESTER_STATE_MISMATCH".equals(failure.code())) {
            respond(connection, 400, "Sign-in rejected.");
          }
          throw failure;
        }
      } catch (SocketTimeoutException failure) {
        throw new InstalledRequesterFailure("REQUESTER_LOGIN_TIMEOUT");
      }
    } catch (InstalledRequesterFailure failure) {
      throw failure;
    } catch (Exception failure) {
      throw new InstalledRequesterFailure("REQUESTER_CALLBACK_INVALID");
    }
  }

  private Map<String, String> parse(Socket connection) {
    try {
      byte[] head = readHead(connection);
      String request = new String(head, StandardCharsets.US_ASCII);
      String[] lines = request.split("\\r\\n", -1);
      require(lines.length >= 3 && lines[lines.length - 1].isEmpty()
          && lines[lines.length - 2].isEmpty());
      String[] requestLine = lines[0].split(" ", -1);
      require(requestLine.length == 3 && "GET".equals(requestLine[0])
          && "HTTP/1.1".equals(requestLine[2]));
      String target = requestLine[1];
      int queryAt = target.indexOf('?');
      require(queryAt > 0 && target.substring(0, queryAt).equals(callbackUri.getRawPath()));
      String rawQuery = target.substring(queryAt + 1);
      require(!rawQuery.isEmpty()
          && rawQuery.getBytes(StandardCharsets.US_ASCII).length <= MAXIMUM_QUERY_BYTES);

      Map<String, String> headers = new HashMap<>();
      int count = 0;
      for (int index = 1; index < lines.length - 2; index++) {
        require(++count <= MAXIMUM_HEADERS);
        int colon = lines[index].indexOf(':');
        require(colon > 0);
        String name = lines[index].substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT);
        String value = lines[index].substring(colon + 1).trim();
        require(name.matches("[a-z0-9-]{1,64}") && !value.isEmpty()
            && value.chars().noneMatch(Character::isISOControl)
            && headers.putIfAbsent(name, value) == null);
      }
      require(headers.size() == count
          && ("127.0.0.1:" + callbackUri.getPort()).equals(headers.get("host"))
          && !headers.containsKey("authorization") && !headers.containsKey("cookie")
          && !headers.containsKey("transfer-encoding")
          && (!headers.containsKey("content-length")
              || "0".equals(headers.get("content-length"))));
      return query(rawQuery);
    } catch (InstalledRequesterFailure failure) {
      throw failure;
    } catch (IOException | RuntimeException failure) {
      throw new InstalledRequesterFailure("REQUESTER_CALLBACK_INVALID");
    }
  }

  private static byte[] readHead(Socket connection) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    int state = 0;
    while (output.size() <= MAXIMUM_HEAD_BYTES) {
      int value = connection.getInputStream().read();
      if (value < 0) break;
      require(value == '\r' || value == '\n' || value >= 0x20 && value <= 0x7e);
      output.write(value);
      state = switch (state) {
        case 0 -> value == '\r' ? 1 : 0;
        case 1 -> value == '\n' ? 2 : 0;
        case 2 -> value == '\r' ? 3 : 0;
        case 3 -> value == '\n' ? 4 : 0;
        default -> state;
      };
      if (state == 4) return output.toByteArray();
    }
    throw new InstalledRequesterFailure("REQUESTER_CALLBACK_INVALID");
  }

  private static Map<String, String> query(String raw) {
    Map<String, String> result = new HashMap<>();
    for (String pair : raw.split("&", -1)) {
      String[] parts = pair.split("=", -1);
      require(parts.length == 2);
      String name = decode(parts[0], 32);
      int bound = "code".equals(name) ? 4096 : "state".equals(name) ? 128 : 512;
      String value = decode(parts[1], bound);
      require(!value.isBlank() && result.putIfAbsent(name, value) == null);
    }
    require(result.keySet().equals(Set.of("code", "state", "iss")));
    return Map.copyOf(result);
  }

  private static String decode(String value, int maximum) {
    require(!value.isEmpty() && value.length() <= maximum * 3 && value.indexOf('+') < 0);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    for (int index = 0; index < value.length();) {
      char character = value.charAt(index);
      if (character == '%') {
        require(index + 2 < value.length());
        int high = Character.digit(value.charAt(index + 1), 16);
        int low = Character.digit(value.charAt(index + 2), 16);
        require(high >= 0 && low >= 0);
        bytes.write((high << 4) | low);
        index += 3;
      } else {
        require(character >= 0x21 && character <= 0x7e && character != '&'
            && character != '=');
        bytes.write(character);
        index++;
      }
    }
    String decoded;
    try {
      decoded = StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
    } catch (java.nio.charset.CharacterCodingException failure) {
      throw new InstalledRequesterFailure("REQUESTER_CALLBACK_INVALID");
    }
    require(decoded.length() <= maximum
        && decoded.chars().noneMatch(Character::isISOControl));
    return decoded;
  }

  private static void respond(Socket connection, int status, String message) {
    try {
      byte[] body = ("<!doctype html><title>TracePermit</title><p>" + message + "</p>")
          .getBytes(StandardCharsets.UTF_8);
      String headers = "HTTP/1.1 " + status + (status == 200 ? " OK" : " Bad Request")
          + "\r\nContent-Type: text/html; charset=utf-8"
          + "\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer"
          + "\r\nX-Content-Type-Options: nosniff\r\nConnection: close"
          + "\r\nContent-Length: " + body.length + "\r\n\r\n";
      connection.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
      connection.getOutputStream().write(body);
      connection.getOutputStream().flush();
    } catch (IOException ignored) {
      // Callback outcome remains bounded even if the browser disconnects before the fixed page.
    }
  }

  private static Duration bounded(Duration value, int maximumSeconds) {
    if (value == null || value.isZero() || value.isNegative()
        || value.compareTo(Duration.ofSeconds(maximumSeconds)) > 0) {
      throw new IllegalArgumentException();
    }
    return value;
  }

  private static void require(boolean condition) {
    if (!condition) throw new InstalledRequesterFailure("REQUESTER_CALLBACK_INVALID");
  }
}
