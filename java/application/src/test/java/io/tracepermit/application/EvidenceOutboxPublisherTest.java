package io.tracepermit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tracepermit.execution.EvidenceAnchorReceipt;
import io.tracepermit.execution.EvidenceOutboxEntry;
import io.tracepermit.execution.EvidencePublicationState;
import io.tracepermit.execution.TenantPartition;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class EvidenceOutboxPublisherTest {
  private static final Instant NOW = Instant.parse("2026-10-10T09:30:00Z");
  private static final TenantPartition TENANT = new TenantPartition("acme");

  @Test
  void publishesOnlyAnExactlyBoundReceiptSignedByConfiguredExternalTrust() throws Exception {
    KeyPair anchor = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    RecordingState state = new RecordingState(entry("worker-1"));
    EvidenceOutboxPublisher worker =
        new EvidenceOutboxPublisher(
            state,
            item -> signedReceipt(item, anchor, "external-audit", "anchor-key-1"),
            new Ed25519EvidenceAnchorVerifier(
                Map.of("external-audit:anchor-key-1", anchor.getPublic())),
            Clock.fixed(NOW, ZoneOffset.UTC),
            "worker-1",
            Duration.ofSeconds(30),
            Duration.ofSeconds(10));

    EvidencePublicationResult result = worker.publishBatch(TENANT, 10);

    assertThat(result).isEqualTo(new EvidencePublicationResult(1, 1, 0));
    assertThat(state.published).hasSize(1);
    assertThat(state.released).isEmpty();
    EvidenceAnchorReceipt receipt = state.published.getFirst();
    assertThat(receipt.payloadHash()).isEqualTo(hash('a'));
    assertThat(receipt.toString())
        .doesNotContain("token", "credential", "argument", "target-result");
  }

  @Test
  void rejectsMutationWrongTrustAndExpiredLeasesWithoutMarkingPublished() throws Exception {
    KeyPair trusted = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    KeyPair wrong = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    RecordingState state = new RecordingState(entry("worker-1"));
    EvidenceOutboxPublisher worker =
        new EvidenceOutboxPublisher(
            state,
            item -> signedReceipt(item, wrong, "external-audit", "anchor-key-1"),
            new Ed25519EvidenceAnchorVerifier(
                Map.of("external-audit:anchor-key-1", trusted.getPublic())),
            Clock.fixed(NOW, ZoneOffset.UTC),
            "worker-1",
            Duration.ofSeconds(30),
            Duration.ofSeconds(10));

    assertThat(worker.publishBatch(TENANT, 1))
        .isEqualTo(new EvidencePublicationResult(1, 0, 1));
    assertThat(state.published).isEmpty();
    assertThat(state.released).containsExactly("ANCHOR_PUBLICATION_FAILED");

    RecordingState expired =
        new RecordingState(
            new EvidenceOutboxEntry(
                TENANT,
                "outbox-1",
                "execution",
                1,
                "evidence-1",
                hash('a'),
                NOW.minusSeconds(60),
                1,
                "worker-1",
                NOW));
    EvidenceOutboxPublisher expiredWorker =
        new EvidenceOutboxPublisher(
            expired,
            item -> signedReceipt(item, trusted, "external-audit", "anchor-key-1"),
            receipt -> {},
            Clock.fixed(NOW, ZoneOffset.UTC),
            "worker-1",
            Duration.ofSeconds(30),
            Duration.ofSeconds(10));
    assertThat(expiredWorker.publishBatch(TENANT, 1).failed()).isEqualTo(1);
    assertThat(expired.published).isEmpty();
  }

  @Test
  void validatesReceiptAndWorkerConfigurationConservatively() throws Exception {
    assertThatThrownBy(
            () ->
                new EvidenceOutboxPublisher(
                    new RecordingState(entry("worker-1")),
                    item -> null,
                    receipt -> {},
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    "bad worker",
                    Duration.ofSeconds(30),
                    Duration.ofSeconds(10)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new EvidenceAnchorReceipt(
                    "2.0",
                    TENANT,
                    "outbox-1",
                    "execution",
                    1,
                    "evidence-1",
                    hash('a'),
                    "external-audit",
                    "anchor-key-1",
                    "anchor-1",
                    NOW,
                    "Ed25519",
                    Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[64])))
        .isInstanceOf(IllegalArgumentException.class);

    KeyPair anchor = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    EvidenceAnchorReceipt receipt =
        signedReceipt(entry("worker-1"), anchor, "external-audit", "anchor-key-1");
    assertThatThrownBy(
            () ->
                new Ed25519EvidenceAnchorVerifier(
                        Map.of("different:key", anchor.getPublic()))
                    .verify(receipt))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not trusted");
  }

  private static EvidenceOutboxEntry entry(String workerId) {
    return new EvidenceOutboxEntry(
        TENANT,
        "outbox-1",
        "execution",
        1,
        "evidence-1",
        hash('a'),
        NOW.minusSeconds(1),
        1,
        workerId,
        NOW.plusSeconds(30));
  }

  private static EvidenceAnchorReceipt signedReceipt(
      EvidenceOutboxEntry entry, KeyPair keyPair, String provider, String keyId) {
    String placeholder = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[64]);
    EvidenceAnchorReceipt unsigned =
        new EvidenceAnchorReceipt(
            EvidenceAnchorReceipt.VERSION,
            entry.tenant(),
            entry.outboxId(),
            entry.streamId(),
            entry.sequence(),
            entry.evidenceEventId(),
            entry.payloadHash(),
            provider,
            keyId,
            "anchor-" + entry.outboxId(),
            NOW,
            "Ed25519",
            placeholder);
    try {
      Signature signer = Signature.getInstance("Ed25519");
      signer.initSign(keyPair.getPrivate());
      signer.update(unsigned.signingInput());
      return new EvidenceAnchorReceipt(
          unsigned.schemaVersion(),
          unsigned.tenant(),
          unsigned.outboxId(),
          unsigned.streamId(),
          unsigned.sequence(),
          unsigned.evidenceEventId(),
          unsigned.payloadHash(),
          unsigned.provider(),
          unsigned.keyId(),
          unsigned.anchorId(),
          unsigned.anchoredAt(),
          unsigned.signatureAlgorithm(),
          Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign()));
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static String hash(char value) {
    return "sha256:" + String.valueOf(value).repeat(64);
  }

  private static final class RecordingState implements EvidencePublicationState {
    private final List<EvidenceOutboxEntry> available;
    private final List<EvidenceAnchorReceipt> published = new ArrayList<>();
    private final List<String> released = new ArrayList<>();

    private RecordingState(EvidenceOutboxEntry entry) {
      available = List.of(entry);
    }

    @Override
    public List<EvidenceOutboxEntry> claimEvidenceBatch(
        TenantPartition tenant,
        String workerId,
        int maximumEntries,
        Instant claimedAt,
        Instant leaseExpiresAt) {
      return available;
    }

    @Override
    public void markEvidencePublished(
        EvidenceOutboxEntry entry,
        EvidenceAnchorReceipt receipt,
        String workerId,
        Instant publishedAt) {
      published.add(receipt);
    }

    @Override
    public void releaseEvidenceClaim(
        EvidenceOutboxEntry entry,
        String workerId,
        String failureCode,
        Instant nextAttemptAt) {
      released.add(failureCode);
    }

    @Override
    public List<EvidenceAnchorReceipt> readEvidenceAnchors(TenantPartition tenant) {
      return List.copyOf(published);
    }
  }
}
