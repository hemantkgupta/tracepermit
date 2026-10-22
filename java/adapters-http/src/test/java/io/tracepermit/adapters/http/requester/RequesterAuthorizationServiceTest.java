package io.tracepermit.adapters.http.requester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RequesterAuthorizationServiceTest {
  @TempDir Path directory;
  private RequesterAuthorizationService service;

  @BeforeEach void prepare() throws Exception {
    var configuration = InstalledRequesterTestSupport.fixture(directory).load();
    service = new RequesterAuthorizationService(configuration, null, null, null, null, null,
        null, new SecureRandom(), InstalledRequesterTestSupport.CLOCK);
  }

  @Test void authorizationRequestIsExactPublicClientS256Profile() {
    URI uri = service.authorizationUri("fixed-state", "fixed-nonce", "fixed-challenge");
    assertThat(uri.getScheme()).isEqualTo("https");
    assertThat(uri.getHost()).isEqualTo("idp.example.invalid");
    assertThat(uri.getPath()).isEqualTo("/authorize");
    assertThat(query(uri)).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
        Map.entry("response_type", "code"),
        Map.entry("client_id", "native-client.example.invalid"),
        Map.entry("redirect_uri", "http://127.0.0.1:41837/requester/callback"),
        Map.entry("scope", "openid approvals.read approvals.request approvals.execute"),
        Map.entry("audience", "https://approval-api.example.invalid"),
        Map.entry("organization", "org_exampleinvalid"),
        Map.entry("state", "fixed-state"),
        Map.entry("nonce", "fixed-nonce"),
        Map.entry("code_challenge", "fixed-challenge"),
        Map.entry("code_challenge_method", "S256")));
    assertThat(uri.toString()).doesNotContain(
        "client_secret", "refresh_token", "offline_access", "device_code", "password");
  }

  @Test void exactTokenResponseIsAcceptedWhileUnknownOrRefreshMaterialFailsClosed() {
    String valid = "{\"access_token\":\"aaa.bbb.ccc\",\"id_token\":\"ddd.eee.fff\","
        + "\"token_type\":\"Bearer\",\"expires_in\":600,"
        + "\"scope\":\"openid approvals.read approvals.request approvals.execute\"}";
    var tokens = service.tokens(valid);
    assertThat(tokens.accessToken).isEqualTo("aaa.bbb.ccc");
    assertThat(tokens.idToken).isEqualTo("ddd.eee.fff");

    rejected(valid.replace("\"expires_in\":600", "\"refresh_token\":\"forbidden\""));
    rejected(valid.replace("\"token_type\":\"Bearer\"", "\"token_type\":\"DPoP\""));
    rejected(valid.replace("approvals.execute", "approvals.decide"));
    rejected(valid + " trailing");
    rejected("{}");
    rejected("x".repeat(65_537));
  }

  private void rejected(String body) {
    assertThatThrownBy(() -> service.tokens(body)).isInstanceOfSatisfying(
        InstalledRequesterFailure.class,
        failure -> assertThat(failure.code()).isEqualTo("PROVIDER_PROFILE_UNSUPPORTED"));
  }

  private static Map<String, String> query(URI uri) {
    return Arrays.stream(uri.getRawQuery().split("&"))
        .map(pair -> pair.split("=", 2))
        .collect(Collectors.toMap(pair -> decode(pair[0]), pair -> decode(pair[1])));
  }

  private static String decode(String value) {
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }
}
