package io.tracepermit.adapters.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.tracepermit.execution.TenantPartition;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;

/** Actual bearer verification and membership resolution; all effects remain simulated. */
final class InstalledRequesterQualificationTest {
  private static PostgreSQLContainer<?> postgres;
  private static HttpClient client;
  @TempDir Path directory;
  private SimulatorApprovalBrowserFixture fixture;

  @BeforeAll static void prerequisites() {
    postgres = new PostgreSQLContainer<>("postgres:16.4-alpine")
        .withPassword(java.util.UUID.randomUUID().toString());
    postgres.start();
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  }

  @AfterAll static void finish() {
    if (client != null) client.close();
    if (postgres != null) postgres.stop();
  }

  @BeforeEach void start() throws Exception {
    fixture = new SimulatorApprovalBrowserFixture(directory, postgres, true);
  }

  @AfterEach void stop() {
    if (fixture != null) fixture.close();
  }

  @Test void exactRequesterOnlyProfileReturnsNoContentAndMutatesNothing() throws Exception {
    var response = call("GET", "", fixture.accessToken("alice"), null);
    assertThat(response.statusCode()).isEqualTo(204);
    assertThat(response.body()).isEmpty();
    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    noAuthority();

    fixture.accessClaims = claims -> claims.put(
        "scope", "openid approvals.read approvals.request approvals.execute");
    assertThat(call("GET", "", fixture.accessToken("alice"), null).statusCode()).isEqualTo(204);
    noAuthority();
  }

  @ParameterizedTest
  @ValueSource(strings = {"bob", "carol", "dave", "robot", "outsider"})
  void wrongOrMissingEnrollmentNeverQualifies(String subject) throws Exception {
    var response = call("GET", "", fixture.accessToken(subject), null);
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(response.body()).contains("REQUESTER_ENROLLMENT_REQUIRED");
    noAuthority();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "approvals.read approvals.request",
      "approvals.read approvals.execute",
      "approvals.request approvals.execute",
      "approvals.read approvals.request approvals.execute approvals.decide",
      "approvals.read approvals.request approvals.execute approvals.cancel"
  })
  void missingOrAdditionalApiAuthorityNeverQualifies(String scopes) throws Exception {
    fixture.accessClaims = claims -> claims.put("scope", scopes);
    var response = call("GET", "", fixture.accessToken("alice"), null);
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(response.body()).contains("REQUESTER_ENROLLMENT_REQUIRED");
    noAuthority();
  }

  @Test void nearExpiryAndTransportSubstitutionFailBeforeAnyMutation() throws Exception {
    fixture.accessClaims = claims -> claims.put("exp", fixture.clock.instant().plusSeconds(59).getEpochSecond());
    assertThat(call("GET", "", fixture.accessToken("alice"), null).statusCode()).isEqualTo(403);

    fixture.accessClaims = claims -> {};
    String token = fixture.accessToken("alice");
    assertThat(call("GET", "?probe=1", token, null).statusCode()).isEqualTo(400);
    assertThat(call("POST", "", token, "{}").statusCode()).isEqualTo(405);
    assertThat(call("GET", "", token, null, "Cookie", "unsafe=value").statusCode()).isEqualTo(403);
    assertThat(call("GET", "", token, null, "Origin", fixture.origin()).statusCode()).isEqualTo(403);
    assertThat(call("GET", "", token, null,
        "tracepermit-source-attestation", "untrusted").statusCode()).isEqualTo(400);
    noAuthority();
  }

  private HttpResponse<String> call(String method, String suffix, String bearer, String body,
      String... headers) throws Exception {
    var request = HttpRequest.newBuilder(URI.create(
        fixture.origin() + "/api/v1/requester-qualification" + suffix))
        .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + bearer);
    for (int i = 0; i < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
    request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofString(body));
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private void noAuthority() {
    assertThat(fixture.state.readEvidence(new TenantPartition("simulated-alpha"))).isEmpty();
    assertThat(fixture.simulator.snapshot().path("aws").path("securityGroups")
        .path("sg-demo").size()).isZero();
  }
}
