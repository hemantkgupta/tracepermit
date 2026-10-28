package io.tracepermit.sandbox.eks;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/** No kubeconfig, AWS SDK, process execution or network access in render/check. */
public final class EksSandboxMain {
  private static final Set<PosixFilePermission> FILE_MODE = PosixFilePermissions.fromString("rw-------");
  private static final Set<PosixFilePermission> DIR_MODE = PosixFilePermissions.fromString("rwx------");

  private EksSandboxMain() {}

  public static void main(String[] args) {
    System.exit(run(args, System.out));
  }

  static int run(String[] args, PrintStream output) {
    if (args.length != 3 || !Set.of("render", "check").contains(args[0])) {
      output.println("EKS_BUNDLE_USAGE");
      return 64;
    }
    try {
      var configuration = EksSandboxBundle.parse(readPrivate(Path.of(args[1]), EksSandboxBundle.CONFIG_BYTES));
      Path target = Path.of(args[2]);
      if (args[0].equals("render")) {
        writeNewPrivate(target, EksSandboxBundle.JSON.writerWithDefaultPrettyPrinter()
            .writeValueAsBytes(EksSandboxBundle.render(configuration)));
        output.println("EKS_BUNDLE_RENDERED:" + EksSandboxBundle.fingerprint(configuration));
      } else {
        EksSandboxBundle.verify(configuration, readPrivate(target, EksSandboxBundle.BUNDLE_BYTES));
        output.println("EKS_BUNDLE_MATCHES_OFFLINE_CONTRACT");
      }
      return 0;
    } catch (Exception invalid) {
      output.println("EKS_BUNDLE_REFUSED");
      return 1;
    }
  }

  static byte[] readPrivate(Path path, int maximum) throws IOException {
    Path absolute = path.toAbsolutePath().normalize();
    EksSandboxBundle.require(absolute.equals(absolute.toRealPath())
        && Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)
        && Files.getPosixFilePermissions(absolute, LinkOption.NOFOLLOW_LINKS).equals(FILE_MODE));
    try (var input = Files.newInputStream(absolute, LinkOption.NOFOLLOW_LINKS)) {
      byte[] bytes = input.readNBytes(maximum + 1);
      EksSandboxBundle.require(bytes.length <= maximum);
      return bytes;
    }
  }

  static void writeNewPrivate(Path path, byte[] bytes) throws IOException {
    Path absolute = path.toAbsolutePath().normalize();
    Path parent = absolute.getParent();
    EksSandboxBundle.require(parent != null && parent.equals(parent.toRealPath())
        && Files.getPosixFilePermissions(parent).equals(DIR_MODE));
    try (var channel = Files.newByteChannel(absolute,
        Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
        PosixFilePermissions.asFileAttribute(FILE_MODE))) {
      var buffer = java.nio.ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) channel.write(buffer);
    }
  }
}
