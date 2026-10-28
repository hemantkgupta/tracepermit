package io.tracepermit.agentcore.registration;

import io.tracepermit.agentcore.http.AgentCoreRegistrationHttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/** Test-only exact numeric-loopback HTTP client with bounded response framing. */
final class RegistrationHttpTestClient {
  private static final int MAXIMUM_RESPONSE_BYTES = 8_192;
  private static final Pattern CONTENT_LENGTH = Pattern.compile("Content-Length: ([0-9]+)\\r\\n");

  private RegistrationHttpTestClient() {}

  static Reply send(int port, String authorization, byte[] body) throws IOException {
    return send(port, authorization, null, body);
  }

  static Reply send(int port, String authorization, String humanAuthorization, byte[] body)
      throws IOException {
    List<String> fields = new ArrayList<>();
    fields.add("Host: 127.0.0.1:" + port);
    fields.add("Authorization: " + authorization);
    if (humanAuthorization != null) fields.add("Human-Authorization: " + humanAuthorization);
    fields.add("Content-Type: application/json");
    fields.add("Content-Length: " + body.length);
    byte[] head =
        ("POST "
                + AgentCoreRegistrationHttpServer.ROUTE
                + " HTTP/1.1\r\n"
                + String.join("\r\n", fields)
                + "\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII);
    byte[] wire = Arrays.copyOf(head, head.length + body.length);
    System.arraycopy(body, 0, wire, head.length, body.length);
    try (Socket socket =
        new Socket(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), port)) {
      socket.setSoTimeout(2_000);
      socket.getOutputStream().write(wire);
      socket.getOutputStream().flush();
      return read(socket);
    } catch (SocketTimeoutException timeout) {
      return new Reply(0, "", "", Outcome.TIMEOUT);
    } catch (java.net.SocketException closed) {
      return new Reply(0, "", "", Outcome.SOCKET_CLOSED);
    }
  }

  private static Reply read(Socket socket) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[512];
    while (true) {
      int count = socket.getInputStream().read(buffer);
      if (count < 0) break;
      if (output.size() + count > MAXIMUM_RESPONSE_BYTES) {
        throw new AssertionError("SYNTHETIC_HTTP_RESPONSE_TOO_LARGE");
      }
      output.write(buffer, 0, count);
      String current = output.toString(StandardCharsets.UTF_8);
      int end = current.indexOf("\r\n\r\n");
      if (end >= 0) {
        var length = CONTENT_LENGTH.matcher(current);
        if (length.find() && output.size() >= end + 4 + Integer.parseInt(length.group(1))) break;
      }
    }
    String raw = output.toString(StandardCharsets.UTF_8);
    if (raw.isEmpty()) return new Reply(0, "", "", Outcome.EMPTY_RESPONSE);
    int split = raw.indexOf("\r\n\r\n");
    if (split <= 0 || raw.length() < 12) {
      throw new AssertionError("SYNTHETIC_HTTP_RESPONSE_INVALID");
    }
    return new Reply(
        Integer.parseInt(raw.substring(9, 12)),
        raw.substring(split + 4),
        raw.substring(0, split), Outcome.RESPONSE);
  }

  enum Outcome { RESPONSE, TIMEOUT, SOCKET_CLOSED, EMPTY_RESPONSE }

  record Reply(int status, String body, String headers, Outcome outcome) {
    @Override
    public String toString() {
      return "RegistrationHttpTestReply[status=" + status + "]";
    }
  }
}
