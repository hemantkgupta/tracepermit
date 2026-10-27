package io.tracepermit.adapters.http;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.identity.AuthenticatedConnector;
import io.tracepermit.identity.BrowserIdTokenVerifier;
import io.tracepermit.identity.OidcAccessTokenVerifier;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Optional numeric-loopback HTTP browser boundary. Never enable behind a tunnel/proxy. */
final class ApprovalBrowserAdapter {
  static final String API = "/browser/api/v1/approvals";
  private static final String COOKIE = "tp_local_session";
  private static final String FLOW_COOKIE = "tp_local_login";
  private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  @FunctionalInterface interface CodeExchange { String exchange(Map<String, String> form); }
  private record Flow(String state, String nonce, String verifier, Instant expires) {}
  // No generated accessor/toString for usable credentials, even in diagnostics.
  private static final class Session {
    final String accessToken;
    final String csrf;
    final Instant expires;
    Session(String accessToken, String csrf, Instant expires) {
      this.accessToken = accessToken; this.csrf = csrf; this.expires = expires;
    }
  }
  private final Map<String, Flow> flows = new HashMap<>();
  private final Map<String, Session> sessions = new HashMap<>();
  private final SecureRandom random = new SecureRandom();
  private final URI authorizationUri;
  private final String issuer;
  private final String audience;
  private final String clientId;
  private final Path console;
  private final CodeExchange exchange;
  private final OidcAccessTokenVerifier access;
  private final BrowserIdTokenVerifier identity;
  private final ApprovalMemberships memberships;
  private final Clock clock;
  private final String organization;
  private final BrowserLoginDiagnostics diagnostics;

  ApprovalBrowserAdapter(URI authorizationUri, String issuer, String audience, String clientId,
      Path console, CodeExchange exchange, OidcAccessTokenVerifier access,
      BrowserIdTokenVerifier identity, ApprovalMemberships memberships, Clock clock) {
    this(authorizationUri, issuer, audience, clientId, console, exchange, access, identity, memberships, clock, null);
  }

  ApprovalBrowserAdapter(URI authorizationUri, String issuer, String audience, String clientId,
      Path console, CodeExchange exchange, OidcAccessTokenVerifier access,
      BrowserIdTokenVerifier identity, ApprovalMemberships memberships, Clock clock, String organization) {
    this(authorizationUri, issuer, audience, clientId, console, exchange, access, identity,
        memberships, clock, organization, BrowserLoginDiagnostics.silent());
  }

  ApprovalBrowserAdapter(URI authorizationUri, String issuer, String audience, String clientId,
      Path console, CodeExchange exchange, OidcAccessTokenVerifier access,
      BrowserIdTokenVerifier identity, ApprovalMemberships memberships, Clock clock,
      String organization, BrowserLoginDiagnostics diagnostics) {
    this.authorizationUri = authorizationUri; this.issuer = issuer; this.audience = audience;
    this.clientId = clientId; this.console = console.toAbsolutePath().normalize();
    this.exchange = exchange; this.access = access; this.identity = identity;
    this.memberships = memberships; this.clock = clock;
    this.organization = organization;
    this.diagnostics = diagnostics;
  }

  /** Returns true when this is a browser non-action route, including static assets. */
  boolean handle(HttpExchange request, int port) throws IOException {
    String path = request.getRequestURI().getRawPath();
    if (path.startsWith(API)) return false;
    if (!Set.of("/approvals", "/auth/login", "/auth/callback", "/browser/session", "/browser/logout")
        .contains(path) && !path.startsWith("/assets/")) return false;
    common(request, port);
    if (!"/auth/callback".equals(path) && request.getRequestURI().getRawQuery() != null) fail(400, "INVALID_REQUEST");
    switch (path) {
      case "/auth/login" -> {
        method(request, "GET");
        String binder = token();
        Flow flow = new Flow(token(), token(), token(), clock.instant().plusSeconds(120));
        synchronized (this) {
          purge();
          if (flows.size() >= 1000) fail(503, "LOGIN_CAPACITY_REACHED");
          flows.put(binder, flow);
        }
        cookie(request, FLOW_COOKIE, binder, 120);
        var parameters = new HashMap<>(Map.of(
            "response_type", "code", "client_id", clientId, "redirect_uri", callback(port),
            "scope", (identity.usesOktaProfile() ? "openid groups " : "openid ")
                + "approvals.read approvals.request approvals.decide approvals.execute approvals.cancel",
            "state", flow.state(), "nonce", flow.nonce(),
            "code_challenge", challenge(flow.verifier()), "code_challenge_method", "S256"));
        if (!identity.usesOktaProfile()) {
          parameters.put("audience", audience);
          if (organization != null) parameters.put("organization", organization);
        }
        redirect(request, authorizationUri + "?" + form(parameters));
      }
      case "/auth/callback" -> callback(request, port);
      case "/browser/session" -> {
        method(request, "GET");
        Session session = session(request);
        AuthenticatedConnector connector = verified(session);
        var member = memberships.resolve(connector);
        var response = JsonSupport.MAPPER.createObjectNode().put("mode", "simulated")
            .put("principalId", member.principalId()).put("tenantId", member.tenant().value())
            .put("human", member.human()).put("csrfToken", session.csrf)
            .put("expiresAt", session.expires.toString());
        var roles = response.putArray("roles");
        member.roles().stream().map(Enum::name).sorted().forEach(roles::add);
        var scopes = response.putArray("scopes");
        connector.scopes().stream().map(io.tracepermit.identity.ConnectorScope::value).sorted().forEach(scopes::add);
        json(request, response);
      }
      case "/browser/logout" -> {
        method(request, "POST");
        Session session = session(request);
        csrf(request, session, port);
        synchronized (this) { sessions.remove(cookies(request).get(COOKIE)); }
        cookie(request, COOKIE, "", 0);
        json(request, JsonSupport.MAPPER.createObjectNode().put("signedOut", true));
      }
      default -> {
        method(request, "GET");
        Path file;
        if ("/approvals".equals(path)) file = console.resolve("index.html");
        else {
          if (!path.matches("/assets/[A-Za-z0-9_-]+\\.(js|css|woff2)")) fail(404, "NOT_FOUND");
          file = console.resolve(path.substring(1));
        }
        if (!Files.isRegularFile(file) || !file.toRealPath().startsWith(console.toRealPath())
            || Files.size(file) > 2_000_000) fail(404, "NOT_FOUND");
        byte[] bytes = Files.readAllBytes(file);
        String type = file.toString().endsWith(".js") ? "text/javascript"
            : file.toString().endsWith(".css") ? "text/css"
            : file.toString().endsWith(".woff2") ? "font/woff2" : "text/html; charset=utf-8";
        request.getResponseHeaders().set("Content-Type", type);
        request.sendResponseHeaders(200, bytes.length);
        request.getResponseBody().write(bytes);
      }
    }
    return true;
  }

  AuthenticatedConnector authenticate(HttpExchange request, int port) {
    common(request, port);
    Session session = session(request);
    if (!"GET".equals(request.getRequestMethod())) csrf(request, session, port);
    return verified(session);
  }

  /** Bound cancellation authority to the browser session too, not just its access token. */
  Instant authorityExpiresAt(HttpExchange request) {
    return session(request).expires;
  }

  private void callback(HttpExchange request, int port) throws IOException {
    method(request, "GET");
    Map<String, String> query = query(request.getRequestURI().getRawQuery());
    String binder = cookies(request).get(FLOW_COOKIE);
    Flow flow;
    synchronized (this) { purge(); flow = flows.remove(binder); }
    cookie(request, FLOW_COOKIE, "", 0);
    if (flow == null || !Set.of("code", "state", "iss").equals(query.keySet())
        || !flow.state().equals(query.get("state")) || !issuer.equals(query.get("iss"))) {
      diagnostics.rejected(BrowserLoginDiagnostics.Stage.CALLBACK_BINDING);
      fail(401, "BROWSER_LOGIN_FAILED");
    }
    // Code and all token response contents stay inside this trusted adapter; never log them.
    BrowserLoginDiagnostics.Stage stage = BrowserLoginDiagnostics.Stage.CODE_EXCHANGE;
    try {
      String result = exchange.exchange(Map.of("grant_type", "authorization_code", "code", query.get("code"),
          "redirect_uri", callback(port), "code_verifier", flow.verifier()));
      stage = BrowserLoginDiagnostics.Stage.TOKEN_RESPONSE;
      if (result == null || result.length() > 65_536) throw new IllegalArgumentException();
      JsonNode tokens = JSON.readTree(result);
      if (!tokens.isObject() || !tokens.path("access_token").isTextual()
          || !tokens.path("id_token").isTextual() || !"Bearer".equals(tokens.path("token_type").asText())) {
        throw new IllegalArgumentException();
      }
      String bearer = tokens.path("access_token").textValue();
      stage = BrowserLoginDiagnostics.Stage.ACCESS_TOKEN;
      AuthenticatedConnector connector = access.authenticate("Bearer " + bearer);
      // openid selects OIDC at authorization time; it is not an API permission.
      stage = BrowserLoginDiagnostics.Stage.API_SCOPE;
      if (organization != null && !connector.scopes().contains(
          io.tracepermit.identity.ConnectorScope.of("approvals.read"))) throw new SecurityException();
      stage = BrowserLoginDiagnostics.Stage.SENDER_CONSTRAINT;
      if (connector.senderConstraint().isPresent()) throw new SecurityException();
      stage = BrowserLoginDiagnostics.Stage.MEMBERSHIP;
      var member = memberships.resolve(connector);
      stage = BrowserLoginDiagnostics.Stage.HUMAN_IDENTITY;
      if (!member.human()) throw new SecurityException();
      stage = BrowserLoginDiagnostics.Stage.ID_TOKEN;
      Instant idExpiry = identity.verify(tokens.path("id_token").textValue(), clientId, flow.nonce(), bearer, connector);
      Instant expires = java.util.Collections.min(java.util.List.of(idExpiry,
          connector.expiresAt(), clock.instant().plusSeconds(600)));
      String id = token();
      stage = BrowserLoginDiagnostics.Stage.SESSION_CAPACITY;
      synchronized (this) {
        purge();
        if (sessions.size() >= 1000) fail(503, "SESSION_CAPACITY_REACHED");
        String previous = cookies(request).get(COOKIE);
        if (previous != null) sessions.remove(previous);
        sessions.put(id, new Session(bearer, token(), expires));
      }
      cookie(request, COOKIE, id, (int) (expires.getEpochSecond() - clock.instant().getEpochSecond()));
      redirect(request, "/approvals");
    } catch (Exception failure) {
      diagnostics.rejected(stage, failure);
      fail(401, "BROWSER_LOGIN_FAILED");
    }
  }

  private AuthenticatedConnector verified(Session session) {
    AuthenticatedConnector connector = access.authenticate("Bearer " + session.accessToken);
    if (connector.senderConstraint().isPresent() || !memberships.resolve(connector).human()) {
      fail(403, "NOT_AUTHORIZED");
    }
    return connector;
  }

  private synchronized Session session(HttpExchange request) {
    purge();
    Session session = sessions.get(cookies(request).get(COOKIE));
    if (session == null) fail(401, "BROWSER_SIGN_IN_REQUIRED");
    return session;
  }

  private void purge() {
    Instant now = clock.instant();
    flows.values().removeIf(flow -> !flow.expires().isAfter(now));
    sessions.values().removeIf(session -> !session.expires.isAfter(now));
  }

  synchronized void clear() { flows.clear(); sessions.clear(); }

  private static void common(HttpExchange request, int port) {
    if (request.getRequestHeaders().containsKey("Authorization")) fail(400, "MIXED_AUTHENTICATION");
    var origins = request.getRequestHeaders().getOrDefault("Origin", java.util.List.of());
    if (!origins.isEmpty() && (origins.size() != 1 || !origin(port).equals(origins.getFirst()))) {
      fail(403, "CROSS_ORIGIN_DENIED");
    }
    String site = request.getRequestHeaders().getFirst("Sec-Fetch-Site");
    // Public page/login navigation and the IdP callback must work from an external tab/link.
    // Cross-site access to session/action data remains forbidden, including GET requests.
    if ("cross-site".equals(site) && request.getRequestURI().getRawPath().startsWith("/browser/")) {
      fail(403, "CROSS_ORIGIN_DENIED");
    }
    var headers = request.getResponseHeaders();
    headers.set("Cache-Control", "no-store");
    headers.set("X-Content-Type-Options", "nosniff");
    headers.set("Referrer-Policy", "no-referrer");
    headers.set("X-Frame-Options", "DENY");
    headers.set("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; font-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
  }

  private static void csrf(HttpExchange request, Session session, int port) {
    var headers = request.getRequestHeaders();
    if (headers.getOrDefault("Origin", java.util.List.of()).size() != 1
        || !origin(port).equals(headers.getFirst("Origin"))
        || headers.getOrDefault("X-CSRF-Token", java.util.List.of()).size() != 1
        || !session.csrf.equals(headers.getFirst("X-CSRF-Token"))) fail(403, "CSRF_DENIED");
  }

  private static Map<String, String> cookies(HttpExchange request) {
    Map<String, String> cookies = new HashMap<>();
    var values = request.getRequestHeaders().getOrDefault("Cookie", java.util.List.of());
    if (values.size() > 1) fail(400, "INVALID_COOKIE");
    if (values.isEmpty()) return cookies;
    if (values.getFirst().length() > 4096) fail(400, "INVALID_COOKIE");
    for (String pair : values.getFirst().split(";")) {
      String[] parts = pair.trim().split("=", 2);
      if (parts.length != 2 || cookies.putIfAbsent(parts[0], parts[1]) != null) fail(400, "INVALID_COOKIE");
    }
    return cookies;
  }

  private static Map<String, String> query(String raw) {
    if (raw == null || raw.length() > 4096) { fail(400, "INVALID_REQUEST"); }
    Map<String, String> result = new HashMap<>();
    for (String pair : raw.split("&")) {
      String[] parts = pair.split("=", 2);
      if (parts.length != 2) fail(400, "INVALID_REQUEST");
      String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
      String value = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
      if (value.isBlank() || value.chars().anyMatch(Character::isISOControl)
          || result.putIfAbsent(key, value) != null) fail(400, "INVALID_REQUEST");
    }
    return result;
  }

  static String form(Map<String, String> fields) {
    return fields.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry ->
        URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
        + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
        .collect(java.util.stream.Collectors.joining("&"));
  }

  private String token() {
    byte[] bytes = new byte[32]; random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
  private static String challenge(String verifier) {
    try { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
        .digest(verifier.getBytes(StandardCharsets.US_ASCII))); }
    catch (java.security.GeneralSecurityException impossible) { throw new IllegalStateException(); }
  }
  private static String origin(int port) { return "http://127.0.0.1:" + port; }
  private static String callback(int port) { return origin(port) + "/auth/callback"; }
  private static void cookie(HttpExchange request, String name, String value, int seconds) {
    // Intentionally not Secure: ONLY explicit numeric-loopback HTTP. Never a production cookie profile.
    request.getResponseHeaders().add("Set-Cookie", name + "=" + value
        + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + Math.max(0, seconds));
  }
  private static void redirect(HttpExchange request, String target) throws IOException {
    request.getResponseHeaders().set("Location", target); request.sendResponseHeaders(303, -1);
  }
  private static void method(HttpExchange request, String expected) {
    if (!expected.equals(request.getRequestMethod())) fail(405, "METHOD_NOT_ALLOWED");
  }
  private static void json(HttpExchange request, JsonNode value) throws IOException {
    byte[] body = JsonSupport.MAPPER.writeValueAsBytes(value);
    request.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    request.sendResponseHeaders(200, body.length); request.getResponseBody().write(body);
  }
  static final class BrowserFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    final int status; final String code;
    BrowserFailure(int status, String code) { super(code); this.status = status; this.code = code; }
  }
  private static void fail(int status, String code) { throw new BrowserFailure(status, code); }
}
