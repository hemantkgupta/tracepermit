package io.tracepermit.agentcore.httpcli;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Pattern;

/** Exact numeric-loopback HTTP/1.1 test client. */
final class RegistrationHttpClient {
  private static final Pattern LENGTH = Pattern.compile("Content-Length: ([0-9]+)\\r\\n");

  private RegistrationHttpClient() {}

  static Reply send(int port, String authorization, ObjectNodeBody request) throws Exception {
    return send(port, authorization, null, request.bytes());
  }

  static Reply send(int port, String authorization, String human, byte[] body) throws Exception {
    String humanHeader = human == null ? "" : "Human-Authorization: " + human + "\r\n";
    byte[] head =
        ("POST /v1/agentcore/registrations HTTP/1.1\r\n"
                + "Host: 127.0.0.1:"
                + port
                + "\r\nAuthorization: "
                + authorization
                + "\r\n"
                + humanHeader
                + "Content-Type: application/json\r\nContent-Length: "
                + body.length
                + "\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII);
    byte[] wire = Arrays.copyOf(head, head.length + body.length);
    System.arraycopy(body, 0, wire, head.length, body.length);
    try (var socket =
        new Socket(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), port)) {
      socket.setSoTimeout(5_000);
      socket.getOutputStream().write(wire);
      socket.getOutputStream().flush();
      return read(socket);
    } catch (java.net.SocketException unavailable) {
      return new Reply(0, "");
    }
  }

  private static Reply read(Socket socket) throws Exception {
    var output = new ByteArrayOutputStream();
    byte[] buffer = new byte[512];
    while (output.size() <= 8_192) {
      int read = socket.getInputStream().read(buffer);
      if (read < 0) break;
      output.write(buffer, 0, read);
      String current = output.toString(StandardCharsets.UTF_8);
      int split = current.indexOf("\r\n\r\n");
      var matcher = LENGTH.matcher(current);
      if (split >= 0 && matcher.find()) {
        int expected = split + 4 + Integer.parseInt(matcher.group(1));
        if (output.size() >= expected) break;
      }
    }
    String raw = output.toString(StandardCharsets.UTF_8);
    int split = raw.indexOf("\r\n\r\n");
    if (split <= 0 || raw.length() < 12) throw new AssertionError("SYNTHETIC_HTTP_INVALID");
    return new Reply(Integer.parseInt(raw.substring(9, 12)), raw.substring(split + 4));
  }

  record Reply(int status, String body) {}

  record ObjectNodeBody(com.fasterxml.jackson.databind.node.ObjectNode value) {
    byte[] bytes() {
      return HttpCliFixtures.bytes(value);
    }
  }
}
