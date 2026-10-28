package io.tracepermit.sandbox.verifier;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/** Command-line entrypoint for mechanical checks and supplied assertions only. */
public final class SandboxArtifactVerifier {
  private SandboxArtifactVerifier() {}

  public static void main(String[] args) {
    int code = execute(args);
    if (code != 0) System.exit(code);
  }

  static int execute(String[] args) {
    if (args == null || args.length != 10
        || !"--archive".equals(args[0]) || !"--sha256".equals(args[2])
        || !"--commit".equals(args[4]) || !"--tree".equals(args[6]) || !"--evidence".equals(args[8])
        || args[1] == null || args[9] == null || args[3] == null || args[5] == null || args[7] == null
        || !args[3].matches("[0-9a-f]{64}") || !args[5].matches("[0-9a-f]{40}")
        || !args[7].matches("[0-9a-f]{40}")) {
      receipt("REJECTED", "NOT_CHECKED", "NOT_CHECKED", "INVALID_ARGUMENTS");
      return 2;
    }
    try {
      TarVerifier.Result archive = TarVerifier.verify(Path.of(args[1]), args[3]);
      if (archive != TarVerifier.Result.MATCH) {
        receipt("REJECTED", "REJECTED", "NOT_CHECKED",
            archive == TarVerifier.Result.MISMATCH ? "DIGEST_MISMATCH" : "ARCHIVE_REJECTED");
        return 3;
      }
      EvidenceVerifier.Result record = EvidenceVerifier.verify(Path.of(args[9]), args[5], args[7], args[3]);
      if (record == EvidenceVerifier.Result.REJECTED) {
        receipt("REJECTED", "MATCH", "REJECTED", "EVIDENCE_REJECTED");
        return 4;
      }
      if (record == EvidenceVerifier.Result.INCOMPLETE) {
        receipt("INCOMPLETE", "MATCH", "INCOMPLETE", "EVIDENCE_INCOMPLETE");
        return 5;
      }
      receipt("COMPLETE_AS_SUPPLIED", "MATCH", "COMPLETE_AS_SUPPLIED", "NONE");
      return 0;
    } catch (InvalidPathException | SecurityException rejected) {
      receipt("REJECTED", "NOT_CHECKED", "NOT_CHECKED", "INVALID_ARGUMENTS");
      return 2;
    }
  }

  private static void receipt(String status, String archive, String record, String reason) {
    System.out.println("{\"schemaVersion\":\"1\",\"status\":\"" + status
        + "\",\"archive\":\"" + archive + "\",\"record\":\"" + record
        + "\",\"reason\":\"" + reason + "\",\"trust\":\"SUPPLIED_ASSERTIONS_ONLY\"}");
  }
}
