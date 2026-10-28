package io.tracepermit.sandbox.verifier;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EvidenceVerifierTest {
  @TempDir Path directory;
  private static final String DIGEST = "c".repeat(64);
  private static final ObjectMapper JSON = new ObjectMapper();

  private ObjectNode valid() throws Exception { return (ObjectNode) JSON.readTree(Fixtures.evidence(DIGEST)); }
  private EvidenceVerifier.Result verify(String value) throws Exception {
    Path file = directory.resolve("record.json");
    Files.writeString(file, value, StandardCharsets.UTF_8);
    return EvidenceVerifier.verify(file, Fixtures.COMMIT, Fixtures.TREE, DIGEST);
  }
  private EvidenceVerifier.Result verify(JsonNode value) throws Exception { return verify(value.toString()); }
  private ObjectNode gate(ObjectNode root, int index) { return (ObjectNode) root.withArray("gates").get(index); }
  private ObjectNode coverage(ObjectNode root) { return (ObjectNode) root.get("coverage"); }

  @Test void completeAsSuppliedRequiresAllNamedFacts() throws Exception {
    assertThat(verify(valid())).isEqualTo(EvidenceVerifier.Result.COMPLETE_AS_SUPPLIED);
  }

  @Test void candidateIdentitySubstitutionsAreRejected() throws Exception {
    for (String key : new String[] {"commit", "tree", "archiveSha256"}) {
      ObjectNode root = valid(); ((ObjectNode) root.get("candidate")).put(key, "e".repeat(key.equals("archiveSha256") ? 64 : 40));
      assertThat(verify(root)).isEqualTo(EvidenceVerifier.Result.REJECTED);
    }
  }

  @Test void duplicateFieldsUnknownFieldsAndTrailingJsonAreRejected() throws Exception {
    String raw = Fixtures.evidence(DIGEST);
    assertThat(verify(raw.replace("\"schemaVersion\":\"1\"", "\"schemaVersion\":\"1\",\"schemaVersion\":\"1\"")))
        .isEqualTo(EvidenceVerifier.Result.REJECTED);
    ObjectNode root = valid(); root.put("unrecognized", true);
    assertThat(verify(root)).isEqualTo(EvidenceVerifier.Result.REJECTED);
    assertThat(verify(raw + " {}" )).isEqualTo(EvidenceVerifier.Result.REJECTED);
  }

  @Test void wrongTypesAndInconsistentPassAreRejected() throws Exception {
    ObjectNode root = valid(); gate(root, 0).put("executed", "1");
    assertThat(verify(root)).isEqualTo(EvidenceVerifier.Result.REJECTED);
    root = valid(); gate(root, 0).put("failed", 1);
    assertThat(verify(root)).isEqualTo(EvidenceVerifier.Result.REJECTED);
    root = valid(); gate(root, 0).put("outcome", "UNRUN");
    assertThat(verify(root)).isEqualTo(EvidenceVerifier.Result.REJECTED);
  }

  @Test void missingAndDuplicateGatesCannotBeComplete() throws Exception {
    ObjectNode root = valid(); ((ArrayNode) root.get("gates")).remove(0);
    assertThat(verify(root)).isEqualTo(EvidenceVerifier.Result.INCOMPLETE);
    root = valid(); gate(root, 1).put("name", "focused");
    assertThat(verify(root)).isEqualTo(EvidenceVerifier.Result.REJECTED);
    root = valid(); root.remove("candidate");
    assertThat(verify(root)).isEqualTo(EvidenceVerifier.Result.INCOMPLETE);
  }

  @Test void failedSkippedAndUnrunGatesAreIncomplete() throws Exception {
    ObjectNode root = valid(); gate(root, 0).put("outcome", "FAIL").put("failed", 1);
    assertThat(verify(root)).isEqualTo(EvidenceVerifier.Result.INCOMPLETE);
    for (String outcome : new String[] {"SKIPPED", "UNRUN"}) {
      root = valid(); gate(root, 0).put("outcome", outcome).put("executed", 0).put("skipped", 1);
      assertThat(verify(root)).isEqualTo(EvidenceVerifier.Result.INCOMPLETE);
    }
  }

  @Test void cacheFreshnessAndZeroExecutionCannotBeComplete() throws Exception {
    for (String freshness : new String[] {"CACHED", "UNKNOWN"}) {
      ObjectNode root = valid(); gate(root, 0).put("freshness", freshness);
      assertThat(verify(root)).isEqualTo(EvidenceVerifier.Result.INCOMPLETE);
    }
    ObjectNode root = valid(); gate(root, 0).put("executed", 0);
    assertThat(verify(root)).isEqualTo(EvidenceVerifier.Result.INCOMPLETE);
  }

  @Test void missingOrPartialCoverageAndLowCountersAreIncomplete() throws Exception {
    ObjectNode root = valid(); coverage(root).remove("branch");
    assertThat(verify(root)).isEqualTo(EvidenceVerifier.Result.INCOMPLETE);
    root = valid(); coverage(root).put("scope", "PARTIAL");
    assertThat(verify(root)).isEqualTo(EvidenceVerifier.Result.INCOMPLETE);
    root = valid(); coverage(root).put("complete", false);
    assertThat(verify(root)).isEqualTo(EvidenceVerifier.Result.INCOMPLETE);
    root = valid(); ((ObjectNode) coverage(root).get("line")).put("covered", 93).put("missed", 7);
    assertThat(verify(root)).isEqualTo(EvidenceVerifier.Result.INCOMPLETE);
    root = valid(); ((ObjectNode) coverage(root).get("branch")).put("covered", 74).put("missed", 26);
    assertThat(verify(root)).isEqualTo(EvidenceVerifier.Result.INCOMPLETE);
  }

  @Test void percentOnlyAndZeroDenominatorDoNotProveCoverage() throws Exception {
    ObjectNode root = valid(); coverage(root).put("percent", 100);
    assertThat(verify(root)).isEqualTo(EvidenceVerifier.Result.REJECTED);
    root = valid(); ((ObjectNode) coverage(root).get("line")).put("covered", 0).put("missed", 0);
    assertThat(verify(root)).isEqualTo(EvidenceVerifier.Result.REJECTED);
  }

  @Test void unknownProvenanceAndCleanupAreIncomplete() throws Exception {
    ObjectNode root = valid(); ((ObjectNode) root.get("provenance")).put("status", "MISSING");
    assertThat(verify(root)).isEqualTo(EvidenceVerifier.Result.INCOMPLETE);
    root = valid(); ((ObjectNode) root.get("provenance")).remove("referenceSha256");
    assertThat(verify(root)).isEqualTo(EvidenceVerifier.Result.INCOMPLETE);
    root = valid(); root.put("cleanup", "UNCERTAIN");
    assertThat(verify(root)).isEqualTo(EvidenceVerifier.Result.INCOMPLETE);
    root = valid(); root.remove("cleanup");
    assertThat(verify(root)).isEqualTo(EvidenceVerifier.Result.INCOMPLETE);
  }

  @Test void invalidUtf8AndOversizedEvidenceAreRejected() throws Exception {
    Path file = directory.resolve("invalid.json");
    Files.write(file, new byte[] {(byte) 0xc3, (byte) 0x28});
    assertThat(EvidenceVerifier.verify(file, Fixtures.COMMIT, Fixtures.TREE, DIGEST)).isEqualTo(EvidenceVerifier.Result.REJECTED);
    Files.write(file, new byte[262_145]);
    assertThat(EvidenceVerifier.verify(file, Fixtures.COMMIT, Fixtures.TREE, DIGEST)).isEqualTo(EvidenceVerifier.Result.REJECTED);
  }
}
