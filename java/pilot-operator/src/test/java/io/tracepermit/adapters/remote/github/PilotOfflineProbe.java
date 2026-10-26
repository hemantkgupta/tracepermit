package io.tracepermit.adapters.remote.github;

import io.tracepermit.adapters.remote.github.credentials.GitHubInstallationCredentialProvider;
import java.io.File;
import java.net.InetAddress;
import java.net.JarURLConnection;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

/** Separate JVM activates a denying DNS SPI and traps provider/transport construction. */
public final class PilotOfflineProbe {
  private PilotOfflineProbe() {}
  public static void main(String[] args) {
    if (args.length != 1 || !usesOnlyArchivedRuntime(Path.of(args[0]))) {
      System.out.println("ARCHIVE_RUNTIME_ORIGIN_REJECTED");
      System.exit(9);
      return;
    }
    try { InetAddress.getAllByName("synthetic-offline-tripwire.invalid"); }
    catch (UnknownHostException expected) { /* deliberately denied by the test SPI */ }
    if (PilotNoDnsProvider.LOOKUPS.getAndSet(0) != 1) throw new AssertionError("DNS_CONTROL_INACTIVE");
    var providers = new AtomicInteger();
    var result = PilotOperatorMain.bounded(new String[]{"--check"}, System.getenv("TRACEPERMIT_PILOT_CONFIGURATION"), config -> {
      providers.incrementAndGet(); throw new AssertionError("PROVIDER_CONTROL_ENTERED");
    }, Duration.ofSeconds(5));
    if (result != PilotOperatorMain.Result.CHECK_VALID || providers.get() != 0 || PilotNoDnsProvider.LOOKUPS.get() != 0) {
      throw new AssertionError("OFFLINE_CONTROL_FAILED");
    }
    System.out.println("OFFLINE_ZERO_DNS_ZERO_PROVIDER");
  }

  private static boolean usesOnlyArchivedRuntime(Path configuredLib) {
    try {
      Path lib = configuredLib.toRealPath();
      Path helper = codeSource(PilotOfflineProbe.class);
      if (!Files.isRegularFile(helper) || helper.startsWith(lib) || !helperInventory(helper)) return false;

      Set<Path> archived;
      try (var paths = Files.list(lib)) {
        archived = paths.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().endsWith(".jar"))
            .map(PilotOfflineProbe::realPath).collect(Collectors.toUnmodifiableSet());
      }
      var classpath = new ArrayList<>(Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
          .map(Path::of).map(PilotOfflineProbe::realPath).toList());
      if (Collections.frequency(classpath, helper) != 1 || !classpath.remove(helper)
          || classpath.size() != archived.size() || !Set.copyOf(classpath).equals(archived)) return false;

      return archivedClass(PilotOperatorMain.class, lib.resolve("pilot-operator-0.1.0-SNAPSHOT.jar"))
          && archivedClass(PilotConfiguration.class, lib.resolve("pilot-operator-0.1.0-SNAPSHOT.jar"))
          && archivedClass(GitHubInstallationCredentialProvider.class,
              lib.resolve("adapters-remote-0.1.0-SNAPSHOT.jar"));
    } catch (Throwable failure) {
      return false;
    }
  }

  private static boolean helperInventory(Path helper) throws Exception {
    Set<String> expected = Set.of(
        "META-INF/MANIFEST.MF",
        "META-INF/services/java.net.spi.InetAddressResolverProvider",
        "io/tracepermit/adapters/remote/github/PilotOfflineProbe.class",
        "io/tracepermit/adapters/remote/github/PilotNoDnsProvider.class",
        "io/tracepermit/adapters/remote/github/PilotNoDnsProvider$1.class");
    try (var jar = new JarFile(helper.toFile())) {
      Set<String> actual = jar.stream().filter(entry -> !entry.isDirectory()).map(entry -> entry.getName())
          .collect(Collectors.toUnmodifiableSet());
      return actual.equals(expected);
    }
  }

  private static boolean archivedClass(Class<?> type, Path expectedJar) throws Exception {
    Path expected = expectedJar.toRealPath();
    if (!codeSource(type).equals(expected)) return false;
    var resources = Collections.list(type.getClassLoader().getResources(type.getName().replace('.', '/') + ".class"));
    if (resources.size() != 1 || !(resources.get(0).openConnection() instanceof JarURLConnection jar)) return false;
    return Path.of(jar.getJarFileURL().toURI()).toRealPath().equals(expected);
  }

  private static Path codeSource(Class<?> type) throws Exception {
    return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
  }

  private static Path realPath(Path path) {
    try { return path.toRealPath(); }
    catch (Exception failure) { throw new IllegalArgumentException("ARCHIVE_RUNTIME_ORIGIN_REJECTED"); }
  }
}
