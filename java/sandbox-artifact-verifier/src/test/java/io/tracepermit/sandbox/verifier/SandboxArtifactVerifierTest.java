package io.tracepermit.sandbox.verifier;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

@Isolated("Temporarily captures process stdout")
final class SandboxArtifactVerifierTest {
  @TempDir Path directory;

  @Test void completeFixtureProducesOnlyFixedSuppliedAssertionReceipt() throws Exception {
    byte[] archive = Fixtures.archive(Fixtures.header("bin/run", '0', 0));
    Path tar = directory.resolve("private-path.tar"); Files.write(tar, archive);
    String digest = Fixtures.digest(archive);
    Path evidence = directory.resolve("private-evidence.json"); Files.writeString(evidence, Fixtures.evidence(digest));
    var captured = new ByteArrayOutputStream();
    PrintStream original = System.out;
    try (PrintStream output = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
      System.setOut(output);
      int exit = SandboxArtifactVerifier.execute(new String[] {"--archive", tar.toString(), "--sha256", digest,
          "--commit", Fixtures.COMMIT, "--tree", Fixtures.TREE, "--evidence", evidence.toString()});
      assertThat(exit).isZero();
    } finally { System.setOut(original); }
    assertThat(captured.toString(StandardCharsets.UTF_8))
        .contains("\"status\":\"COMPLETE_AS_SUPPLIED\"", "\"trust\":\"SUPPLIED_ASSERTIONS_ONLY\"")
        .doesNotContain(tar.toString(), evidence.toString(), digest, Fixtures.COMMIT);
  }

  @Test void invalidArgumentsNeverEchoSuppliedData() throws Exception {
    var captured = new ByteArrayOutputStream();
    PrintStream original = System.out;
    try (PrintStream output = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
      System.setOut(output);
      assertThat(SandboxArtifactVerifier.execute(new String[] {"SYNTHETIC_PRIVATE_INPUT"})).isEqualTo(2);
    } finally { System.setOut(original); }
    assertThat(captured.toString(StandardCharsets.UTF_8))
        .contains("INVALID_ARGUMENTS").doesNotContain("SYNTHETIC_PRIVATE_INPUT", "Exception");
  }

  @Test void wrongDigestAndIncompleteOrMalformedEvidenceHaveDistinctFixedOutcomes() throws Exception {
    byte[] archive = Fixtures.archive(Fixtures.header("bin/run", '0', 0));
    Path tar = directory.resolve("private-path.tar"); Files.write(tar, archive);
    String digest = Fixtures.digest(archive);
    Path evidence = directory.resolve("private-evidence.json");
    Files.writeString(evidence, Fixtures.evidence(digest).replace("CONFIRMED_AS_SUPPLIED", "UNCERTAIN"));
    var captured = new ByteArrayOutputStream();
    PrintStream original = System.out;
    try (PrintStream output = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
      System.setOut(output);
      String[] args = {"--archive", tar.toString(), "--sha256", "0".repeat(64), "--commit", Fixtures.COMMIT,
          "--tree", Fixtures.TREE, "--evidence", evidence.toString()};
      assertThat(SandboxArtifactVerifier.execute(args)).isEqualTo(3);
      args[3] = digest;
      assertThat(SandboxArtifactVerifier.execute(args)).isEqualTo(5);
      Files.writeString(evidence, "{\"schemaVersion\":\"1\",\"schemaVersion\":\"1\"}");
      assertThat(SandboxArtifactVerifier.execute(args)).isEqualTo(4);
    } finally { System.setOut(original); }
    assertThat(captured.toString(StandardCharsets.UTF_8))
        .contains("DIGEST_MISMATCH", "EVIDENCE_INCOMPLETE", "EVIDENCE_REJECTED")
        .doesNotContain(tar.toString(), evidence.toString(), digest, Fixtures.COMMIT, "Exception");
  }
}
