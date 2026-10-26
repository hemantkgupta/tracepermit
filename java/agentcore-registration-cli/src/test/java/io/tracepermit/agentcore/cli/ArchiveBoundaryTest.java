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

  @Test void namedFifoAndUnlinkedFifoRejectedAtSupervisorBoundary() throws Exception {
    try (var installed = new InstalledArchive()) {
      Path fifoDir = Files.createTempDirectory("tp-fifo-sup-");
      Path linkedFifo = fifoDir.resolve("linked.fifo");
      Path unlinkedFifo = fifoDir.resolve("unlinked.fifo");
      var mk1 = new ProcessBuilder("mkfifo", linkedFifo.toString()).start();
      assertThat(mk1.waitFor(5, TimeUnit.SECONDS)).isTrue(); assertThat(mk1.exitValue()).isZero();
      var mk2 = new ProcessBuilder("mkfifo", unlinkedFifo.toString()).start();
      assertThat(mk2.waitFor(5, TimeUnit.SECONDS)).isTrue(); assertThat(mk2.exitValue()).isZero();

      try {
        // 1. Linked named FIFO at supervisor boundary
        var b1 = new ProcessBuilder("/bin/sh", "-c",
            "exec \"$1\" check-config", "sh", installed.launcher.toString()).directory(installed.root.toFile());
        b1.redirectInput(linkedFifo.toFile());
        b1.environment().clear();
        b1.environment().put("JAVA_HOME", System.getProperty("java.home"));
        b1.environment().put("PATH", "/usr/bin:/bin");
        Thread w1 = new Thread(() -> {
          try (var fos = new java.io.FileOutputStream(linkedFifo.toFile())) {
            fos.write(new byte[]{0, 0, 0, 1, 0});
          } catch (Exception ignored) { }
        });
        w1.setDaemon(true); w1.start();
        Process p1 = b1.start();
        try {
          assertThat(p1.waitFor(5, TimeUnit.SECONDS)).isTrue();
          assertThat(p1.exitValue()).isNotZero();
          assertThat(Receipt.decode(p1.getInputStream().readNBytes(1024), p1.exitValue()).outcome()).isEqualTo("INPUT_INVALID");
        } finally { p1.toHandle().destroyForcibly(); p1.waitFor(2, TimeUnit.SECONDS); }

        // 2. Unlinked named FIFO at supervisor boundary
        var b2 = new ProcessBuilder("/bin/sh", "-c",
            "rm -f \"$1\" && exec \"$2\" check-config",
            "sh", unlinkedFifo.toString(), installed.launcher.toString()).directory(installed.root.toFile());
        b2.redirectInput(unlinkedFifo.toFile());
        b2.environment().clear();
        b2.environment().put("JAVA_HOME", System.getProperty("java.home"));
        b2.environment().put("PATH", "/usr/bin:/bin");
        Thread w2 = new Thread(() -> {
          try (var fos = new java.io.FileOutputStream(unlinkedFifo.toFile())) {
            fos.write(new byte[]{0, 0, 0, 1, 0});
          } catch (Exception ignored) { }
        });
        w2.setDaemon(true); w2.start();
        Process p2 = b2.start();
        try {
          assertThat(p2.waitFor(5, TimeUnit.SECONDS)).isTrue();
          assertThat(p2.exitValue()).isNotZero();
          assertThat(Receipt.decode(p2.getInputStream().readNBytes(1024), p2.exitValue()).outcome()).isEqualTo("INPUT_INVALID");
        } finally { p2.toHandle().destroyForcibly(); p2.waitFor(2, TimeUnit.SECONDS); }
      } finally {
        try { Files.deleteIfExists(linkedFifo); } catch (Exception ignored) { }
        try { Files.deleteIfExists(unlinkedFifo); } catch (Exception ignored) { }
        try { Files.deleteIfExists(fifoDir); } catch (Exception ignored) { }
      }
    }
  }

  @Test void namedFifoAndUnlinkedFifoRejectedAtWorkerBoundary() throws Exception {
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    String cp = System.getProperty("tracepermit.cli.testClasspath");
    Path fifoDir = Files.createTempDirectory("tp-fifo-worker-");
    Path linkedFifo = fifoDir.resolve("linked.fifo");
    Path unlinkedFifo = fifoDir.resolve("unlinked.fifo");
    var mk1 = new ProcessBuilder("mkfifo", linkedFifo.toString()).start();
    assertThat(mk1.waitFor(5, TimeUnit.SECONDS)).isTrue(); assertThat(mk1.exitValue()).isZero();
    var mk2 = new ProcessBuilder("mkfifo", unlinkedFifo.toString()).start();
    assertThat(mk2.waitFor(5, TimeUnit.SECONDS)).isTrue(); assertThat(mk2.exitValue()).isZero();

    try {
      // 1. Linked named FIFO at worker boundary
      var b1 = new ProcessBuilder("/bin/sh", "-c",
          "exec \"$1\" -cp \"$2\" io.tracepermit.agentcore.cli.Worker check-config", "sh", java, cp);
      b1.redirectInput(linkedFifo.toFile());
      Thread w1 = new Thread(() -> {
        try (var fos = new java.io.FileOutputStream(linkedFifo.toFile())) {
          fos.write(new byte[]{0, 0, 0, 1, 0});
        } catch (Exception ignored) { }
      });
      w1.setDaemon(true); w1.start();
      Process p1 = b1.start();
      try {
        assertThat(p1.waitFor(5, TimeUnit.SECONDS)).isTrue();
        assertThat(p1.exitValue()).isNotZero();
        assertThat(Receipt.decode(p1.getInputStream().readNBytes(1024), p1.exitValue()).outcome()).isEqualTo("INPUT_INVALID");
      } finally { p1.toHandle().destroyForcibly(); p1.waitFor(2, TimeUnit.SECONDS); }

      // 2. Unlinked named FIFO at worker boundary
      var b2 = new ProcessBuilder("/bin/sh", "-c",
          "rm -f \"$1\" && exec \"$2\" -cp \"$3\" io.tracepermit.agentcore.cli.Worker check-config",
          "sh", unlinkedFifo.toString(), java, cp);
      b2.redirectInput(unlinkedFifo.toFile());
      Thread w2 = new Thread(() -> {
        try (var fos = new java.io.FileOutputStream(unlinkedFifo.toFile())) {
          fos.write(new byte[]{0, 0, 0, 1, 0});
        } catch (Exception ignored) { }
      });
      w2.setDaemon(true); w2.start();
      Process p2 = b2.start();
      try {
        assertThat(p2.waitFor(5, TimeUnit.SECONDS)).isTrue();
        assertThat(p2.exitValue()).isNotZero();
        assertThat(Receipt.decode(p2.getInputStream().readNBytes(1024), p2.exitValue()).outcome()).isEqualTo("INPUT_INVALID");
      } finally { p2.toHandle().destroyForcibly(); p2.waitFor(2, TimeUnit.SECONDS); }

      // 3. Positive control: anonymous pipe is accepted at worker boundary
      var b3 = new ProcessBuilder(java, "-cp", cp, Worker.class.getName(), "check-config");
      Process p3 = b3.start();
      try (var out = p3.getOutputStream()) {
        out.write(frames(bytes(config())));
      }
      try {
        assertThat(p3.waitFor(5, TimeUnit.SECONDS)).isTrue();
        assertThat(p3.exitValue()).isZero();
        assertThat(Receipt.decode(p3.getInputStream().readNBytes(1024), p3.exitValue()).outcome()).isEqualTo("CONFIG_VALID");
      } finally { p3.toHandle().destroyForcibly(); p3.waitFor(2, TimeUnit.SECONDS); }
    } finally {
      try { Files.deleteIfExists(linkedFifo); } catch (Exception ignored) { }
      try { Files.deleteIfExists(unlinkedFifo); } catch (Exception ignored) { }
      try { Files.deleteIfExists(fifoDir); } catch (Exception ignored) { }
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
