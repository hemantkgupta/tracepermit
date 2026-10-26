package io.tracepermit.adapters.http.requester;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** One bodyless, no-retry loopback qualification request before credential publication. */
final class RequesterQualificationClient {
  @FunctionalInterface interface QualificationCall { void qualify(String bearer); }

  static QualificationCall production(URI uri) {
    return bearer -> {
      try (Socket socket = new Socket()) {
        socket.connect(new InetSocketAddress("127.0.0.1", uri.getPort()), 3_000);
        socket.setSoTimeout(5_000);
        String request = "GET " + uri.getRawPath() + " HTTP/1.1\r\n"
            + "Host: 127.0.0.1:" + uri.getPort() + "\r\n"
            + "Authorization: Bearer " + bearer + "\r\n"
            + "Accept: application/json\r\n"
            + "Cache-Control: no-store\r\n"
            + "Connection: close\r\n\r\n";
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        String response = readBounded(socket);
        if (!validNoContent(response)) {
          throw new InstalledRequesterFailure("REQUESTER_ENROLLMENT_REQUIRED");
        }
      } catch (InstalledRequesterFailure failure) {
        throw failure;
      } catch (IOException | RuntimeException failure) {
        throw new InstalledRequesterFailure("REQUESTER_ENROLLMENT_REQUIRED");
      }
    };
  }

  private static boolean validNoContent(String response) {
    if (!response.endsWith("\r\n\r\n")) return false;
    String[] lines = response.split("\r\n", -1);
    if (lines.length < 3 || !lines[0].matches("HTTP/1\\.1 204 [ -~]{1,64}")) return false;
    Map<String, String> headers = new LinkedHashMap<>();
    for (int index = 1; index < lines.length - 2; index++) {
      int colon = lines[index].indexOf(':');
      if (colon < 1) return false;
      String name = lines[index].substring(0, colon).trim()
          .toLowerCase(java.util.Locale.ROOT);
      String value = lines[index].substring(colon + 1).trim();
      if (!name.matches("[a-z0-9-]{1,64}") || value.isEmpty()
          || value.chars().anyMatch(character -> character < 0x20 || character > 0x7e)
          || headers.putIfAbsent(name, value) != null) {
        return false;
      }
    }
    return !headers.containsKey("location") && !headers.containsKey("transfer-encoding")
        && (!headers.containsKey("content-length")
            || "0".equals(headers.get("content-length")));
  }

  private static String readBounded(Socket socket) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    while (output.size() <= 8_192) {
      int value = socket.getInputStream().read();
      if (value < 0) break;
      output.write(value);
    }
    if (output.size() == 0 || output.size() > 8_192) {
      throw new InstalledRequesterFailure("REQUESTER_ENROLLMENT_REQUIRED");
    }
    return output.toString(StandardCharsets.US_ASCII);
  }

  private RequesterQualificationClient() {}
}
