package io.tracepermit.adapters.remote.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PilotConfigurationTest {
  @TempDir Path root;
  private PilotTestFiles files;
  @BeforeEach void setup() throws Exception { files = new PilotTestFiles(root); }

  @Test void positiveOfflineCheckNeverConstructsProviderOrTransportAndWritesNothing() throws Exception {
    var invocations = new AtomicInteger();
    java.util.List<Path> before;
    try (var paths = Files.walk(root)) { before = paths.sorted().toList(); }
    var result = PilotOperatorMain.bounded(new String[]{"--check"}, files.config.toString(), config -> {
      invocations.incrementAndGet(); throw new AssertionError("OFFLINE_NETWORK_PATH_ENTERED");
    }, Duration.ofSeconds(5));
    assertThat(result).isEqualTo(PilotOperatorMain.Result.CHECK_VALID);
    assertThat(invocations).hasValue(0);
    try (var paths = Files.walk(root)) { assertThat(paths.sorted().toList()).isEqualTo(before); }
    var config = PilotConfiguration.read(files.config);
    assertThat(config.toString()).isEqualTo("PilotConfiguration[redacted]");
    assertThat(config.key()).isEqualTo(files.key);
    assertThat(config.installation().permissions()).containsExactlyInAnyOrderEntriesOf(
        io.tracepermit.adapters.remote.github.credentials.GitHubInstallationConfiguration.requiredPermissions());
  }

  @ParameterizedTest @ValueSource(strings = {"authenticated", "verified", "delegated", "approved", "origin", "proxy",
      "trustStore", "tlsBypass", "installationToken", "timeout", "execute", "policyHash"})
  void unknownAndAuthorityFieldsAreRejected(String field) throws Exception {
    files.json.put(field, PilotTestFiles.CANARY); files.write(); rejected();
  }

  @ParameterizedTest @ValueSource(strings = {"appId", "installationId", "repositoryId", "ownerId", "headSha", "baseSha",
      "schemaVersion", "permissions", "privateKeyFile", "draft", "repository", "head", "base"})
  void missingRequiredFieldsRejected(String field) throws Exception { files.json.remove(field); files.write(); rejected(); }

  @ParameterizedTest @ValueSource(strings = {"appId", "installationId", "repositoryId", "ownerId"})
  void idsCannotBeStringsOrInexact(String field) throws Exception {
    files.json.put(field, "11"); files.write(); rejected();
    files.json.put(field, 9_007_199_254_740_992L); files.write(); rejected();
    files.json.put(field, 0); files.write(); rejected();
    files.json.put(field, 11.0); files.write(); rejected();
  }

  @ParameterizedTest @ValueSource(strings = {"repository", "base", "head", "headSha", "baseSha", "schemaVersion"})
  void exactPinsAreNotNormalized(String field) throws Exception {
    files.json.put(field, field.equals("base") ? "development" : " MALFORMED "); files.write(); rejected();
  }

  @Test void draftAndLeastPermissionRestrictionsRemain() throws Exception {
    files.json.put("draft", false); files.write(); rejected(); files.json.put("draft", true);
    files.json.withObject("/permissions").put("contents", "write"); files.write(); rejected();
    files.json.withObject("/permissions").put("contents", "read").put("administration", "read"); files.write(); rejected();
    files.json.withObject("/permissions").remove("administration");
    files.json.withObject("/permissions").put("pull_requests", 1); files.write(); rejected();
    files.json.put("head", "main"); files.write(); rejected();
  }

  @ParameterizedTest @ValueSource(strings = {"", "[]", "null", "{", "{} true", "{}{}", "{\"schemaVersion\":1,\"schemaVersion\":2}",
      "{\"permissions\":{\"contents\":\"read\",\"contents\":\"write\"}}"})
  void strictJsonRejectsEmptyMalformedDuplicateAndTrailing(String input) throws Exception {
    PilotTestFiles.privateFile(files.config, input.getBytes(StandardCharsets.UTF_8)); rejected();
  }

  @Test void duplicatesAndTrailingOnOtherwiseValidConfigurationRejected() throws Exception {
    String original = Files.readString(files.config);
    for (String input : new String[]{original + " {}", original.replace("\"appId\":11", "\"appId\":11,\"appId\":11"),
        original.replace("\"contents\":\"read\"", "\"contents\":\"read\",\"contents\":\"read\"")}) {
      PilotTestFiles.privateFile(files.config, input.getBytes(StandardCharsets.UTF_8)); rejected();
    }
  }

  @Test void utf8AndEscapedSurrogatesAreStrict() throws Exception {
    byte[][] malformed = {{(byte) 0xc0, (byte) 0xaf}, {(byte) 0xed, (byte) 0xa0, (byte) 0x80}, {(byte) 0xff}};
    for (byte[] bytes : malformed) { PilotTestFiles.privateFile(files.config, bytes); rejected(); }
    for (String surrogate : new String[]{"\\ud800", "\\udfff", "\\ud800x"}) {
      String input = files.json.toString().replace("synthetic/topic", surrogate);
      PilotTestFiles.privateFile(files.config, input.getBytes(StandardCharsets.UTF_8)); rejected();
    }
  }

  @Test void configAndKeyBoundsRejectOversizedAndMalformedKey() throws Exception {
    PilotTestFiles.privateFile(files.config, new byte[16_385]); rejected(); files.write();
    PilotTestFiles.privateFile(files.key, new byte[16_385]); rejected();
    PilotTestFiles.privateFile(files.key, PilotTestFiles.CANARY.getBytes(StandardCharsets.UTF_8)); rejected();
    var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(1024);
    PilotTestFiles.privateFile(files.key, generator.generateKeyPair().getPrivate().getEncoded()); rejected();
    generator = KeyPairGenerator.getInstance("Ed25519");
    PilotTestFiles.privateFile(files.key, generator.generateKeyPair().getPrivate().getEncoded()); rejected();
  }

  @ParameterizedTest @ValueSource(strings = {"config", "key"})
  void privateRegularFilesNoLinksDirectoriesOrUnsafeModes(String selected) throws Exception {
    Path file = selected.equals("config") ? files.config : files.key;
    byte[] original = Files.readAllBytes(file);
    for (String mode : new String[]{"rw-r--r--", "rwx------", "-w-------", "rw-rw----"}) {
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(mode)); rejected();
    }
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--------"));
    assertThat(PilotConfiguration.read(files.config)).isNotNull();
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    Path moved = file.resolveSibling(selected + ".original"); Files.move(file, moved);
    Files.createSymbolicLink(file, moved); rejected(); Files.delete(file);
    Files.createLink(file, moved); rejected(); Files.delete(file); Files.delete(moved);
    Files.createDirectory(file); rejected(); Files.delete(file);
    PilotTestFiles.privateFile(file, original);
    Files.delete(file); rejected();
  }

  @Test void privateDirectoryAndEveryPathComponentAreChecked() throws Exception {
    Files.setPosixFilePermissions(files.directory, PosixFilePermissions.fromString("rwxr-x---")); rejected();
    Files.setPosixFilePermissions(files.directory, PosixFilePermissions.fromString("rwx------"));
    var link = root.resolve("linked-directory"); Files.createSymbolicLink(link, files.directory);
    assertThatThrownBy(() -> PilotConfiguration.read(link.resolve(files.config.getFileName()))).isInstanceOf(Exception.class);
    files.json.put("privateKeyFile", link.resolve(files.key.getFileName()).toString()); files.write(); rejected();
    files.json.put("privateKeyFile", files.key.getFileName().toString()); files.write(); rejected();
  }

  @ParameterizedTest @ValueSource(strings = {"--execute", "--run", "--reconcile", "", "--check=secret"})
  void unsupportedCommandsHaveFixedUsageResult(String mode) {
    assertThat(PilotOperatorMain.bounded(new String[]{mode}, files.config.toString(), ignored -> {
      throw new AssertionError("UNREACHABLE");
    }, Duration.ofSeconds(1))).isEqualTo(PilotOperatorMain.Result.USAGE_REJECTED);
  }

  private void rejected() {
    var result = PilotOperatorMain.bounded(new String[]{"--check"}, files.config.toString(), ignored -> {
      throw new AssertionError("INVALID_CONFIGURATION_REACHED_PROVIDER");
    }, Duration.ofSeconds(5));
    assertThat(result).isEqualTo(PilotOperatorMain.Result.CONFIGURATION_REJECTED);
    assertThat(result.diagnostic()).isEqualTo("CONFIGURATION_REJECTED_NON_AUTHORITATIVE");
  }
}
