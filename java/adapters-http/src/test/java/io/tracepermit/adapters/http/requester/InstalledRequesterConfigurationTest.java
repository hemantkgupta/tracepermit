package io.tracepermit.adapters.http.requester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class InstalledRequesterConfigurationTest {
  @TempDir Path directory;

  @Test void exactPrivateConfigurationAndOfflineCheckPassWithoutCreatingCredential()
      throws Exception {
    var fixture = InstalledRequesterTestSupport.fixture(directory);
    var configuration = fixture.load();
    assertThat(configuration.callbackUri().toString())
        .isEqualTo("http://127.0.0.1:41837/requester/callback");
    assertThat(configuration.qualificationUri().toString())
        .isEqualTo("http://127.0.0.1:8085/api/v1/requester-qualification");

    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ByteArrayOutputStream error = new ByteArrayOutputStream();
    int result = InstalledRequesterAuthMain.execute(new String[] {"--check"},
        Map.of("TRACEPERMIT_REQUESTER_CONFIG", fixture.config().toString()),
        new PrintStream(output), new PrintStream(error), InstalledRequesterTestSupport.CLOCK);
    assertThat(result).isZero();
    assertThat(output.toString(StandardCharsets.UTF_8))
        .contains("REQUESTER_CONFIGURATION_VALID").contains("offline only");
    assertThat(error.toString(StandardCharsets.UTF_8)).isEmpty();
    assertThat(fixture.credential()).doesNotExist();
  }

  @Test void unknownDuplicateTrailingAndSecretFieldsFailClosed() throws Exception {
    var fixture = InstalledRequesterTestSupport.fixture(directory);
    rejected(fixture, fixture.json().replace("\"profile\":",
        "\"unknown\": true,\n  \"profile\":"));
    rejected(fixture, fixture.json().replace("\"profile\":",
        "\"schemaVersion\": \"1.0\",\n  \"profile\":"));
    rejected(fixture, fixture.json() + " trailing");
    rejected(fixture, fixture.json().replace("\"clientId\":",
        "\"clientSecret\": \"forbidden\",\n    \"clientId\":"));
  }

  @Test void nonNumericOrNonLoopbackUrisAndEndpointDriftFailOffline() throws Exception {
    var fixture = InstalledRequesterTestSupport.fixture(directory);
    rejected(fixture, fixture.json().replace("127.0.0.1:41837", "localhost:41837"));
    rejected(fixture, fixture.json().replace("127.0.0.1:41837", "[::1]:41837"));
    rejected(fixture, fixture.json().replace("127.0.0.1:41837", "192.0.2.8:41837"));
    rejected(fixture, fixture.json().replace("/requester/callback", "/callback"));
    rejected(fixture, fixture.json().replace(
        "https://idp.example.invalid/oauth/token", "https://other.example.invalid/oauth/token"));
    rejected(fixture, fixture.json().replace("/mcp\"", "/other\""));
  }

  @Test void permissionsLinksAndRelativePathsFailClosed() throws Exception {
    var fixture = InstalledRequesterTestSupport.fixture(directory);
    Files.setPosixFilePermissions(fixture.config(), PosixFilePermissions.fromString("rw-r--r--"));
    assertInvalid(fixture.config());

    fixture.writeConfig(fixture.json());
    Path linked = directory.resolve("linked-config.json");
    Files.createLink(linked, fixture.config());
    assertInvalid(fixture.config());
    Files.delete(linked);

    rejected(fixture, fixture.json().replace(
        fixture.credential().toString(), "relative/credential.json"));
    rejected(fixture, fixture.json().replace(
        fixture.executable().toString(), "relative/codex"));
  }

  private static void rejected(InstalledRequesterTestSupport.Fixture fixture, String json)
      throws Exception {
    fixture.writeConfig(json);
    assertInvalid(fixture.config());
  }

  private static void assertInvalid(Path config) {
    assertThatThrownBy(() -> InstalledRequesterConfiguration.load(config))
        .isInstanceOfSatisfying(InstalledRequesterFailure.class,
            failure -> assertThat(failure.code()).isEqualTo("REQUESTER_CONFIGURATION_INVALID"));
  }
}
