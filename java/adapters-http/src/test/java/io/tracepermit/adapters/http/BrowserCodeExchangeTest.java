package io.tracepermit.adapters.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class BrowserCodeExchangeTest {
  private static final URI ENDPOINT = URI.create("https://idp.example.invalid/oauth/token");
  private static final int PORT = 18085;
  private static final String CLIENT = "synthetic-client";

  private static Map<String, String> form() {
    return new HashMap<>(Map.of("grant_type", "authorization_code", "code", UUID.randomUUID().toString(),
        "redirect_uri", "http://127.0.0.1:" + PORT + "/auth/callback",
        "code_verifier", UUID.randomUUID().toString() + UUID.randomUUID()));
  }

  @Test void publicClientSendsOnlyCodePkceRedirectAndClientIdWithoutAuthentication() {
    var input = form();
    var calls = new AtomicInteger();
    var exchange = new BrowserCodeExchange(ENDPOINT, PORT, CLIENT, "none", null, (uri, body, auth) -> {
      calls.incrementAndGet();
      assertThat(uri).isEqualTo(ENDPOINT);
      assertThat(auth == null).isTrue();
      assertThat(body.keySet()).containsExactlyInAnyOrder("grant_type", "code", "redirect_uri", "code_verifier", "client_id");
      assertThat(CLIENT.equals(body.get("client_id"))).isTrue();
      assertThat(body.entrySet().containsAll(input.entrySet())).isTrue();
      return "{}";
    });
    assertThat(exchange.exchange(input)).isEqualTo("{}");
    assertThat(calls).hasValue(1);
    assertThat(input).doesNotContainKey("client_id");
  }

  @Test void confidentialClientPreservesBasicEncodingAndNeverSendsBodySecret() {
    String secret = UUID.randomUUID().toString() + ":+";
    var exchange = new BrowserCodeExchange(ENDPOINT, PORT, CLIENT, "client_secret_basic", secret,
        (uri, body, auth) -> {
          String expected = "Basic " + Base64.getEncoder().encodeToString((CLIENT + ":"
              + java.net.URLEncoder.encode(secret, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
          assertThat(expected.equals(auth)).isTrue();
          assertThat(body.keySet()).containsExactlyInAnyOrder("grant_type", "code", "redirect_uri", "code_verifier");
          return "{}";
        });
    assertThat(exchange.exchange(form())).isEqualTo("{}");
    assertThat(exchange.toString().contains(secret)).isFalse();
  }

  @ParameterizedTest @ValueSource(strings = {"client_secret_post", "private_key_jwt", "NONE", "", "unknown"})
  void unsupportedAuthenticationMethodsNeverFallBack(String method) {
    assertThatThrownBy(() -> new BrowserCodeExchange(ENDPOINT, PORT, CLIENT, method, null, (u, b, a) -> "{}"))
        .hasMessage("INVALID_APPROVAL_CONFIGURATION").hasNoCause();
  }

  @Test void secretInjectionMissingConfidentialSecretAndInvalidPortFailClosed() {
    for (String secret : new String[] {"", UUID.randomUUID().toString()}) {
      assertThatThrownBy(() -> new BrowserCodeExchange(ENDPOINT, PORT, CLIENT, "none", secret, (u, b, a) -> "{}"))
          .hasMessage("INVALID_APPROVAL_CONFIGURATION");
    }
    for (String secret : new String[] {null, "", " "}) {
      assertThatThrownBy(() -> new BrowserCodeExchange(ENDPOINT, PORT, CLIENT, "client_secret_basic", secret, (u, b, a) -> "{}"))
          .hasMessage("INVALID_APPROVAL_CONFIGURATION");
    }
    for (int port : new int[] {0, -1, 65536}) {
      assertThatThrownBy(() -> new BrowserCodeExchange(ENDPOINT, port, CLIENT, "none", null, (u, b, a) -> "{}"))
          .hasMessage("INVALID_APPROVAL_CONFIGURATION");
    }
  }

  @ParameterizedTest @ValueSource(strings = {"code", "grant_type", "code_verifier", "redirect_uri", "client_secret", "client_id", "short_verifier", "remote_redirect", "localhost_redirect", "port_redirect", "path_redirect"})
  void invalidFormsAreRejectedBeforeTransport(String change) {
    var body = form();
    switch (change) {
      case "client_secret", "client_id" -> body.put(change, UUID.randomUUID().toString());
      case "short_verifier" -> body.put("code_verifier", "short");
      case "remote_redirect" -> body.put("redirect_uri", "https://attacker.example.invalid/auth/callback");
      case "localhost_redirect" -> body.put("redirect_uri", "http://localhost:" + PORT + "/auth/callback");
      case "port_redirect" -> body.put("redirect_uri", "http://127.0.0.1:18086/auth/callback");
      case "path_redirect" -> body.put("redirect_uri", "http://127.0.0.1:" + PORT + "/auth/callback/extra");
      default -> body.remove(change);
    }
    var calls = new AtomicInteger();
    var exchange = new BrowserCodeExchange(ENDPOINT, PORT, CLIENT, "none", null,
        (uri, value, auth) -> { calls.incrementAndGet(); return "{}"; });
    assertThatThrownBy(() -> exchange.exchange(body)).hasMessage("BROWSER_LOGIN_FAILED").hasNoCause();
    assertThat(calls).hasValue(0);
  }
}
