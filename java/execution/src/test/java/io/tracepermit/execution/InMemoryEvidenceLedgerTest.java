package io.tracepermit.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.JsonSupport;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

final class InMemoryEvidenceLedgerTest {
  private static final Instant NOW = Instant.parse("2026-10-08T10:15:31Z");

  @Test
  void appendCreatesAnOrderedVerifiableChainWithDefensiveCopies() {
    InMemoryEvidenceLedger ledger = ledger();
    ObjectNode input = JsonSupport.MAPPER.createObjectNode().put("eventType", "policy.evaluated");
    JsonNode first = ledger.append(input, NOW);
    input.put("eventType", "tampered-at-source");
    ((ObjectNode) first.path("payload")).put("eventType", "tampered-at-caller");
    ledger.append(
        JsonSupport.MAPPER.createObjectNode().put("eventType", "target.succeeded"),
        NOW.plusSeconds(1));

    var records = ledger.readAll();
    assertThat(records).hasSize(2);
    assertThat(records.getFirst().path("payload").path("eventType").asText())
        .isEqualTo("policy.evaluated");
    assertThat(records.get(1).path("integrity").path("previousEventHash").asText())
        .isEqualTo(records.getFirst().path("integrity").path("eventHash").asText());
    assertThat(ledger.verify(records.get(1).path("integrity").path("eventHash").asText()).valid())
        .isTrue();
  }

  @Test
  void verifierDistinguishesSequenceLinkPayloadAndAnchorFailures() {
    InMemoryEvidenceLedger ledger = ledger();
    ledger.append(JsonSupport.MAPPER.createObjectNode().put("eventType", "one"), NOW);
    ledger.append(JsonSupport.MAPPER.createObjectNode().put("eventType", "two"), NOW.plusSeconds(1));
    var records = ledger.readAll();
    String head = records.get(1).path("integrity").path("eventHash").asText();

    var badSequence = mutableCopy(records);
    ((ObjectNode) badSequence.getFirst().path("payload")).put("sequence", 9);
    assertThat(InMemoryEvidenceLedger.verifyRecords(badSequence, head).issueCode())
        .isEqualTo("INVALID_SEQUENCE");

    var badLink = mutableCopy(records);
    ((ObjectNode) badLink.get(1).path("integrity")).put("previousEventHash", "sha256:" + "0".repeat(64));
    assertThat(InMemoryEvidenceLedger.verifyRecords(badLink, head).issueCode())
        .isEqualTo("BROKEN_LINK");

    var badPayload = mutableCopy(records);
    ((ObjectNode) badPayload.getFirst().path("payload")).put("eventType", "changed");
    assertThat(InMemoryEvidenceLedger.verifyRecords(badPayload, head).issueCode())
        .isEqualTo("HASH_MISMATCH");

    assertThat(ledger.verify("sha256:" + "f".repeat(64)).issueCode())
        .isEqualTo("ANCHOR_MISMATCH");
    assertThat(ledger.verify(head).valid()).isTrue();
  }

  private static InMemoryEvidenceLedger ledger() {
    AtomicInteger ids = new AtomicInteger();
    return new InMemoryEvidenceLedger(
        "urn:tracepermit:test", Clock.fixed(NOW, ZoneOffset.UTC),
        () -> "evidence-" + ids.incrementAndGet());
  }

  private static ArrayList<JsonNode> mutableCopy(java.util.List<JsonNode> records) {
    ArrayList<JsonNode> copy = new ArrayList<>();
    records.forEach(record -> copy.add(record.deepCopy()));
    return copy;
  }
}
