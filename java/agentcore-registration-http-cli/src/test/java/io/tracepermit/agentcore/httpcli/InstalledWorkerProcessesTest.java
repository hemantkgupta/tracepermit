package io.tracepermit.agentcore.httpcli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(30)
final class InstalledWorkerProcessesTest {
  @Test
  void boundedCommandParserRequiresExactJavaMainAndLaunchShape() {
    var command = command("synthetic-classpath:" + "x".repeat(8_192), IdleWorker.class.getName());
    assertThat(InstalledWorkerProcesses.commandMatches(bytes(command), IdleWorker.class.getName())).isTrue();
    for (int field : new int[] {0, 1, 2, 3, 4, 5, 6, 7}) {
      var changed = new ArrayList<>(command);
      changed.set(field, field == 5 ? "" : "untrusted");
      assertThat(InstalledWorkerProcesses.commandMatches(bytes(changed), IdleWorker.class.getName()))
          .as("changed field %s", field).isFalse();
    }
    var wrongMain = command(IdleWorker.class.getName(), WrongWorker.class.getName());
    assertThat(InstalledWorkerProcesses.commandMatches(bytes(wrongMain), IdleWorker.class.getName())).isFalse();
    var extra = new ArrayList<>(command);
    extra.add(IdleWorker.class.getName());
    assertThat(InstalledWorkerProcesses.commandMatches(bytes(extra), IdleWorker.class.getName())).isFalse();
    byte[] valid = bytes(command);
    assertThat(InstalledWorkerProcesses.commandMatches(java.util.Arrays.copyOf(valid, valid.length - 1),
        IdleWorker.class.getName())).isFalse();
    assertThat(InstalledWorkerProcesses.commandMatches(new byte[InstalledWorkerProcesses.MAXIMUM_COMMAND_BYTES + 1],
        IdleWorker.class.getName())).isFalse();
  }

  @ParameterizedTest
  @ValueSource(ints = {64, 8_192})
  void findsOnlyTheOwnedWorkerWithShortOrLongClasspath(int minimum) throws Exception {
    Process parent = launch(minimum, false);
    try {
      assertThat(parent.getInputStream().read()).isEqualTo('R');
      var discovery = InstalledWorkerProcesses.find(parent, IdleWorker.class.getName());
      assertThat(discovery.matches()).as(discovery.summary()).hasSize(1);
      var worker = discovery.matches().get(0);
      assertThat(worker.parent().orElseThrow().pid()).isEqualTo(parent.pid());
      assertThat(worker.destroyForcibly()).isTrue();
      assertThat(parent.waitFor(5, TimeUnit.SECONDS)).isTrue();
      assertThat(worker.isAlive()).isFalse();
    } finally { stop(parent); }
  }

  @Test
  void workerNameInAnotherMainsArgumentsDoesNotMatch() throws Exception {
    Process parent = launch(8_192, true);
    try {
      assertThat(parent.getInputStream().read()).isEqualTo('R');
      var discovery = InstalledWorkerProcesses.find(parent, IdleWorker.class.getName());
      assertThat(discovery.directChildren()).isEqualTo(1);
      assertThat(discovery.matches()).as(discovery.summary()).isEmpty();
    } finally { stop(parent); }
  }

  private static Process launch(int minimum, boolean wrongMain) throws Exception {
    String cp = Path.of(InstalledWorkerProcessesTest.class.getProtectionDomain()
        .getCodeSource().getLocation().toURI()).toString();
    while (cp.length() < minimum) cp += java.io.File.pathSeparator + cp;
    return new ProcessBuilder(java(), "-cp", cp, Launcher.class.getName(), cp, Boolean.toString(wrongMain))
        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
  }

  private static List<String> command(String cp, String main) {
    return List.of(java(), "-Xms16m", "-Xmx64m", "-XX:ActiveProcessorCount=2", "-cp", cp, main, "serve-bounded");
  }

  private static byte[] bytes(List<String> command) {
    return (String.join("\u0000", command) + "\u0000").getBytes(StandardCharsets.UTF_8);
  }

  private static String java() {
    return Path.of(System.getProperty("java.home"), "bin", "java").toString();
  }

  private static void stop(Process process) throws Exception {
    process.destroy();
    if (!process.waitFor(2, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
    }
    process.getInputStream().close(); process.getErrorStream().close(); process.getOutputStream().close();
    assertThat(process.isAlive()).isFalse();
  }

  public static final class Launcher {
    private Launcher() {}
    public static void main(String[] args) throws Exception {
      var launch = new ArrayList<>(command(args[0],
          Boolean.parseBoolean(args[1]) ? WrongWorker.class.getName() : IdleWorker.class.getName()));
      if (Boolean.parseBoolean(args[1])) launch.add(IdleWorker.class.getName());
      Process child = new ProcessBuilder(launch).redirectError(ProcessBuilder.Redirect.DISCARD).start();
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        child.destroyForcibly();
        try { child.waitFor(5, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
      }));
      System.out.write(child.getInputStream().read()); System.out.flush();
      child.waitFor();
    }
  }

  public static final class IdleWorker {
    private IdleWorker() {}
    public static void main(String[] args) throws Exception {
      System.out.print('R'); System.out.flush(); System.in.read();
    }
  }

  public static final class WrongWorker {
    private WrongWorker() {}
    public static void main(String[] args) throws Exception {
      System.out.print('R'); System.out.flush(); System.in.read();
    }
  }
}
