package io.tracepermit.adapters.remote.github.credentials;

import static io.tracepermit.adapters.remote.github.credentials.GitHubInstallationCredentialProviderTest.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

final class CredentialBoundaryTest {
  private static KeyPair runtimeKeys;
  private static final String SENTINEL = "synthetic-private-boundary-canary";
  @TempDir Path directory;
  @BeforeAll static void generateKey() throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); runtimeKeys = generator.generateKeyPair();
  }
  private static GitHubInstallationCredentialProvider provider(AppSigningKeySource key, CredentialExchange.Factory exchange) {
    return new GitHubInstallationCredentialProvider(CONFIG, key, exchange, Clock.fixed(NOW, ZoneOffset.UTC));
  }
  private static CredentialExchange successful(Runnable close) {
    return new CredentialExchange() {
      @Override public Reply installation(String jwt) { return json(200, GitHubInstallationCredentialProviderTest.installation().toString()); }
      @Override public Reply token(String jwt, com.fasterxml.jackson.databind.JsonNode restrictions) {
        return json(201, GitHubInstallationCredentialProviderTest.token().toString());
      }
      @Override public void close() { close.run(); }
    };
  }
  private static CredentialExchange.Reply json(int status, String text) {
    return new CredentialExchange.Reply(status, false, text.getBytes(StandardCharsets.UTF_8));
  }
  private static Throwable secretFailure(String kind) {
    Throwable problem = switch (kind) {
      case "error" -> new AssertionError(SENTINEL);
      case "runtime" -> new IllegalStateException(SENTINEL);
      default -> new IOException(SENTINEL);
    };
    problem.initCause(new IOException(SENTINEL)); problem.addSuppressed(new IOException(SENTINEL)); return problem;
  }
  @SuppressWarnings("unchecked") private static <T extends Throwable> void throwAny(Throwable problem) throws T { throw (T) problem; }
  private static GitHubCredentialException fixed(Throwable problem, String code) {
    assertThat(problem instanceof GitHubCredentialException).isTrue();
    var failure = (GitHubCredentialException) problem;
    assertThat(failure.errorCode()).isEqualTo(code);
    assertThat(failure.getCause() == null && failure.getSuppressed().length == 0).isTrue();
    var output = new StringWriter(); failure.printStackTrace(new PrintWriter(output));
    assertThat(output.toString().contains(SENTINEL) || output.toString().contains(TOKEN)).isFalse();
    failure.addSuppressed(secretFailure("checked"));
    assertThat(failure.getSuppressed().length).isZero();
    Throwable mutation = catchThrowable(() -> failure.initCause(secretFailure("checked")));
    assertThat(mutation instanceof IllegalStateException && !mutation.toString().contains(SENTINEL)).isTrue();
    return failure;
  }

  @ParameterizedTest @ValueSource(strings = {"checked", "runtime", "error"})
  void keySourceFailuresCannotExposeNestedOrSuppressedPrivateMaterial(String kind) {
    var opened = new AtomicBoolean();
    var provider = provider(() -> { throwAny(secretFailure(kind)); return null; }, () -> {
      opened.set(true); return successful(() -> {});
    });
    fixed(catchThrowable(() -> provider.getInstallationCredentials(REPOSITORY)), "KEY_SOURCE_REJECTED");
    assertThat(opened).isFalse();
  }

  static Stream<Arguments> callbackFailures() {
    return Stream.of("open", "installation", "token", "cleanup").flatMap(stage ->
        Stream.of("checked", "runtime", "error").map(kind -> Arguments.of(stage, kind)));
  }
  @ParameterizedTest @MethodSource("callbackFailures")
  void transportAndCleanupBoundariesAlwaysReturnFixedExceptions(String stage, String kind) {
    var closed = new AtomicBoolean();
    var provider = provider(() -> runtimeKeys.getPrivate(), () -> {
      if (stage.equals("open")) throwAny(secretFailure(kind));
      return new CredentialExchange() {
        @Override public Reply installation(String jwt) {
          if (stage.equals("installation")) throwAny(secretFailure(kind));
          return json(200, GitHubInstallationCredentialProviderTest.installation().toString());
        }
        @Override public Reply token(String jwt, com.fasterxml.jackson.databind.JsonNode restrictions) {
          if (stage.equals("token")) throwAny(secretFailure(kind));
          return json(201, GitHubInstallationCredentialProviderTest.token().toString());
        }
        @Override public void close() { closed.set(true); if (stage.equals("cleanup")) throwAny(secretFailure(kind)); }
      };
    });
    fixed(catchThrowable(() -> provider.getInstallationCredentials(REPOSITORY)), stage.equals("cleanup") ? "CLEANUP_FAILED" : "TRANSPORT_UNAVAILABLE");
    assertThat(closed.get()).isEqualTo(!stage.equals("open"));
  }

  @Test void signingFailureIsContainedWithoutExposingThePrivateKey() {
    PrivateKey failing = new RSAPrivateKey() {
      private static final long serialVersionUID = 1L;
      @Override public BigInteger getModulus() { return ((RSAPrivateKey) runtimeKeys.getPrivate()).getModulus(); }
      @Override public BigInteger getPrivateExponent() { throwAny(secretFailure("checked")); return BigInteger.ZERO; }
      @Override public String getAlgorithm() { return "RSA"; }
      @Override public String getFormat() { return "PKCS#8"; }
      @Override public byte[] getEncoded() { return null; }
    };
    var provider = provider(() -> failing, () -> successful(() -> {}));
    fixed(catchThrowable(() -> provider.getInstallationCredentials(REPOSITORY)), "SIGNING_REJECTED");
  }

  @ParameterizedTest @ValueSource(ints = {1024, 4097})
  void unsupportedSigningStrengthFailsBeforeAnyExchange(int bits) {
    PrivateKey key = new RSAPrivateKey() {
      private static final long serialVersionUID = 1L;
      @Override public BigInteger getModulus() { return BigInteger.ONE.shiftLeft(bits - 1); }
      @Override public BigInteger getPrivateExponent() { return BigInteger.ONE; }
      @Override public String getAlgorithm() { return "RSA"; }
      @Override public String getFormat() { return "PKCS#8"; }
      @Override public byte[] getEncoded() { return null; }
    };
    var opened = new AtomicBoolean();
    var provider = provider(() -> key, () -> { opened.set(true); return successful(() -> {}); });
    fixed(catchThrowable(() -> provider.getInstallationCredentials(REPOSITORY)), "KEY_SOURCE_REJECTED");
    assertThat(opened).isFalse();
  }

  @Test void expiredAtCleanupCannotReturnAnAlreadyExpiredCredential() {
    var instant = new java.util.concurrent.atomic.AtomicReference<>(NOW);
    var clock = new Clock() {
      @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
      @Override public Clock withZone(java.time.ZoneId zone) { return this; }
      @Override public java.time.Instant instant() { return instant.get(); }
    };
    var provider = new GitHubInstallationCredentialProvider(CONFIG, () -> runtimeKeys.getPrivate(),
        () -> successful(() -> instant.set(NOW.plusSeconds(3600))), clock);
    fixed(catchThrowable(() -> provider.getInstallationCredentials(REPOSITORY)), "TOKEN_EXPIRY_REJECTED");
  }

  @Test void directInterruptedKeyReadRetainsTheCallersInterrupt() {
    var provider = provider(() -> { throw new InterruptedException(SENTINEL); }, () -> successful(() -> {}));
    try {
      fixed(catchThrowable(() -> provider.getInstallationCredentials(REPOSITORY)), "KEY_SOURCE_REJECTED");
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally { Thread.interrupted(); }
  }

  @ParameterizedTest @ValueSource(strings = {"missing", "public", "empty", "oversize", "invalid", "symlink"})
  void explicitPrivateKeyFileRejectsUnsafeOrInvalidSources(String kind) throws Exception {
    Path file = directory.resolve("operator-key.der");
    if (!kind.equals("missing")) {
      Files.write(file, kind.equals("empty") ? new byte[0] : kind.equals("oversize") ? new byte[16_385]
          : kind.equals("invalid") ? new byte[] {1, 2, 3} : runtimeKeys.getPrivate().getEncoded());
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(kind.equals("public") ? "rw-r--r--" : "rw-------"));
    }
    if (kind.equals("symlink")) {
      Path link = directory.resolve("operator-link.der"); Files.createSymbolicLink(link, file); file = link;
    }
    var source = new PrivateAppKeyFile(file.toAbsolutePath());
    fixed(catchThrowable(source::read), "KEY_SOURCE_REJECTED");
    assertThat(source.toString().contains(file.toString())).isFalse();
  }

  @Test void privateRuntimeGeneratedKeyLoadsAndSignsWithoutWritingAnyCopy() throws Exception {
    Path file = directory.resolve("operator-key.der"); Files.write(file, runtimeKeys.getPrivate().getEncoded());
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--------"));
    long before = Files.size(file);
    var source = new PrivateAppKeyFile(file.toAbsolutePath());
    var key = (RSAPrivateKey) source.read();
    assertThat(key.getModulus().equals(((RSAPrivateKey) runtimeKeys.getPrivate()).getModulus())).isTrue();
    assertThat(provider(source, () -> successful(() -> {})).getInstallationCredentials(REPOSITORY).token().equals(TOKEN)).isTrue();
    assertThat(Files.size(file)).isEqualTo(before);
    try (var files = Files.list(directory)) { assertThat(files.count()).isEqualTo(1); }
    assertThat(GitHubInstallationCredentialProvider.production(CONFIG, file.toAbsolutePath()).toString().contains(file.toString())).isFalse();
  }

  static Stream<Arguments> unsafeIds() {
    return Stream.of("app", "installation", "repository", "owner").flatMap(field ->
        Stream.of(0L, -1L, 9_007_199_254_740_992L).map(id -> Arguments.of(field, id)));
  }
  @ParameterizedTest @MethodSource("unsafeIds")
  void operatorIdentityMustStayInsideTheFrozenExactIntegerProfile(String field, long id) {
    fixed(catchThrowable(() -> new GitHubInstallationConfiguration(field.equals("app") ? id : 31,
        field.equals("installation") ? id : 71, field.equals("repository") ? id : 101, field.equals("owner") ? id : 201,
        REPOSITORY, CONFIG.permissions())), "CONFIGURATION_REJECTED");
  }

  @Test void operatorScopeAndPermissionsAreExplicitImmutableAndClosed() {
    var permissions = new HashMap<>(CONFIG.permissions());
    var config = new GitHubInstallationConfiguration(31, 71, 101, 201, REPOSITORY, permissions); permissions.clear();
    assertThat(config.permissions()).hasSize(3);
    assertThat(catchThrowable(() -> config.permissions().clear()) instanceof UnsupportedOperationException).isTrue();
    fixed(catchThrowable(() -> new GitHubInstallationConfiguration(31, 71, 101, 201, "other/repository", CONFIG.permissions())), "CONFIGURATION_REJECTED");
    fixed(catchThrowable(() -> new GitHubInstallationConfiguration(31, 71, 101, 201, REPOSITORY, Map.of("administration", SENTINEL))), "CONFIGURATION_REJECTED");
    assertThat(config.toString().contains(REPOSITORY)).isFalse();
    assertThat(GitHubInstallationCredentialProvider.class.getConstructors().length).isZero();
  }

  @ParameterizedTest @ValueSource(strings = {"http://localhost:1", "https://127.0.0.1:1", "http://127.0.0.2:1", "http://[::1]:1",
      "http://127.0.0.1:0", "http://127.0.0.1:1/path", "http://user@127.0.0.1:1", "http://127.0.0.1:1/?query", "http://127.0.0.1:1/#fragment"})
  void syntheticTransportDoesNotCreateAProductionDestinationOverride(String origin) {
    fixed(catchThrowable(() -> GitHubCredentialTransport.synthetic(URI.create(origin), 71, Duration.ofSeconds(1), 1024)), "DESTINATION_REJECTED");
  }

  @Test void productionTransportHasNoPublicTrustDnsOrOriginInjectionSurface() {
    assertThat(GitHubCredentialTransport.class.getConstructors().length).isZero();
    assertThat(GitHubCredentialTransport.production(71).toString()).isEqualTo("GitHubCredentialTransport[redacted]");
    fixed(catchThrowable(() -> GitHubCredentialTransport.synthetic(URI.create("http://127.0.0.1:1"), 71, Duration.ofSeconds(11), 1024)), "CONFIGURATION_REJECTED");
  }
}
