package io.tracepermit.auth0reviewer;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class ReviewerConfigurationTest {
  @Test void offlineConfigPinsPublicTrustAndFixedLoopbackOnly() {
    var config = ReviewerFixtures.config();
    assertThat(config.provider().tokenProfile().authenticationProfile()).isEqualTo("auth0-rfc9068-rs256-v1");
    assertThat(config.toString()).isEqualTo("ReviewerConfiguration[redacted]");
    assertThat(ReviewerConfiguration.CALLBACK.toString()).isEqualTo("http://127.0.0.1:41839/reviewer/callback");
  }
  @ParameterizedTest @ValueSource(strings = {"missing", "extra", "issuer", "issuer-path", "callback", "client",
      "subject", "tenant", "keyId", "publicKeySpki", "management", "schemaVersion", "typed", "duplicate", "trailing"})
  void refusesInvalidTrustBeforeAnyEffects(String changed) {
    var n = ReviewerFixtures.json();
    switch (changed) {
      case "missing" -> n.remove("clientId");
      case "extra" -> n.put("authenticated", true);
      case "issuer" -> n.put("issuer", "http://synthetic.au.auth0.com/");
      case "issuer-path" -> n.put("issuer", ReviewerFixtures.ISSUER + "other/");
      case "callback" -> n.put("callbackUri", "http://localhost:41839/reviewer/callback");
      case "client" -> n.put("clientId", "bad client");
      case "subject" -> n.put("expectedSubject", "machine@clients");
      case "tenant" -> n.put("tenant", " ");
      case "keyId" -> n.put("keyId", "bad key");
      case "publicKeySpki" -> n.put("publicKeySpki", "not-a-key");
      case "management" -> n.put("audience", ReviewerFixtures.ISSUER + "api/v2/");
      case "schemaVersion" -> n.put("schemaVersion", "2.0");
      case "typed" -> n.put("clientId", 123);
      default -> { }
    }
    String text = n.toString();
    if (changed.equals("duplicate")) text = text.replace("\"clientId\":", "\"clientId\":\"duplicate\",\"clientId\":");
    if (changed.equals("trailing")) text += " {}";
    byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    assertThatThrownBy(() -> ReviewerConfiguration.parse(bytes)).hasMessage("REVIEWER_CONFIG_INVALID").hasNoCause();
  }
}
