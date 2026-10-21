package io.tracepermit.adapters.http;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Explicit token authentication; never infer a public client from a missing secret. */
final class BrowserCodeExchange implements ApprovalBrowserAdapter.CodeExchange {
  @FunctionalInterface interface TokenPost {
    String post(URI uri, Map<String, String> form, String authorization);
  }
  private final URI endpoint;
  private final String callback;
  private final String clientId;
  private final String authorization;
  private final TokenPost transport;

  BrowserCodeExchange(URI endpoint, int port, String clientId, String method,
      String secret, TokenPost transport) {
    if (port < 1 || port > 65535 || clientId == null || clientId.isBlank()
        || !("none".equals(method) && secret == null
            || "client_secret_basic".equals(method) && secret != null && !secret.isBlank())) {
      throw new IllegalArgumentException("INVALID_APPROVAL_CONFIGURATION");
    }
    this.endpoint = endpoint;
    this.callback = "http://127.0.0.1:" + port + "/auth/callback";
    this.clientId = clientId;
    this.transport = java.util.Objects.requireNonNull(transport);
    this.authorization = "none".equals(method) ? null : "Basic " + Base64.getEncoder().encodeToString(
        (URLEncoder.encode(clientId, StandardCharsets.UTF_8) + ":"
            + URLEncoder.encode(secret, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
  }

  @Override public String exchange(Map<String, String> form) {
    // Closed schema prevents secret injection and redirect substitution before any network call.
    if (form == null || !form.keySet().equals(Set.of("grant_type", "code", "redirect_uri", "code_verifier"))
        || !"authorization_code".equals(form.get("grant_type"))
        || !callback.equals(form.get("redirect_uri"))
        || form.get("code") == null || form.get("code").isBlank() || form.get("code").length() > 4096
        || form.get("code_verifier") == null
        || !form.get("code_verifier").matches("[A-Za-z0-9._~-]{43,128}")) {
      throw new SecurityException("BROWSER_LOGIN_FAILED");
    }
    var body = new HashMap<>(form);
    if (authorization == null) body.put("client_id", clientId);
    return transport.post(endpoint, Map.copyOf(body), authorization);
  }
}
