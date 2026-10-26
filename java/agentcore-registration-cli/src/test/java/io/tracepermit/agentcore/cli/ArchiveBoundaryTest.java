package io.tracepermit.agentcore.cli;

import static io.tracepermit.agentcore.cli.SyntheticIdentity.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(90)
final class ArchiveBoundaryTest {
  @Test void unpackedInventoryHashAndActualJava21WithNoSourceOrBuildTools() throws Exception {
    try (var installed = new InstalledArchive()) {
      assertThat(Runtime.version().feature()).isEqualTo(21);
      var archive = Path.of(System.getProperty("tracepermit.cli.archive"));
      String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(archive)));
      var inventory = new StringBuilder("SHA256 " + hash + " " + archive.getFileName() + "\nJava " + Runtime.version() + "\n");
      try (var paths = Files.walk(installed.home)) {
        for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
          String relative = installed.home.relativize(path).toString();
          assertThat(relative.matches(".*(?:\\.java|\\.class|\\.gradle|\\.git|node_modules|src/|test/).*" )).isFalse();
          inventory.append(relative).append('\n');
          if (relative.endsWith(".jar")) try (var jar = new JarFile(path.toFile())) {
            if (relative.contains("agentcore-registration-cli-")) {
              assertThat(jar.stream().anyMatch(e -> e.getName().matches(".*(?:Test|Synthetic|HostileChild|CommitGate|LocalDatabases).*"))).isFalse();
              var entry = jar.getJarEntry("io/tracepermit/agentcore/cli/Bootstrap.class");
              assertThat(entry).isNotNull();
              byte[] bytecode = jar.getInputStream(entry).readNBytes(8);
              assertThat(((bytecode[6] & 255) << 8) | (bytecode[7] & 255)).isEqualTo(52);
            }
          }
        }
      }
      assertThat(installed.call("check-config", frames(bytes(config()))).outcome()).isEqualTo("CONFIG_VALID");
      Files.writeString(archive.resolveSibling("cli-runtime-inventory.txt"), inventory);
    }
  }

  @Test void installedOfflineCheckNeverConnectsEvenWithApparentlyReadyPorts() throws Exception {
    try (var installed = new InstalledArchive(); var sentinel = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
      sentinel.setSoTimeout(200); var config = config();
      ((ObjectNode)config.get("replay")).put("port", sentinel.getLocalPort());
      ((ObjectNode)config.get("operations")).put("port", sentinel.getLocalPort());
      assertThat(installed.call("check-config", frames(bytes(config))).outcome()).isEqualTo("CONFIG_VALID");
      boolean connected;
      try (var unexpected = sentinel.accept()) { connected = unexpected.isConnected(); }
      catch (java.net.SocketTimeoutException expected) { connected = false; }
      assertThat(connected).as("zero installed TCP connection attempts").isFalse();
    }
  }

  @Test void realSlowOpenInputIsKilledByInstalledCommandAndWorkerReaped() throws Exception {
    try (var installed = new InstalledArchive()) {
      Process process = installed.start("register-once");
      var children = new java.util.concurrent.CopyOnWriteArrayList<ProcessHandle>();
      try {
        long ready = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (children.isEmpty() && System.nanoTime() < ready) {
          children.addAll(process.descendants().filter(p -> p.info().arguments()
              .map(a -> java.util.Arrays.asList(a).contains(Worker.class.getName())).orElse(false)).toList());
          Thread.sleep(10);
        }
        assertThat(children).hasSize(1); assertThat(children.get(0).isAlive()).isTrue();
        // Positive fixture: keep a partial length frame open, with a real worker blocked on stdin.
        process.getOutputStream().write(new byte[]{0, 0}); process.getOutputStream().flush();
        Thread.sleep(200); assertThat(children.get(0).isAlive()).isTrue(); assertThat(process.isAlive()).isTrue();
        assertThat(process.waitFor(66, TimeUnit.SECONDS)).as("60s total plus separate 5s cleanup reserve").isTrue();
        assertThat(process.exitValue()).isNotZero();
        assertThat(Receipt.decode(process.getInputStream().readNBytes(1024), process.exitValue()).outcome()).isEqualTo("UNKNOWN");
        assertThat(children.stream().anyMatch(ProcessHandle::isAlive)).isFalse();
      } finally {
        process.toHandle().destroyForcibly(); process.waitFor(3, TimeUnit.SECONDS);
        for (var child : children) if (child.isAlive()) child.destroyForcibly();
      }
    }
  }

  @Test void fileInputAndUnsupportedCommandsRejectedWithoutWorker() throws Exception {
    try (var installed = new InstalledArchive()) {
      var builder = new ProcessBuilder(installed.launcher.toString(), "check-config").directory(installed.root.toFile());
      builder.environment().clear(); builder.environment().put("JAVA_HOME", System.getProperty("java.home")); builder.environment().put("PATH", "/usr/bin:/bin");
      builder.redirectInput(Path.of("/dev/null").toFile());
      Process process = builder.start();
      try {
        assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
        assertThat(Receipt.decode(process.getInputStream().readNBytes(1024), process.exitValue()).outcome()).isEqualTo("INPUT_INVALID");
      } finally { process.toHandle().destroyForcibly(); process.waitFor(2, TimeUnit.SECONDS); }
      assertThat(installed.call("execute", new byte[0]).outcome()).isEqualTo("USAGE");
    }
  }

  @ParameterizedTest @ValueSource(strings = {"duplicate", "utf8", "trailing", "truncated", "oversized", "extra", "remoteOptions"})
  void installedMalformedFramesNeverPass(String mode) throws Exception {
    byte[] value = bytes(config());
    byte[] frame = switch (mode) {
      case "duplicate" -> frames("{\"profile\":1,\"profile\":2}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      case "utf8" -> frames(new byte[]{(byte)0xff});
      case "trailing" -> frames("{} {}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      case "truncated" -> java.util.Arrays.copyOf(frames(value), value.length + 3);
      case "oversized" -> new byte[]{0,1,0,1};
      case "extra" -> frames(value, value);
      case "remoteOptions" -> {
        var config = config(); ((ObjectNode)config.get("operations")).put("properties", "socketFactory=remote.invalid");
        yield frames(bytes(config));
      }
      default -> throw new AssertionError();
    };
    try (var installed = new InstalledArchive()) { assertThat(installed.call("check-config", frame).exitCode()).isNotZero(); }
  }

  @Test void brokenReceiptPipeCannotExitSuccess() throws Exception {
    try (var installed = new InstalledArchive()) {
      Process process = installed.start("check-config");
      try {
        process.getInputStream().close();
        byte[] frame = frames(bytes(config()));
        Thread writer = InstalledArchive.thread(() -> { try { process.getOutputStream().write(frame); process.getOutputStream().close(); } catch (Exception ignored) { } });
        assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue(); writer.join(1000);
        assertThat(writer.isAlive()).isFalse(); assertThat(process.exitValue()).isNotZero();
      } finally { process.toHandle().destroyForcibly(); process.waitFor(2, TimeUnit.SECONDS); }
    }
  }

  @Test void realBootstrapRejectsUnsupportedVersionBeforeImplementationLoads() throws Exception {
    try (var installed = new InstalledArchive()) {
      Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
          "-cp", System.getProperty("tracepermit.cli.testClasspath"), UnsupportedJavaProbe.class.getName(),
          installed.home.resolve("lib").toString()).start();
      try {
        assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue(); assertThat(process.exitValue()).isEqualTo(64);
        assertThat(new String(process.getInputStream().readNBytes(128), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("JAVA_21_REQUIRED\n");
        assertThat(process.getErrorStream().readNBytes(128).length).isZero();
      } finally { process.toHandle().destroyForcibly(); process.waitFor(2, TimeUnit.SECONDS); }
    }
  }
}
