package io.tracepermit.agentcore.organizationcli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Records the exact public archive, inventory and extraction used by later manual stages. */
@Timeout(60)
final class OrganizationContainerInventoryTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void acceptedArchiveAndFixedRecipeHaveExactSourceFreeInventory() throws Exception {
    String source = required("sourceCommit");
    String manifest = required("sourceManifestHash");
    assertThat(source).matches("[a-f0-9]{40}");
    assertThat(manifest).matches("[a-f0-9]{64}");
    Path evidence = privateEvidence();
    Path archive = Path.of(required("organizationArchive")).toRealPath();
    Path dockerfile = Path.of(required("dockerfile")).toRealPath();
    Path probe = Path.of(required("probeJar")).toRealPath();
    assertThat(Files.size(archive)).isBetween(1L, 64L * 1024 * 1024);
    assertThat(Files.size(probe)).isBetween(1L, 2L * 1024 * 1024);

    List<String> inventory =
        nonemptyLines(run(List.of("/usr/bin/tar", "-tf", archive.toString()), Duration.ofSeconds(10)));
    assertThat(inventory).hasSizeBetween(2, 256);
    assertThat(inventory)
        .allSatisfy(
            name -> {
              assertThat(name).doesNotStartWith("/");
              assertThat(name).doesNotContain("../");
              assertThat(name).doesNotContain("\\");
            });
    assertThat(
            inventory.stream()
                .filter(
                    value ->
                        value.endsWith(
                            "/bin/tracepermit-synthetic-organization-operator"))
                .toList())
        .hasSize(1);

    Path extracted = evidence.resolve("accepted-organization-archive");
    Files.createDirectory(
        extracted,
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    run(
        List.of("/usr/bin/tar", "-xf", archive.toString(), "-C", extracted.toString()),
        Duration.ofSeconds(10));
    Path home;
    try (var children = Files.list(extracted)) {
      List<Path> roots = children.toList();
      assertThat(roots).hasSize(1);
      home = roots.get(0).toRealPath();
    }
    assertThat(home.startsWith(extracted.toRealPath())).isTrue();
    assertThat(Files.isExecutable(home.resolve("bin/tracepermit-synthetic-organization-operator")))
        .isTrue();
    boolean bootstrap = false;
    boolean cli = false;
    boolean worker = false;
    try (var libraries = Files.list(home.resolve("lib"))) {
      for (Path library : libraries.sorted(Comparator.comparing(Path::toString)).toList()) {
        if (!library.getFileName().toString().endsWith(".jar")) continue;
        try (var jar = new JarFile(library.toFile())) {
          bootstrap |=
              jar.getJarEntry("io/tracepermit/agentcore/organizationcli/Bootstrap.class") != null;
          cli |=
              jar.getJarEntry(
                      "io/tracepermit/agentcore/organizationcli/OrganizationOperatorCli.class")
                  != null;
          worker |=
              jar.getJarEntry(
                      "io/tracepermit/agentcore/organizationcli/OrganizationOperatorWorker.class")
                  != null;
        }
      }
    }
    assertThat(bootstrap && cli && worker).isTrue();

    String recipe = Files.readString(dockerfile, StandardCharsets.UTF_8);
    assertThat(recipe)
        .contains(
            "FROM " + OrganizationContainerImageAssembler.FIXED_BASE,
            "mkdir -p /opt/organization",
            "chmod -R a+rX,a-w /opt/organization",
            "USER 10001:10001",
            "ENTRYPOINT [\"" + OrganizationContainerContract.CLI + "\"]")
        .doesNotContain("apt-get", "apk add", "curl ", "wget ", "COPY .", "ADD ");

    var receipt = JSON.createObjectNode();
    receipt
        .put("sourceCommit", source)
        .put("sourceManifestSha256", manifest)
        .put("organizationArchive", archive.toString())
        .put("organizationArchiveSha256", OrganizationContainerImageAssembler.digest(archive))
        .put("extractionLocation", home.toString())
        .put("dockerfile", dockerfile.toString())
        .put("probeJar", probe.toString())
        .put("entrypoint", OrganizationContainerContract.CLI)
        .put("status", "INVENTORIED");
    var names = receipt.putArray("archiveInventory");
    inventory.forEach(names::add);
    Files.writeString(evidence.resolve("organization-archive-inventory.json"), receipt + "\n");
  }

  private static Path privateEvidence() throws Exception {
    Path evidence = Path.of(required("evidence")).toRealPath();
    Path checkout = Path.of(System.getProperty("tracepermit.repositoryRoot")).toRealPath();
    assertThat(evidence.startsWith(checkout)).isFalse();
    assertThat(Files.getPosixFilePermissions(evidence))
        .isEqualTo(PosixFilePermissions.fromString("rwx------"));
    return evidence;
  }

  private static byte[] run(List<String> command, Duration budget) throws Exception {
    Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
    var output = new ByteArrayOutputStream();
    var errors = new ByteArrayOutputStream();
    Thread out = capture(process.getInputStream(), output);
    Thread err = capture(process.getErrorStream(), errors);
    try {
      if (!process.waitFor(budget.toMillis(), TimeUnit.MILLISECONDS)) {
        throw new AssertionError("ORGANIZATION_ARCHIVE_TOOL_DEADLINE");
      }
      out.join(1_000);
      err.join(1_000);
      if (out.isAlive()
          || err.isAlive()
          || output.size() > 262_144
          || errors.size() > 4_096
          || process.exitValue() != 0) {
        throw new AssertionError("ORGANIZATION_ARCHIVE_TOOL_FAILED");
      }
      return output.toByteArray();
    } finally {
      if (process.isAlive()) process.destroyForcibly();
      if (!process.waitFor(5, TimeUnit.SECONDS)) {
        throw new AssertionError("ORGANIZATION_ARCHIVE_TOOL_NOT_REAPED");
      }
    }
  }

  private static Thread capture(java.io.InputStream source, ByteArrayOutputStream target) {
    Thread thread =
        new Thread(
            () -> {
              try {
                source.transferTo(target);
              } catch (java.io.IOException failure) {
                throw new AssertionError(failure);
              }
            },
            "organization-archive-inventory-reader");
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  private static List<String> nonemptyLines(byte[] bytes) {
    var result = new ArrayList<String>();
    for (String value : new String(bytes, StandardCharsets.UTF_8).split("\\R")) {
      if (!value.isBlank()) result.add(value);
    }
    return List.copyOf(result);
  }

  private static String required(String name) {
    String value = System.getProperty("tracepermit.container." + name);
    if (value == null || value.isBlank()) {
      throw new AssertionError("ORGANIZATION_INVENTORY_PROPERTY_REQUIRED");
    }
    return value;
  }
}
