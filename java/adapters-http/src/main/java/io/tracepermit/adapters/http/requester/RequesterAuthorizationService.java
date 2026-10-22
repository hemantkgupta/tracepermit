package io.tracepermit.adapters.http.requester;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** One explicit browser authorization, token exchange, qualification, and publication. */
final class RequesterAuthorizationService {
  @FunctionalInterface interface TokenExchange { String exchange(Map<String, String> form); }

  private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private final InstalledRequesterConfiguration configuration;
  private final RequesterTokenVerifier verifier;
  private final RequesterCallbackListener listener;
  private final RequesterCallbackListener.BrowserOpener browser;
  private final TokenExchange exchange;
  private final RequesterQualificationClient.QualificationCall qualification;
  private final RequesterCredentialStore credentials;
  private final SecureRandom random;
  private final Clock clock;

  RequesterAuthorizationService(InstalledRequesterConfiguration configuration,
      RequesterTokenVerifier verifier, RequesterCallbackListener listener,
      RequesterCallbackListener.BrowserOpener browser, TokenExchange exchange,
      RequesterQualificationClient.QualificationCall qualification,
      RequesterCredentialStore credentials, SecureRandom random, Clock clock) {
    this.configuration = configuration;
    this.verifier = verifier;
    this.listener = listener;
    this.browser = browser;
    this.exchange = exchange;
    this.qualification = qualification;
    this.credentials = credentials;
    this.random = random;
    this.clock = clock;
  }

  void login() {
    String state = randomToken();
    String nonce = randomToken();
    String codeVerifier = randomToken();
    URI authorization = authorizationUri(state, nonce, challenge(codeVerifier));
    String code = listener.await(state, configuration.issuer().toString(), authorization, browser);
    String response;
    try {
      response = exchange.exchange(Map.of(
          "grant_type", "authorization_code",
          "code", code,
          "redirect_uri", configuration.callbackUri().toString(),
          "code_verifier", codeVerifier,
          "client_id", configuration.clientId()));
    } catch (RuntimeException failure) {
      throw new InstalledRequesterFailure("TOKEN_EXCHANGE_FAILED");
    }
    Tokens tokens = tokens(response);
    RequesterTokenVerifier.VerifiedAccess verified =
        verifier.verifyLogin(tokens.accessToken, tokens.idToken, nonce);
    qualification.qualify(tokens.accessToken);
    credentials.publish(tokens.accessToken, verified.expiresAt());
  }

  URI authorizationUri(String state, String nonce, String challenge) {
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("response_type", "code");
    fields.put("client_id", configuration.clientId());
    fields.put("redirect_uri", configuration.callbackUri().toString());
    fields.put("scope", InstalledRequesterConfiguration.AUTHORIZATION_SCOPE);
    fields.put("audience", configuration.audience());
    fields.put("organization", configuration.organization());
    fields.put("state", state);
    fields.put("nonce", nonce);
    fields.put("code_challenge", challenge);
    fields.put("code_challenge_method", "S256");
    return URI.create(configuration.authorizationUri() + "?" + form(fields));
  }

  Tokens tokens(String response) {
    try {
      if (response == null || response.isEmpty()
          || response.getBytes(StandardCharsets.UTF_8).length > 65_536) {
        throw new IllegalArgumentException();
      }
      JsonNode root = JSON.readTree(response);
      Set<String> allowed = Set.of("access_token", "id_token", "token_type", "expires_in", "scope");
      if (root == null || !root.isObject() || root.size() < 3 || root.size() > 5) {
        throw new IllegalArgumentException();
      }
      root.fieldNames().forEachRemaining(name -> {
        if (!allowed.contains(name)) throw new IllegalArgumentException();
      });
      String accessToken = text(root, "access_token", 16_384);
      String idToken = text(root, "id_token", 16_384);
      if (!"Bearer".equals(text(root, "token_type", 16))) throw new IllegalArgumentException();
      if (root.has("expires_in") && (!root.path("expires_in").isIntegralNumber()
          || root.path("expires_in").longValue() < 60
          || root.path("expires_in").longValue() > 900)) {
        throw new IllegalArgumentException();
      }
      if (root.has("scope")) {
        Set<String> scopes = Set.of(text(root, "scope", 256).split(" ", -1));
        if (scopes.size() < 3 || scopes.size() > 4
            || !scopes.containsAll(Set.of("approvals.read", "approvals.request", "approvals.execute"))
            || !Set.of("openid", "approvals.read", "approvals.request", "approvals.execute")
                .containsAll(scopes)) {
          throw new IllegalArgumentException();
        }
      }
      return new Tokens(accessToken, idToken);
    } catch (RuntimeException | java.io.IOException failure) {
      throw new InstalledRequesterFailure("PROVIDER_PROFILE_UNSUPPORTED");
    }
  }

  private String randomToken() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String challenge(String verifier) {
    try {
      return Base64.getUrlEncoder().withoutPadding().encodeToString(
          MessageDigest.getInstance("SHA-256")
              .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    } catch (java.security.GeneralSecurityException impossible) {
      throw new IllegalStateException();
    }
  }

  private static String form(Map<String, String> fields) {
    return fields.entrySet().stream().map(entry ->
        URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
            + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
        .collect(java.util.stream.Collectors.joining("&"));
  }

  private static String text(JsonNode node, String field, int maximum) {
    JsonNode value = node.path(field);
    if (!value.isTextual() || value.textValue().isBlank()
        || value.textValue().length() > maximum
        || value.textValue().chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException();
    }
    return value.textValue();
  }

  static final class Tokens {
    final String accessToken;
    final String idToken;
    Tokens(String accessToken, String idToken) {
      this.accessToken = accessToken;
      this.idToken = idToken;
    }
  }
}
