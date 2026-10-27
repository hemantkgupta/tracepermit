package io.tracepermit.adapters.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class OktaApprovalConfigurationTest {
  @TempDir Path directory;
  SimulatorApprovalFixture fixture;

  @BeforeEach void setup() throws Exception {
    fixture = new SimulatorApprovalFixture(directory);
    fixture.oktaBrowser();
  }

  @Test void explicitPublicProfileIsOfflineAndContainsNoClientSecret() throws Exception {
    var config = fixture.load();
    assertThat(config.provider().tokenProfile().isOkta()).isTrue();
    assertThat(config.browser().tokenEndpointAuthMethod).isEqualTo("none");
    assertThat(config.browser().clientSecret).isNull();
    assertThat(config.browser().organization).isEqualTo("pilot-synthetic");
    assertThat(config.provider().tokenProfile().toString()).doesNotContain("pilot-requesters");
  }

  @ParameterizedTest
  @ValueSource(strings = {"profile", "missing_profile", "old_version", "future_version", "missing_browser",
      "issuer_org", "issuer_slash", "issuer_encoded", "authorization", "token", "jwks", "secret",
      "method", "redirect_override", "missing_groups", "empty_mapping", "role", "missing_reviewer",
      "tenant_claim", "group_claim", "same_claim", "group_null", "group_control", "extra", "bind", "audience_client"})
  void malformedAmbiguousAndUnqualifiedConfigurationIsRejected(String change) throws Exception {
    var oidc = fixture.json.withObject("/oidc");
    var browser = fixture.json.withObject("/browser");
    switch (change) {
      case "profile" -> oidc.put("profile", "automatic");
      case "missing_profile" -> oidc.remove("profile");
      case "old_version" -> fixture.json.put("schemaVersion", "1.1");
      case "future_version" -> fixture.json.put("schemaVersion", "9.0");
      case "missing_browser" -> fixture.json.remove("browser");
      case "issuer_org" -> oidc.put("issuer", "https://idp.example.invalid");
      case "issuer_slash" -> oidc.put("issuer", oidc.path("issuer").asText() + "/");
      case "issuer_encoded" -> oidc.put("issuer", "https://idp.example.invalid/oauth2/%73ynthetic");
      case "authorization" -> browser.put("authorizationUri", "https://other.example.invalid/v1/authorize");
      case "token" -> browser.put("tokenUri", oidc.path("issuer").asText() + "/v1/token?override=true");
      case "jwks" -> oidc.put("jwksUri", "https://other.example.invalid/keys");
      case "secret" -> browser.put("clientSecretFile", "not-read.secret");
      case "method" -> browser.put("tokenEndpointAuthMethod", "client_secret_basic");
      case "redirect_override" -> browser.put("redirectUri", "http://0.0.0.0/auth/callback");
      case "missing_groups" -> oidc.remove("groupsClaim");
      case "empty_mapping" -> oidc.putObject("groupRoleMappings");
      case "role" -> oidc.withObject("/groupRoleMappings").put("pilot-reviewers", "ADMIN");
      case "missing_reviewer" -> oidc.withObject("/groupRoleMappings").remove("pilot-reviewers");
      case "tenant_claim" -> oidc.put("tenantClaim", "iss");
      case "group_claim" -> oidc.put("groupsClaim", "roles");
      case "same_claim" -> oidc.put("groupsClaim", "tracepermit_tenant");
      case "group_null" -> oidc.withObject("/groupRoleMappings").putNull("pilot-reviewers");
      case "group_control" -> oidc.withObject("/groupRoleMappings").put("bad\ngroup", "VIEWER");
      case "extra" -> oidc.put("authenticated", true);
      case "bind" -> fixture.json.put("host", "0.0.0.0");
      case "audience_client" -> oidc.put("clientId", oidc.path("audience").asText());
      default -> throw new AssertionError();
    }
    fixture.save();
    assertThatThrownBy(() -> SimulatorApprovalConfiguration.load(fixture.config))
        .isExactlyInstanceOf(IllegalArgumentException.class)
        .hasMessage("INVALID_APPROVAL_CONFIGURATION").hasNoCause();
  }
}
