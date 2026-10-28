package io.tracepermit.agentcore.lifecyclecli;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Test-only discovery for the fixed inspect launch; never selects an arbitrary descendant. */
final class LifecycleWorkerProcesses {
  static final int MAXIMUM_COMMAND_BYTES = 131_072;

  private LifecycleWorkerProcesses() {}

  static Discovery find(Process parent, String expectedMain) {
    var matches = new ArrayList<ProcessHandle>();
    int directChildren = 0;
    int missingArguments = 0;
    int unreadableCommands = 0;
    for (var child : parent.toHandle().children().toList()) {
      if (!owned(parent, child)) continue;
      directChildren++;
      var info = child.info();
      var arguments = info.arguments();
      boolean match;
      if (arguments.isPresent()) {
        match =
            javaExecutable(info.command().orElse(""))
                && workerArguments(arguments.orElseThrow(), expectedMain);
      } else {
        missingArguments++;
        byte[] command = linuxCommand(child);
        if (command == null) unreadableCommands++;
        match = command != null && commandMatches(command, expectedMain);
      }
      // Metadata reads do not establish that the same live child still belongs to this parent.
      if (match && owned(parent, child)) matches.add(child);
    }
    return new Discovery(
        List.copyOf(matches), directChildren, missingArguments, unreadableCommands);
  }

  private static boolean owned(Process parent, ProcessHandle child) {
    return parent.isAlive()
        && child.isAlive()
        && child.parent().map(owner -> owner.pid() == parent.pid()).orElse(false);
  }

  private static byte[] linuxCommand(ProcessHandle child) {
    if (!System.getProperty("os.name", "").equals("Linux")) return null;
    try (var input = Files.newInputStream(Path.of("/proc", Long.toString(child.pid()), "cmdline"))) {
      byte[] bytes = input.readNBytes(MAXIMUM_COMMAND_BYTES + 1);
      return bytes.length <= MAXIMUM_COMMAND_BYTES ? bytes : null;
    } catch (Exception unavailable) {
      return null;
    }
  }

  static boolean commandMatches(byte[] bytes, String expectedMain) {
    if (bytes.length == 0
        || bytes.length > MAXIMUM_COMMAND_BYTES
        || bytes[bytes.length - 1] != 0) return false;
    String decoded;
    try {
      decoded = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    } catch (CharacterCodingException invalid) {
      return false;
    }
    String[] command = decoded.split("\u0000", -1);
    if (command.length != 9 || !command[8].isEmpty() || !javaExecutable(command[0])) return false;
    return workerArguments(Arrays.copyOfRange(command, 1, 8), expectedMain);
  }

  private static boolean workerArguments(String[] arguments, String expectedMain) {
    return arguments.length == 7
        && arguments[0].equals("-Xms16m")
        && arguments[1].equals("-Xmx64m")
        && arguments[2].equals("-XX:ActiveProcessorCount=2")
        && arguments[3].equals("-cp")
        && !arguments[4].isBlank()
        && arguments[5].equals(expectedMain)
        && arguments[6].equals("inspect");
  }

  private static boolean javaExecutable(String command) {
    try {
      return Path.of(command)
          .toRealPath()
          .equals(Path.of(System.getProperty("java.home"), "bin", "java").toRealPath());
    } catch (Exception unavailable) {
      return false;
    }
  }

  record Discovery(
      List<ProcessHandle> matches,
      int directChildren,
      int missingArguments,
      int unreadableCommands) {
    String summary() {
      return "LIFECYCLE_WORKER_DISCOVERY[children="
          + directChildren
          + ",matches="
          + matches.size()
          + ",missingArguments="
          + missingArguments
          + ",unreadableCommands="
          + unreadableCommands
          + "]";
    }
  }
}
