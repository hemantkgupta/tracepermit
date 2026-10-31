package io.tracepermit.auth0reviewer;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.adapters.http.requester.ReviewerCallback;
import io.tracepermit.adapters.remote.SsrfSafeHttpsJwkSetSource;
import io.tracepermit.identity.*;
import java.awt.Desktop;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.*;

/** Identity qualification only. No credential persistence, operation approval or target call. */
final class ReviewerLogin {
  @FunctionalInterface interface Callback { String await(String state, URI authorization); }
  @FunctionalInterface interface Exchange { String exchange(Map<String, String> form); }
  private final ReviewerConfiguration config;
  private final Callback callback;
  private final Exchange exchange;
  private final SecureRandom random;
  private final Clock clock;

  ReviewerLogin(ReviewerConfiguration config, Callback callback, Exchange exchange,
      SecureRandom random, Clock clock) {
    this.config = Objects.requireNonNull(config);
    this.callback = Objects.requireNonNull(callback);
    this.exchange = Objects.requireNonNull(exchange);
    this.random = Objects.requireNonNull(random);
    this.clock = Objects.requireNonNull(clock);
  }

  /** The same fixed live transport/callback for identity-only and operation-review commands. */
  static ReviewerLogin live(ReviewerConfiguration config, Clock clock) {
    var transport = new SsrfSafeHttpsJwkSetSource(Set.of(config.issuer().resolve("oauth/token")),
        Duration.ofSeconds(3), Duration.ofSeconds(5));
    return new ReviewerLogin(config,
        (state, authorization) -> ReviewerCallback.await(ReviewerConfiguration.CALLBACK, state,
            config.issuer().toString(), authorization, uri -> {
              try {
                if (!Desktop.isDesktopSupported()
                    || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                  throw new IllegalStateException();
                }
                Desktop.getDesktop().browse(uri);
              } catch (Exception denied) { throw new IllegalStateException("BROWSER_UNAVAILABLE"); }
            }),
        form -> transport.postForm(config.issuer().resolve("oauth/token"), form, null),
        new SecureRandom(), clock);
  }

  AuthenticatedConnector login() {
    try (var credential = loginForReview()) {
      return credential.identity();
    }
  }

  ReviewCredential loginForReview() {
    try {
      String state = randomValue();
      String verifier = randomValue();
      String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
          MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
      Map<String, String> authorize = new TreeMap<>(Map.of("response_type", "code", "client_id", config.client(),
          "redirect_uri", ReviewerConfiguration.CALLBACK.toString(), "audience", config.audience(),
          "scope", "operations.decide", "state", state, "code_challenge", challenge,
          "code_challenge_method", "S256"));
      URI uri = URI.create(config.issuer().resolve("authorize") + "?" + form(authorize));
      String code = callback.await(state, uri);
      if (code == null || code.isBlank() || code.length() > 4096 ||
          code.chars().anyMatch(Character::isISOControl)) throw rejected();
      String response = exchange.exchange(Map.of("grant_type", "authorization_code", "client_id", config.client(),
          "redirect_uri", ReviewerConfiguration.CALLBACK.toString(), "code", code, "code_verifier", verifier));
      if (response == null || response.getBytes(StandardCharsets.UTF_8).length > 65536) throw rejected();
      JsonNode n = ReviewerConfiguration.JSON.readTree(response);
      Set<String> fields = new HashSet<>();
      n.fieldNames().forEachRemaining(fields::add);
      if (!n.isObject() || !fields.containsAll(Set.of("access_token", "token_type", "expires_in")) ||
          !Set.of("access_token", "token_type", "expires_in", "scope").containsAll(fields) ||
          !"Bearer".equals(n.path("token_type").textValue()) ||
          !n.path("expires_in").isIntegralNumber() || !n.path("expires_in").canConvertToInt() ||
          n.path("expires_in").intValue() < 1 || n.path("expires_in").intValue() > 300 ||
          !n.path("access_token").isTextual() ||
          (n.has("scope") && !"operations.decide".equals(n.path("scope").textValue()))) throw rejected();
      var provider = config.provider();
      var access = new OidcAccessTokenVerifier(new StaticOidcProviderRegistry(List.of(provider)),
          (p, kid, algorithm, at) -> {
            if (p != provider || !config.keyId().equals(kid) || !"RS256".equals(algorithm)) throw rejected();
            return config.key();
          }, clock);
      var verified = access.authenticate("Bearer " + n.path("access_token").textValue());
      if (!verified.subjectId().equals(config.issuer() + "#" + config.subject()) ||
          !OidcTokenProfile.AUTH0.equals(verified.authenticationProfile()) ||
          verified.mappedRole().filter("REVIEWER"::equals).isEmpty() ||
          !verified.clientId().equals(config.client()) ||
          !verified.organizationId().equals(config.tenant()) || verified.senderConstraint().isPresent()) throw rejected();
      return new ReviewCredential(verified,
          n.path("access_token").textValue().getBytes(StandardCharsets.US_ASCII));
    } catch (Exception rejected) { throw rejected(); }
  }

  private String randomValue() {
    byte[] bytes = new byte[32]; random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String form(Map<String, String> fields) {
    return fields.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" +
        URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
  }
  private static IllegalStateException rejected() { return new IllegalStateException("REVIEWER_LOGIN_REJECTED"); }
}
