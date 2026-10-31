package io.tracepermit.auth0reviewer;

import static org.assertj.core.api.Assertions.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class ReviewerLoginTest {
  @Test void bindsS256StateCodeClientAndRedirectWithoutClientSecretOrOpenid() throws Exception {
    var authorize = new AtomicReference<Map<String,String>>();
    var exchanges = new AtomicInteger();
    var result = new ReviewerLogin(ReviewerFixtures.config(), (state, uri) -> {
      Map<String,String> values = new HashMap<>();
      for (String field : uri.getRawQuery().split("&")) {
        String[] pair = field.split("=", 2);
        values.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
      }
      assertThat(uri.getHost()).isEqualTo("synthetic.au.auth0.com");
      assertThat(uri.getPath()).isEqualTo("/authorize");
      assertThat(values.get("state")).isEqualTo(state).hasSize(43);
      assertThat(values).containsEntry("scope", "operations.decide").containsEntry("code_challenge_method", "S256");
      assertThat(values).doesNotContainKeys("client_secret", "organization", "nonce");
      authorize.set(values); return "synthetic-code";
    }, form -> {
      exchanges.incrementAndGet();
      assertThat(form.keySet()).containsExactlyInAnyOrder("grant_type", "client_id", "redirect_uri", "code", "code_verifier");
      assertThat(form).containsEntry("code", "synthetic-code").containsEntry("client_id", "synthetic-reviewer")
          .containsEntry("redirect_uri", ReviewerConfiguration.CALLBACK.toString());
      try {
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
            .digest(form.get("code_verifier").getBytes(StandardCharsets.US_ASCII)));
        assertThat(challenge).isEqualTo(authorize.get().get("code_challenge"));
      } catch (Exception failure) { throw new AssertionError(failure); }
      return ReviewerFixtures.response(c -> {});
    }, new SecureRandom(), ReviewerFixtures.CLOCK).login();
    assertThat(exchanges).hasValue(1);
    assertThat(result.mappedRole()).contains("REVIEWER");
    assertThat(result.subjectId()).isEqualTo(ReviewerFixtures.ISSUER + "#auth0|synthetic-human");
    assertThat(result.toString()).doesNotContain("synthetic-code");
  }

  @Test void callbackRejectionPrecedesExchangeAndNeverRetries() {
    var exchanges = new AtomicInteger();
    assertThatThrownBy(() -> new ReviewerLogin(ReviewerFixtures.config(), (state, uri) -> {
      throw new IllegalStateException("private-code-canary");
    }, form -> { exchanges.incrementAndGet(); return ""; }, new SecureRandom(), ReviewerFixtures.CLOCK).login())
        .hasMessage("REVIEWER_LOGIN_REJECTED").hasNoCause();
    assertThat(exchanges).hasValue(0);
  }

  @ParameterizedTest @ValueSource(strings = {"identity", "tenant", "client", "role", "scope", "expired", "jti"})
  void signedWrongIdentityNeverQualifies(String changed) {
    var exchanges = new AtomicInteger();
    assertThatThrownBy(() -> new ReviewerLogin(ReviewerFixtures.config(), (state, uri) -> "code", form -> {
      exchanges.incrementAndGet();
      return ReviewerFixtures.response(c -> {
        switch (changed) {
          case "identity" -> c.put("sub", "auth0|other-human");
          case "tenant" -> c.put(ReviewerConfiguration.TENANT_CLAIM, "other");
          case "client" -> c.put("client_id", "other");
          case "role" -> c.putArray(ReviewerConfiguration.ROLE_CLAIM).add("REQUESTER");
          case "scope" -> c.put("scope", "operations.execute");
          case "expired" -> c.put("exp", 1);
          case "jti" -> c.remove("jti");
          default -> throw new AssertionError();
        }
      });
    }, new SecureRandom(), ReviewerFixtures.CLOCK).login()).hasMessage("REVIEWER_LOGIN_REJECTED").hasNoCause();
    assertThat(exchanges).hasValue(1);
  }

  @ParameterizedTest @ValueSource(strings = {"refresh_token", "id_token", "duplicate", "invalid_json", "oversize", "token_type", "expires", "scope"})
  void tokenResponseCannotBroadenOrPersistCredentials(String changed) {
    assertThatThrownBy(() -> new ReviewerLogin(ReviewerFixtures.config(), (state, uri) -> "code", form -> {
      String response = ReviewerFixtures.response(c -> {});
      if (changed.equals("invalid_json")) return "[]";
      if (changed.equals("oversize")) return "x".repeat(65537);
      if (changed.equals("duplicate")) return response.replace("\"expires_in\":300", "\"expires_in\":300,\"expires_in\":300");
      try {
        var n = (com.fasterxml.jackson.databind.node.ObjectNode) ReviewerConfiguration.JSON.readTree(response);
        if (changed.equals("expires")) n.put("expires_in", 301);
        else if (changed.equals("token_type")) n.put("token_type", "Basic");
        else if (changed.equals("scope")) n.put("scope", "operations.execute");
        else n.put(changed, "private-canary");
        return n.toString();
      } catch (Exception failure) { throw new AssertionError(failure); }
    }, new SecureRandom(), ReviewerFixtures.CLOCK).login()).hasMessage("REVIEWER_LOGIN_REJECTED").hasNoCause();
  }
}
