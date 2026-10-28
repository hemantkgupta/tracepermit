package io.tracepermit.agentcore.lifecyclecli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(30)
final class LifecycleWorkerProcessesTest {
  @Test
  void boundedCommandParserRequiresExactExecutableMainInspectAndFraming() {
    String expected = IdleWorker.class.getName();
    var valid = command("synthetic-classpath:" + "x".repeat(8_192), expected);
    assertThat(LifecycleWorkerProcesses.commandMatches(bytes(valid), expected)).isTrue();
    for (int field = 0; field < valid.size(); field++) {
      var changed = new ArrayList<>(valid);
      changed.set(field, field == 5 ? "" : "untrusted");
      assertThat(LifecycleWorkerProcesses.commandMatches(bytes(changed), expected))
          .as("changed launch field %s", field)
          .isFalse();
    }
    var wrongMain = command(expected, DecoyWorker.class.getName());
    wrongMain.add(expected);
    assertThat(LifecycleWorkerProcesses.commandMatches(bytes(wrongMain), expected)).isFalse();
    byte[] encoded = bytes(valid);
    assertThat(
            LifecycleWorkerProcesses.commandMatches(
                Arrays.copyOf(encoded, encoded.length - 1), expected))
        .isFalse();
    byte[] malformedUtf8 = encoded.clone();
    malformedUtf8[0] = (byte) 0xff;
    assertThat(LifecycleWorkerProcesses.commandMatches(malformedUtf8, expected)).isFalse();
    assertThat(LifecycleWorkerProcesses.commandMatches(new byte[0], expected)).isFalse();
    assertThat(
            LifecycleWorkerProcesses.commandMatches(
                new byte[LifecycleWorkerProcesses.MAXIMUM_COMMAND_BYTES + 1], expected))
        .isFalse();
  }

  @Test
  void parserAcceptsItsExactByteLimitAndRejectsOverflowAndExtraFields() {
    String expected = IdleWorker.class.getName();
    int otherBytes = bytes(command("", expected)).length;
    var maximum =
        command("x".repeat(LifecycleWorkerProcesses.MAXIMUM_COMMAND_BYTES - otherBytes), expected);
    assertThat(bytes(maximum)).hasSize(LifecycleWorkerProcesses.MAXIMUM_COMMAND_BYTES);
    assertThat(LifecycleWorkerProcesses.commandMatches(bytes(maximum), expected)).isTrue();
    maximum.set(5, maximum.get(5) + "x");
    assertThat(LifecycleWorkerProcesses.commandMatches(bytes(maximum), expected)).isFalse();
    var extra = command("synthetic-classpath", expected);
    extra.add("inspect");
    assertThat(LifecycleWorkerProcesses.commandMatches(bytes(extra), expected)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(ints = {64, 8_192})
  void findsOnlyTheDirectOwnedWorkerWithShortOrLongClasspath(int minimum) throws Exception {
    Process parent = launch(minimum, "owned");
    try {
      awaitReady(parent);
      var discovery = LifecycleWorkerProcesses.find(parent, IdleWorker.class.getName());
      assertThat(discovery.matches()).as(discovery.summary()).hasSize(1);
      ProcessHandle worker = discovery.matches().getFirst();
      assertThat(worker.parent().orElseThrow().pid()).isEqualTo(parent.pid());
      assertThat(worker.destroyForcibly()).isTrue();
      assertThat(parent.waitFor(5, TimeUnit.SECONDS)).isTrue();
      assertThat(worker.isAlive()).isFalse();
    } finally {
      stopFixture(parent);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"wrong-main", "wrong-command", "nested"})
  void refusesDecoysAndAGrandchildEvenWithTheExpectedWorkerName(String mode) throws Exception {
    Process parent = launch(8_192, mode);
    try {
      awaitReady(parent);
      var discovery = LifecycleWorkerProcesses.find(parent, IdleWorker.class.getName());
      assertThat(discovery.directChildren()).isEqualTo(1);
      assertThat(discovery.matches()).as(discovery.summary()).isEmpty();
      if (mode.equals("nested")) {
        ProcessHandle intermediate = parent.toHandle().children().findFirst().orElseThrow();
        assertThat(intermediate.children().filter(ProcessHandle::isAlive).toList()).hasSize(1);
      }
    } finally {
      stopFixture(parent);
    }
  }

  private static Process launch(int minimum, String mode) throws Exception {
    String cp =
        Path.of(
                LifecycleWorkerProcessesTest.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI())
            .toString();
    while (cp.length() < minimum) cp += File.pathSeparator + cp;
    return new ProcessBuilder(java(), "-cp", cp, Launcher.class.getName(), cp, mode)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start();
  }

  private static void awaitReady(Process parent) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
    while (parent.isAlive()
        && parent.getInputStream().available() == 0
        && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertThat(parent.getInputStream().available()).as("private fixture ready byte").isPositive();
    assertThat(parent.getInputStream().read()).isEqualTo('R');
  }

  private static ArrayList<String> command(String cp, String main) {
    return new ArrayList<>(
        List.of(
            java(),
            "-Xms16m",
            "-Xmx64m",
            "-XX:ActiveProcessorCount=2",
            "-cp",
            cp,
            main,
            "inspect"));
  }

  private static byte[] bytes(List<String> command) {
    return (String.join("\u0000", command) + "\u0000").getBytes(StandardCharsets.UTF_8);
  }

  private static String java() {
    return Path.of(System.getProperty("java.home"), "bin", "java").toString();
  }

  private static void stopFixture(Process parent) throws Exception {
    // All of these handles are created by this test's private fixture root, never a name search.
    var handles = new ArrayList<>(parent.toHandle().descendants().toList());
    handles.sort(
        Comparator.comparingInt((ProcessHandle child) -> fixtureDepth(child, parent.pid()))
            .reversed());
    handles.add(parent.toHandle());
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    try {
      for (var handle : handles) {
        if (handle.isAlive()) handle.destroyForcibly();
        handle.onExit().get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        assertThat(handle.isAlive()).isFalse();
      }
    } finally {
      for (var handle : handles) {
        if (handle.isAlive()) handle.destroyForcibly();
      }
      parent.getInputStream().close();
      parent.getErrorStream().close();
      parent.getOutputStream().close();
    }
  }

  private static int fixtureDepth(ProcessHandle child, long rootPid) {
    int depth = 0;
    while (child.pid() != rootPid) {
      var owner = child.parent();
      if (owner.isEmpty()) break;
      child = owner.orElseThrow();
      depth++;
    }
    return depth;
  }

  private static Process startChild(String cp, String mode) throws Exception {
    String main =
        switch (mode) {
          case "wrong-main" -> DecoyWorker.class.getName();
          case "nested" -> NestedLauncher.class.getName();
          default -> IdleWorker.class.getName();
        };
    var launch = command(cp, main);
    if (mode.equals("wrong-main")) launch.add(IdleWorker.class.getName());
    if (mode.equals("wrong-command")) launch.set(7, "evaluate");
    return new ProcessBuilder(launch).redirectError(ProcessBuilder.Redirect.DISCARD).start();
  }

  private static void forwardReadyAndWait(Process child) throws Exception {
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  child.destroyForcibly();
                  try {
                    child.waitFor(5, TimeUnit.SECONDS);
                  } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                  }
                }));
    System.out.write(child.getInputStream().read());
    System.out.flush();
    child.waitFor();
  }

  public static final class Launcher {
    private Launcher() {}

    public static void main(String[] args) throws Exception {
      forwardReadyAndWait(startChild(args[0], args[1]));
    }
  }

  public static final class NestedLauncher {
    private NestedLauncher() {}

    public static void main(String[] args) throws Exception {
      forwardReadyAndWait(startChild(System.getProperty("java.class.path"), "owned"));
    }
  }

  public static final class IdleWorker {
    private IdleWorker() {}

    public static void main(String[] args) throws Exception {
      System.out.print('R');
      System.out.flush();
      System.in.read();
    }
  }

  public static final class DecoyWorker {
    private DecoyWorker() {}

    public static void main(String[] args) throws Exception {
      System.out.print('R');
      System.out.flush();
      System.in.read();
    }
  }
}
