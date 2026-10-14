package io.tracepermit.endpoint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class EndpointAdaptersTest {
  @TempDir Path temporaryDirectory;

  @Test
  void fileKeyProviderSignsWithoutReturningPrivateKeyMaterial() throws Exception {
    KeyPair keys = ed25519();
    Path path = writePrivateKey(keys, "endpoint-key.pk8");
    EndpointKeyProvider.KeyMetadata metadata = new EndpointKeyProvider.KeyMetadata(
        "tracepermit-endpoint:device-simulated-adapter",
        "endpoint-key-adapter",
        "device-simulated-adapter");
    FileEndpointKeyProvider provider = new FileEndpointKeyProvider(metadata, path);
    byte[] input = "canonical-signing-input".getBytes(StandardCharsets.UTF_8);

    byte[] signature = provider.sign(input);

    Signature verifier = Signature.getInstance("Ed25519");
    verifier.initVerify(keys.getPublic());
    verifier.update(input);
    assertThat(verifier.verify(signature)).isTrue();
    assertThat(provider.metadata()).isEqualTo(metadata);
    assertThat(provider.getClass().getMethods())
        .extracting(java.lang.reflect.Method::getName)
        .doesNotContain("privateKey", "getPrivateKey", "encodedKey");
  }

  @Test
  void fileKeyProviderRejectsInsecureInvalidAndLinkedKeyFiles() throws Exception {
    KeyPair keys = ed25519();
    Path insecure = temporaryDirectory.resolve("insecure.pk8");
    Files.write(insecure, keys.getPrivate().getEncoded());
    Files.setPosixFilePermissions(
        insecure,
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ));
    Path invalid = temporaryDirectory.resolve("invalid.pk8");
    Files.writeString(invalid, "not-a-private-key", StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(invalid, Set.of(PosixFilePermission.OWNER_READ));
    Path valid = writePrivateKey(keys, "valid.pk8");
    Path empty = temporaryDirectory.resolve("empty.pk8");
    Files.write(empty, new byte[0]);
    Files.setPosixFilePermissions(empty, Set.of(PosixFilePermission.OWNER_READ));
    Path link = temporaryDirectory.resolve("linked.pk8");
    Files.createSymbolicLink(link, valid.getFileName());
    EndpointKeyProvider.KeyMetadata metadata = new EndpointKeyProvider.KeyMetadata(
        "tracepermit-endpoint:device-simulated-adapter",
        "endpoint-key-adapter",
        "device-simulated-adapter");

    for (Path path : java.util.List.of(insecure, invalid, empty, link)) {
      assertThatThrownBy(() -> new FileEndpointKeyProvider(metadata, path))
          .isInstanceOf(ConnectorException.class)
          .extracting(error -> ((ConnectorException) error).code())
          .isEqualTo("ENDPOINT_KEY_UNAVAILABLE");
    }
  }

  @Test
  void processObserverCapturesAndRevalidatesIndependentRuntimeFacts() {
    ProcessHandleProcessObserver observer =
        new ProcessHandleProcessObserver("device-simulated-runtime", ignored -> false);

    ProcessObservation observation = observer.observe();

    assertThat(observation.deviceId()).isEqualTo("device-simulated-runtime");
    assertThat(observation.executablePath()).isAbsolute();
    assertThat(observation.executableHash()).matches("^sha256:[a-f0-9]{64}$");
    assertThat(observation.startedAt()).isBeforeOrEqualTo(Instant.now());
    assertThat(observation.terminalAttached()).isFalse();
    observer.verifyStable(observation);

    ProcessObservation changed = new ProcessObservation(
        observation.processId(),
        observation.deviceId(),
        observation.platform(),
        observation.assurance(),
        observation.executableName(),
        observation.executablePath(),
        "sha256:" + "0".repeat(64),
        observation.parentExecutableName(),
        observation.startedAt(),
        observation.terminalAttached());
    assertThatThrownBy(() -> observer.verifyStable(changed))
        .isInstanceOf(ConnectorException.class)
        .extracting(error -> ((ConnectorException) error).code())
        .isEqualTo("ENDPOINT_EXECUTABLE_CHANGED");
  }

  @Test
  void macOsTerminalClassificationIsBoundedAndAdapterIsPlatformGuarded() {
    assertThat(MacOsDevelopmentProcessObserver.ttyOutputIsInteractive("ttys001\n"))
        .isTrue();
    assertThat(MacOsDevelopmentProcessObserver.ttyOutputIsInteractive("??\n"))
        .isFalse();
    assertThat(MacOsDevelopmentProcessObserver.ttyOutputIsInteractive(""))
        .isFalse();
    assertThat(MacOsDevelopmentProcessObserver.terminalProbeCommand(123).environment())
        .isEmpty();
    assertThat(MacOsDevelopmentProcessObserver.hasTty(ProcessHandle.current().pid()))
        .isIn(true, false);
    assertThatThrownBy(() -> MacOsDevelopmentProcessObserver.hasTty(Long.MAX_VALUE))
        .isInstanceOf(ConnectorException.class)
        .extracting(error -> ((ConnectorException) error).code())
        .isEqualTo("ENDPOINT_OBSERVATION_UNAVAILABLE");
    if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac")) {
      assertThatThrownBy(
          () -> new MacOsDevelopmentProcessObserver("device-simulated-macos"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("macOS");
    }

    String originalOsName = System.getProperty("os.name");
    try {
      System.setProperty("os.name", "Mac OS X");
      MacOsDevelopmentProcessObserver observer =
          new MacOsDevelopmentProcessObserver("device-simulated-macos");
      ProcessObservation observation = observer.observe();
      assertThat(observation.platform()).isEqualTo("macos");
      observer.verifyStable(observation);
    } finally {
      if (originalOsName == null) System.clearProperty("os.name");
      else System.setProperty("os.name", originalOsName);
    }
  }

  @Test
  void environmentCredentialProviderAndValueObjectsRejectUnsafeConfiguration() {
    EnvironmentConnectorCredentialProvider provider =
        new EnvironmentConnectorCredentialProvider(
            Map.of(
                EnvironmentConnectorCredentialProvider.VARIABLE, "short-lived-token",
                "UNRELATED_SENTINEL_SECRET", "must-not-be-retained"));
    assertThat(provider.currentBearerToken()).isEqualTo("short-lived-token");
    assertThat(provider.getClass().getDeclaredFields())
        .extracting(java.lang.reflect.Field::getType)
        .doesNotContain(Map.class);
    assertThatThrownBy(
        () -> new EnvironmentConnectorCredentialProvider(Map.of()).currentBearerToken())
        .isInstanceOf(ConnectorException.class)
        .extracting(error -> ((ConnectorException) error).code())
        .isEqualTo("CONNECTOR_CREDENTIAL_UNAVAILABLE");

    assertThatThrownBy(
        () -> new EndpointKeyProvider.KeyMetadata("", "key", "device"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
        () -> new EndpointKeyProvider.KeyMetadata(null, "key", "device"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
        () -> new EndpointKeyProvider.KeyMetadata("issuer", "k".repeat(129), "device"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
        () -> new ConnectorException("bad-code", "safe"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
        () -> new ConnectorException(null, "safe"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
        () -> new ProcessObservation(
            0,
            "device",
            "linux",
            "development",
            "codex",
            Path.of("/simulated/codex"),
            "sha256:" + "1".repeat(64),
            "terminal",
            Instant.EPOCH,
            true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
        () -> observationWith("unsupported", "development", "sha256:" + "1".repeat(64), "codex"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
        () -> observationWith("linux", "production", "sha256:" + "1".repeat(64), "codex"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
        () -> observationWith("linux", "development", "not-a-fingerprint", "codex"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
        () -> observationWith("linux", "development", "sha256:" + "1".repeat(64), " "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
        () -> observationWith("linux", "development", "sha256:" + "1".repeat(64), null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
        () -> observationWith(
            "linux", "development", "sha256:" + "1".repeat(64), "x".repeat(129)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void mainCompositionUsesNoStdoutForStartupAndSanitizesConfigurationErrors()
      throws Exception {
    ByteArrayOutputStream invalidOut = new ByteArrayOutputStream();
    ByteArrayOutputStream invalidErr = new ByteArrayOutputStream();
    int invalid = EndpointConnectorMain.run(
        Map.of("TRACEPERMIT_CONNECTOR_BEARER_TOKEN", "sentinel-secret-value"),
        new ByteArrayInputStream(new byte[0]),
        invalidOut,
        new PrintStream(invalidErr, true, StandardCharsets.UTF_8));
    assertThat(invalid).isEqualTo(2);
    assertThat(invalidOut.size()).isZero();
    assertThat(invalidErr.toString(StandardCharsets.UTF_8))
        .contains("CONFIGURATION_INVALID")
        .doesNotContain("sentinel-secret-value");

    KeyPair keys = ed25519();
    Path keyPath = writePrivateKey(keys, "main-key.pk8");
    Map<String, String> environment = Map.of(
        "TRACEPERMIT_ENDPOINT_ISSUER", "tracepermit-endpoint:device-simulated-main",
        "TRACEPERMIT_ENDPOINT_KEY_ID", "endpoint-key-main",
        "TRACEPERMIT_ENDPOINT_DEVICE_ID", "device-simulated-main",
        "TRACEPERMIT_ENDPOINT_PRIVATE_KEY_PKCS8_PATH", keyPath.toString(),
        "TRACEPERMIT_GATEWAY_URL", "http://127.0.0.1:65534/mcp",
        "TRACEPERMIT_CONNECTOR_BEARER_TOKEN", "sentinel-main-token");
    ByteArrayOutputStream validOut = new ByteArrayOutputStream();
    ByteArrayOutputStream validErr = new ByteArrayOutputStream();
    int valid = EndpointConnectorMain.run(
        environment,
        new ByteArrayInputStream(new byte[0]),
        validOut,
        new PrintStream(validErr, true, StandardCharsets.UTF_8));
    assertThat(valid).isZero();
    assertThat(validOut.size()).isZero();
    assertThat(validErr.size()).isZero();
  }

  private Path writePrivateKey(KeyPair keys, String name) throws Exception {
    Path path = temporaryDirectory.resolve(name);
    Files.write(path, keys.getPrivate().getEncoded());
    Files.setPosixFilePermissions(
        path,
        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    return path;
  }

  private static ProcessObservation observationWith(
      String platform, String assurance, String hash, String executableName) {
    return new ProcessObservation(
        1,
        "device-simulated-validation",
        platform,
        assurance,
        executableName,
        Path.of("/simulated/codex"),
        hash,
        "terminal",
        Instant.EPOCH,
        false);
  }

  private static KeyPair ed25519() {
    try {
      return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
