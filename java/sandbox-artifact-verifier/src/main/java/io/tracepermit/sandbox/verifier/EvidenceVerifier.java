package io.tracepermit.sandbox.verifier;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** Checks only consistency and completeness of assertions supplied in a bounded record. */
final class EvidenceVerifier {
  private static final int MAX_BYTES = 262_144;
  private static final long MAX_COUNT = 1_000_000_000L;
  private static final Set<String> GATES = Set.of("focused", "repository", "format", "docs-links", "docs-flows");
  private static final Set<String> TEST_GATES = Set.of("focused", "repository", "docs-links", "docs-flows");
  private static final Set<String> OUTCOMES = Set.of("PASS", "FAIL", "SKIPPED", "UNRUN");
  private static final Set<String> FRESHNESS = Set.of("FRESH", "CACHED", "UNKNOWN");
  private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(12)
          .maxStringLength(1_024).maxNumberLength(10).build()).build());

  private EvidenceVerifier() {}
  enum Result { COMPLETE_AS_SUPPLIED, INCOMPLETE, REJECTED }

  static Result verify(Path file, String commit, String tree, String digest) {
    try {
      if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return Result.REJECTED;
      byte[] bytes;
      try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
        bytes = input.readNBytes(MAX_BYTES + 1);
      }
      if (bytes.length == 0 || bytes.length > MAX_BYTES) return Result.REJECTED;
      String json = decode(bytes);
      try (JsonParser parser = MAPPER.getFactory().createParser(json)) {
        JsonNode root = MAPPER.readTree(parser);
        if (root == null || parser.nextToken() != null) return Result.REJECTED;
        State state = new State();
        validate(root, commit, tree, digest, state);
        return state.incomplete ? Result.INCOMPLETE : Result.COMPLETE_AS_SUPPLIED;
      }
    } catch (IOException | IllegalArgumentException rejected) {
      return Result.REJECTED;
    }
  }

  private static String decode(byte[] bytes) throws CharacterCodingException {
    return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
  }

  private static void validate(JsonNode root, String commit, String tree, String digest, State s) {
    fields(root, s, "schemaVersion", "candidate", "gates", "coverage", "provenance", "cleanup");
    String version = string(root, "schemaVersion", s);
    if (version != null && !version.equals("1")) throw invalid();
    JsonNode candidate = object(root, "candidate", s);
    if (candidate != null) {
      fields(candidate, s, "commit", "tree", "archiveSha256");
      exact(candidate, "commit", commit, s);
      exact(candidate, "tree", tree, s);
      exact(candidate, "archiveSha256", digest, s);
    }
    JsonNode gates = field(root, "gates", s);
    if (gates != null) {
      if (!gates.isArray() || gates.size() > GATES.size()) throw invalid();
      Set<String> seen = new HashSet<>();
      for (JsonNode gate : gates) validateGate(gate, seen, s);
      if (!seen.equals(GATES)) s.incomplete = true;
    }
    JsonNode coverage = object(root, "coverage", s);
    if (coverage != null) validateCoverage(coverage, s);
    JsonNode provenance = object(root, "provenance", s);
    if (provenance != null) {
      fields(provenance, s, "status", "referenceSha256");
      String status = choice(provenance, "status", s, Set.of("PRESENT_AS_SUPPLIED", "MISSING", "UNKNOWN"));
      String reference = string(provenance, "referenceSha256", s);
      if (status != null && !status.equals("PRESENT_AS_SUPPLIED")) s.incomplete = true;
      if (reference != null && !reference.matches("[0-9a-f]{64}")) throw invalid();
    }
    String cleanup = choice(root, "cleanup", s, Set.of("CONFIRMED_AS_SUPPLIED", "UNCERTAIN", "FAILED", "UNRUN"));
    if (cleanup != null && !cleanup.equals("CONFIRMED_AS_SUPPLIED")) s.incomplete = true;
  }

  private static void validateGate(JsonNode gate, Set<String> seen, State s) {
    fields(gate, s, "name", "outcome", "freshness", "executed", "failed", "skipped");
    String name = choice(gate, "name", s, GATES);
    if (name != null && !seen.add(name)) throw invalid();
    String outcome = choice(gate, "outcome", s, OUTCOMES);
    String freshness = choice(gate, "freshness", s, FRESHNESS);
    Long executed = number(gate, "executed", s);
    Long failed = number(gate, "failed", s);
    Long skipped = number(gate, "skipped", s);
    if (executed != null && failed != null && failed > executed) throw invalid();
    if (outcome != null) {
      if (outcome.equals("PASS") && ((failed != null && failed != 0) || (skipped != null && skipped != 0))) {
        throw invalid();
      }
      if ((outcome.equals("UNRUN") || outcome.equals("SKIPPED"))
          && ((executed != null && executed != 0) || (failed != null && failed != 0))) throw invalid();
      if (!outcome.equals("PASS")) s.incomplete = true;
    }
    if (freshness != null && !freshness.equals("FRESH")) s.incomplete = true;
    if (name != null && TEST_GATES.contains(name) && executed != null && executed == 0) s.incomplete = true;
    if ((failed != null && failed > 0) || (skipped != null && skipped > 0)) s.incomplete = true;
  }

  private static void validateCoverage(JsonNode coverage, State s) {
    fields(coverage, s, "scope", "freshness", "complete", "line", "branch");
    String scope = choice(coverage, "scope", s, Set.of("ALL_REQUIRED_MODULES", "PARTIAL", "UNKNOWN"));
    String freshness = choice(coverage, "freshness", s, FRESHNESS);
    if (scope != null && !scope.equals("ALL_REQUIRED_MODULES")) s.incomplete = true;
    if (freshness != null && !freshness.equals("FRESH")) s.incomplete = true;
    JsonNode complete = field(coverage, "complete", s);
    if (complete != null) {
      if (!complete.isBoolean()) throw invalid();
      if (!complete.booleanValue()) s.incomplete = true;
    }
    counters(object(coverage, "line", s), 94, s);
    counters(object(coverage, "branch", s), 75, s);
  }

  private static void counters(JsonNode node, int minimum, State s) {
    if (node == null) return;
    fields(node, s, "covered", "missed");
    Long covered = number(node, "covered", s);
    Long missed = number(node, "missed", s);
    if (covered == null || missed == null) return;
    long total = covered + missed;
    if (total == 0) throw invalid();
    if (covered * 100 < total * minimum) s.incomplete = true;
  }

  private static void fields(JsonNode object, State s, String... expected) {
    if (!object.isObject()) throw invalid();
    Set<String> names = Set.of(expected);
    Iterator<String> actual = object.fieldNames();
    while (actual.hasNext()) if (!names.contains(actual.next())) throw invalid();
    for (String name : expected) if (!object.has(name)) s.incomplete = true;
  }

  private static JsonNode field(JsonNode object, String name, State s) {
    JsonNode value = object.get(name);
    if (value == null) { s.incomplete = true; return null; }
    if (value.isNull()) throw invalid();
    return value;
  }

  private static JsonNode object(JsonNode parent, String name, State s) {
    JsonNode value = field(parent, name, s);
    if (value != null && !value.isObject()) throw invalid();
    return value;
  }

  private static String string(JsonNode parent, String name, State s) {
    JsonNode value = field(parent, name, s);
    if (value == null) return null;
    if (!value.isTextual()) throw invalid();
    return value.textValue();
  }

  private static String choice(JsonNode parent, String name, State s, Set<String> allowed) {
    String value = string(parent, name, s);
    if (value != null && !allowed.contains(value)) throw invalid();
    return value;
  }

  private static void exact(JsonNode parent, String name, String expected, State s) {
    String value = string(parent, name, s);
    if (value != null && !value.equals(expected)) throw invalid();
  }

  private static Long number(JsonNode parent, String name, State s) {
    JsonNode value = field(parent, name, s);
    if (value == null) return null;
    if (!value.isIntegralNumber() || !value.canConvertToLong()) throw invalid();
    long number = value.longValue();
    if (number < 0 || number > MAX_COUNT) throw invalid();
    return number;
  }

  private static IllegalArgumentException invalid() { return new IllegalArgumentException(); }
  private static final class State { boolean incomplete; }
}
