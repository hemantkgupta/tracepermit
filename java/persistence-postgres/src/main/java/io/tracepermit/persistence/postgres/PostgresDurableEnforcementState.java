package io.tracepermit.persistence.postgres;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.contracts.ActionValidator;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.execution.ApprovalBinding;
import io.tracepermit.execution.ApprovalConsumptionResult;
import io.tracepermit.execution.ApprovalConsumptionStatus;
import io.tracepermit.execution.ApprovalDecision;
import io.tracepermit.execution.ApprovalExecutionClaim;
import io.tracepermit.execution.ApprovalPolicyBinding;
import io.tracepermit.execution.ApprovalRequestState;
import io.tracepermit.execution.DurableClaimResult;
import io.tracepermit.execution.DurableClaimStatus;
import io.tracepermit.execution.DurableApprovalActionState;
import io.tracepermit.execution.DurablePolicyBoundApprovalState;
import io.tracepermit.execution.DurableEnforcementState;
import io.tracepermit.execution.DurableEvidenceRecord;
import io.tracepermit.execution.DurableExecutionClaim;
import io.tracepermit.execution.DurableReconciliationState;
import io.tracepermit.execution.EvidenceAnchorReceipt;
import io.tracepermit.execution.EvidenceOutboxEntry;
import io.tracepermit.execution.EvidencePublicationState;
import io.tracepermit.execution.ExecutionAttempt;
import io.tracepermit.execution.ExecutionTransition;
import io.tracepermit.execution.InMemoryEvidenceLedger;
import io.tracepermit.execution.ReconciliationCandidate;
import io.tracepermit.execution.TenantPartition;
import org.flywaydb.core.Flyway;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** PostgreSQL implementation of the durable execution and evidence transaction port. */
public final class PostgresDurableEnforcementState
    implements DurableEnforcementState,
        DurablePolicyBoundApprovalState,
        EvidencePublicationState,
        DurableReconciliationState {
  public static final String MIGRATION_LOCATION = "classpath:db/migration";
  private static final String STREAM_ID = "execution";
  private static final String PRODUCER = "tracepermit-java-durable-enforcement";

  private final DataSource dataSource;
  private final Clock clock;
  private final Supplier<String> createId;

  public PostgresDurableEnforcementState(DataSource dataSource, Clock clock, Supplier<String> createId) {
    this(dataSource, clock, createId, true);
  }

  /** Operator runtime path: validate existing state without implicit schema migration. */
  public static PostgresDurableEnforcementState openExisting(
      DataSource dataSource, Clock clock, Supplier<String> createId) {
    return new PostgresDurableEnforcementState(dataSource, clock, createId, false);
  }

  private PostgresDurableEnforcementState(
      DataSource dataSource, Clock clock, Supplier<String> createId, boolean migrateSchema) {
    this.dataSource = Objects.requireNonNull(dataSource);
    this.clock = Objects.requireNonNull(clock);
    this.createId = Objects.requireNonNull(createId);
    if (migrateSchema) migrate(dataSource, MIGRATION_LOCATION);
    else PostgresDatabaseLifecycle.validateExisting(dataSource);
    verifySchemaVersion();
  }

  public static void migrate(DataSource dataSource, String location) {
    Objects.requireNonNull(dataSource);
    Objects.requireNonNull(location);
    Flyway.configure()
        .dataSource(dataSource)
        .locations(location)
        .validateMigrationNaming(true)
        .load()
        .migrate();
  }

  @Override
  public DurableClaimResult claim(DurableExecutionClaim claim) {
    Objects.requireNonNull(claim);
    try {
      return inTransaction(connection -> insertClaim(connection, claim));
    } catch (PostgresStateException failure) {
      if (!hasSqlState(failure, "23505")) throw failure;
      DurableClaimResult conflict = resolveClaimConflict(claim);
      if (conflict == null) throw failure;
      return conflict;
    }
  }

  @Override
  public ExecutionAttempt complete(
      TenantPartition tenant,
      String attemptId,
      String outcome,
      String reasonCode,
      Instant occurredAt) {
    Objects.requireNonNull(tenant);
    requireIdentifier(attemptId, 256, "attempt ID");
    requireReason(reasonCode);
    Objects.requireNonNull(occurredAt);
    if (!List.of("succeeded", "failed", "unknown").contains(outcome)) {
      throw new IllegalArgumentException("The execution outcome is invalid.");
    }
    return inTransaction(connection -> {
      AttemptMetadata metadata = requireAttempt(connection, tenant, attemptId, true);
      if (!"executing".equals(metadata.status())) {
        throw new IllegalStateException("Execution attempt is " + metadata.status() + ", not executing.");
      }
      appendTransition(connection, metadata, outcome, reasonCode, occurredAt);
      String kind = switch (outcome) {
        case "succeeded" -> "target.succeeded";
        case "failed" -> "target.denied";
        case "unknown" -> "target.outcome_unknown";
        default -> throw new IllegalStateException("Unreachable execution outcome.");
      };
      appendEvidence(connection, metadata, kind, outcome, reasonCode, occurredAt);
      if ("unknown".equals(outcome)) {
        enqueueReconciliation(connection, metadata, occurredAt);
      }
      return requireAttemptSnapshot(connection, tenant, attemptId);
    });
  }

  @Override
  public ExecutionAttempt reconcile(
      TenantPartition tenant,
      String adapterId,
      String idempotencyKey,
      String outcome,
      String reasonCode,
      Instant occurredAt) {
    Objects.requireNonNull(tenant);
    requireIdentifier(adapterId, 128, "adapter ID");
    requireHash(idempotencyKey, "idempotency key");
    requireReason(reasonCode);
    Objects.requireNonNull(occurredAt);
    if (!List.of("succeeded", "failed").contains(outcome)) {
      throw new IllegalArgumentException("Reconciliation outcome must be succeeded or failed.");
    }
    return inTransaction(
        connection -> {
          lockReconciliationQueueByKey(connection, tenant, adapterId, idempotencyKey);
          AttemptMetadata metadata =
              requireAttemptByKey(connection, tenant, adapterId, idempotencyKey, true);
          ExecutionAttempt reconciled =
              reconcileLocked(connection, metadata, outcome, reasonCode, occurredAt);
          deleteReconciliationQueue(connection, tenant, metadata.attemptId());
          return reconciled;
        });
  }

  @Override
  public ExecutionAttempt findByIdempotencyKey(
      TenantPartition tenant, String adapterId, String idempotencyKey) {
    Objects.requireNonNull(tenant);
    requireIdentifier(adapterId, 128, "adapter ID");
    requireHash(idempotencyKey, "idempotency key");
    try (Connection connection = dataSource.getConnection()) {
      AttemptMetadata metadata = findAttemptByKey(connection, tenant, adapterId, idempotencyKey, false);
      return metadata == null ? null : requireAttemptSnapshot(connection, tenant, metadata.attemptId());
    } catch (SQLException exception) {
      throw databaseFailure(exception);
    }
  }

  @Override
  public List<DurableEvidenceRecord> readEvidence(TenantPartition tenant) {
    Objects.requireNonNull(tenant);
    String sql = """
        SELECT stream_id, sequence, evidence_event_id, payload::text,
               previous_event_hash, event_hash
          FROM tp_evidence_events
         WHERE tenant_id = ?
         ORDER BY stream_id, sequence
        """;
    try (Connection connection = dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant.value());
      try (ResultSet results = statement.executeQuery()) {
        List<DurableEvidenceRecord> records = new ArrayList<>();
        while (results.next()) {
          records.add(new DurableEvidenceRecord(
              tenant,
              results.getString("stream_id"),
              results.getLong("sequence"),
              results.getString("evidence_event_id"),
              parseJson(results.getString("payload")),
              results.getString("previous_event_hash"),
              results.getString("event_hash")));
        }
        return List.copyOf(records);
      }
    } catch (SQLException exception) {
      throw databaseFailure(exception);
    }
  }

  @Override
  public List<EvidenceOutboxEntry> claimEvidenceBatch(
      TenantPartition tenant,
      String workerId,
      int maximumEntries,
      Instant claimedAt,
      Instant leaseExpiresAt) {
    Objects.requireNonNull(tenant);
    requireIdentifier(workerId, 128, "worker ID");
    if (maximumEntries < 1 || maximumEntries > 100) {
      throw new IllegalArgumentException("The evidence publication batch size is invalid.");
    }
    Objects.requireNonNull(claimedAt);
    Objects.requireNonNull(leaseExpiresAt);
    if (!leaseExpiresAt.isAfter(claimedAt)
        || leaseExpiresAt.isAfter(claimedAt.plusSeconds(300))) {
      throw new IllegalArgumentException("The evidence publication lease is invalid.");
    }
    return inTransaction(
        connection -> {
          String sql = """
              WITH candidates AS (
                SELECT tenant_id, outbox_id
                  FROM tp_evidence_outbox
                 WHERE tenant_id = ?
                   AND published_at IS NULL
                   AND next_attempt_at <= ?
                   AND (lease_expires_at IS NULL OR lease_expires_at <= ?)
                 ORDER BY stream_id, sequence
                 FOR UPDATE SKIP LOCKED
                 LIMIT ?
              )
              UPDATE tp_evidence_outbox AS outbox
                 SET lease_owner = ?, lease_expires_at = ?,
                     publication_attempts = publication_attempts + 1,
                     last_failure_code = NULL
                FROM candidates
               WHERE outbox.tenant_id = candidates.tenant_id
                 AND outbox.outbox_id = candidates.outbox_id
              RETURNING outbox.outbox_id, outbox.stream_id, outbox.sequence,
                        outbox.evidence_event_id, outbox.payload_hash, outbox.created_at,
                        outbox.publication_attempts, outbox.lease_owner,
                        outbox.lease_expires_at
              """;
          try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenant.value());
            statement.setObject(2, databaseTime(claimedAt));
            statement.setObject(3, databaseTime(claimedAt));
            statement.setInt(4, maximumEntries);
            statement.setString(5, workerId);
            statement.setObject(6, databaseTime(leaseExpiresAt));
            try (ResultSet result = statement.executeQuery()) {
              List<EvidenceOutboxEntry> entries = new ArrayList<>();
              while (result.next()) {
                entries.add(
                    new EvidenceOutboxEntry(
                        tenant,
                        result.getString("outbox_id"),
                        result.getString("stream_id"),
                        result.getLong("sequence"),
                        result.getString("evidence_event_id"),
                        result.getString("payload_hash"),
                        result.getObject("created_at", OffsetDateTime.class).toInstant(),
                        result.getInt("publication_attempts"),
                        result.getString("lease_owner"),
                        result.getObject("lease_expires_at", OffsetDateTime.class).toInstant()));
              }
              entries.sort(
                  java.util.Comparator.comparing(EvidenceOutboxEntry::streamId)
                      .thenComparingLong(EvidenceOutboxEntry::sequence));
              return List.copyOf(entries);
            }
          }
        });
  }

  @Override
  public void markEvidencePublished(
      EvidenceOutboxEntry entry,
      EvidenceAnchorReceipt receipt,
      String workerId,
      Instant publishedAt) {
    Objects.requireNonNull(entry);
    Objects.requireNonNull(receipt);
    requireIdentifier(workerId, 128, "worker ID");
    Objects.requireNonNull(publishedAt);
    receipt.requireBinding(entry);
    if (receipt.anchoredAt().isBefore(entry.createdAt())
        || receipt.anchoredAt().isAfter(publishedAt)) {
      throw new IllegalArgumentException("The evidence anchor time is invalid.");
    }
    inTransaction(
        connection -> {
          String lockSql = """
              SELECT stream_id, sequence, evidence_event_id, payload_hash, created_at,
                     lease_owner, lease_expires_at, published_at
                FROM tp_evidence_outbox
               WHERE tenant_id = ? AND outbox_id = ?
               FOR UPDATE
              """;
          try (PreparedStatement lock = connection.prepareStatement(lockSql)) {
            lock.setString(1, entry.tenant().value());
            lock.setString(2, entry.outboxId());
            try (ResultSet result = lock.executeQuery()) {
              if (!result.next()) {
                throw new IllegalArgumentException("The evidence outbox entry does not exist.");
              }
              OffsetDateTime leaseExpiry = result.getObject("lease_expires_at", OffsetDateTime.class);
              if (!entry.streamId().equals(result.getString("stream_id"))
                  || entry.sequence() != result.getLong("sequence")
                  || !entry.evidenceEventId().equals(result.getString("evidence_event_id"))
                  || !entry.payloadHash().equals(result.getString("payload_hash"))
                  || !entry.createdAt().equals(
                      result.getObject("created_at", OffsetDateTime.class).toInstant())) {
                throw new IllegalArgumentException("The evidence outbox binding does not match.");
              }
              if (result.getObject("published_at") != null
                  || !workerId.equals(result.getString("lease_owner"))
                  || leaseExpiry == null
                  || !leaseExpiry.toInstant().isAfter(publishedAt)) {
                throw new IllegalStateException("The evidence publication lease is not active.");
              }
            }
          }

          try (PreparedStatement anchor = connection.prepareStatement("""
              INSERT INTO tp_evidence_anchors
                (tenant_id, anchor_id, outbox_id, schema_version, stream_id, sequence,
                 evidence_event_id, payload_hash, provider, key_id, anchored_at,
                 signature_algorithm, signature, stored_at)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
              """)) {
            anchor.setString(1, entry.tenant().value());
            anchor.setString(2, receipt.anchorId());
            anchor.setString(3, entry.outboxId());
            anchor.setString(4, receipt.schemaVersion());
            anchor.setString(5, entry.streamId());
            anchor.setLong(6, entry.sequence());
            anchor.setString(7, entry.evidenceEventId());
            anchor.setString(8, entry.payloadHash());
            anchor.setString(9, receipt.provider());
            anchor.setString(10, receipt.keyId());
            anchor.setObject(11, databaseTime(receipt.anchoredAt()));
            anchor.setString(12, receipt.signatureAlgorithm());
            anchor.setString(13, receipt.signature());
            anchor.setObject(14, databaseTime(publishedAt));
            anchor.executeUpdate();
          }
          try (PreparedStatement update = connection.prepareStatement("""
              UPDATE tp_evidence_outbox
                 SET published_at = ?, lease_owner = NULL, lease_expires_at = NULL,
                     last_failure_code = NULL
               WHERE tenant_id = ? AND outbox_id = ?
              """)) {
            update.setObject(1, databaseTime(publishedAt));
            update.setString(2, entry.tenant().value());
            update.setString(3, entry.outboxId());
            if (update.executeUpdate() != 1) {
              throw new SQLException("Evidence outbox publication did not update exactly one row.");
            }
          }
          return null;
        });
  }

  @Override
  public void releaseEvidenceClaim(
      EvidenceOutboxEntry entry,
      String workerId,
      String failureCode,
      Instant nextAttemptAt) {
    Objects.requireNonNull(entry);
    requireIdentifier(workerId, 128, "worker ID");
    requireReason(failureCode);
    Objects.requireNonNull(nextAttemptAt);
    if (nextAttemptAt.isBefore(entry.createdAt())) {
      throw new IllegalArgumentException("The next evidence publication attempt is invalid.");
    }
    inTransaction(
        connection -> {
          try (PreparedStatement statement = connection.prepareStatement("""
              UPDATE tp_evidence_outbox
                 SET lease_owner = NULL, lease_expires_at = NULL,
                     next_attempt_at = ?, last_failure_code = ?
               WHERE tenant_id = ? AND outbox_id = ?
                 AND published_at IS NULL AND lease_owner = ?
              """)) {
            statement.setObject(1, databaseTime(nextAttemptAt));
            statement.setString(2, failureCode);
            statement.setString(3, entry.tenant().value());
            statement.setString(4, entry.outboxId());
            statement.setString(5, workerId);
            if (statement.executeUpdate() != 1) {
              throw new IllegalStateException("The evidence publication lease is not active.");
            }
          }
          return null;
        });
  }

  @Override
  public List<EvidenceAnchorReceipt> readEvidenceAnchors(TenantPartition tenant) {
    Objects.requireNonNull(tenant);
    String sql = """
        SELECT schema_version, outbox_id, stream_id, sequence, evidence_event_id,
               payload_hash, provider, key_id, anchor_id, anchored_at,
               signature_algorithm, signature
          FROM tp_evidence_anchors
         WHERE tenant_id = ?
         ORDER BY stream_id, sequence
        """;
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant.value());
      try (ResultSet result = statement.executeQuery()) {
        List<EvidenceAnchorReceipt> receipts = new ArrayList<>();
        while (result.next()) {
          receipts.add(
              new EvidenceAnchorReceipt(
                  result.getString("schema_version"),
                  tenant,
                  result.getString("outbox_id"),
                  result.getString("stream_id"),
                  result.getLong("sequence"),
                  result.getString("evidence_event_id"),
                  result.getString("payload_hash"),
                  result.getString("provider"),
                  result.getString("key_id"),
                  result.getString("anchor_id"),
                  result.getObject("anchored_at", OffsetDateTime.class).toInstant(),
                  result.getString("signature_algorithm"),
                  result.getString("signature")));
        }
        return List.copyOf(receipts);
      }
    } catch (SQLException exception) {
      throw databaseFailure(exception);
    }
  }

  @Override
  public List<ReconciliationCandidate> claimUnknownBatch(
      TenantPartition tenant,
      String workerId,
      int maximumEntries,
      Instant claimedAt,
      Instant leaseExpiresAt) {
    Objects.requireNonNull(tenant);
    requireIdentifier(workerId, 128, "worker ID");
    if (maximumEntries < 1 || maximumEntries > 100) {
      throw new IllegalArgumentException("The reconciliation batch size is invalid.");
    }
    Objects.requireNonNull(claimedAt);
    Objects.requireNonNull(leaseExpiresAt);
    if (!leaseExpiresAt.isAfter(claimedAt)
        || leaseExpiresAt.isAfter(claimedAt.plusSeconds(300))) {
      throw new IllegalArgumentException("The reconciliation lease is invalid.");
    }
    return inTransaction(
        connection -> {
          String sql = """
              WITH candidates AS (
                SELECT tenant_id, attempt_id
                  FROM tp_reconciliation_queue
                 WHERE tenant_id = ?
                   AND next_attempt_at <= ?
                   AND (lease_expires_at IS NULL OR lease_expires_at <= ?)
                 ORDER BY unknown_since, attempt_id
                 FOR UPDATE SKIP LOCKED
                 LIMIT ?
              )
              UPDATE tp_reconciliation_queue AS queue
                 SET lease_owner = ?, lease_expires_at = ?,
                     lookup_attempts = lookup_attempts + 1,
                     last_reason_code = NULL
                FROM candidates
               WHERE queue.tenant_id = candidates.tenant_id
                 AND queue.attempt_id = candidates.attempt_id
              RETURNING queue.attempt_id, queue.adapter_id, queue.idempotency_key,
                        queue.unknown_since, queue.lookup_attempts, queue.lease_owner,
                        queue.lease_expires_at
              """;
          try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenant.value());
            statement.setObject(2, databaseTime(claimedAt));
            statement.setObject(3, databaseTime(claimedAt));
            statement.setInt(4, maximumEntries);
            statement.setString(5, workerId);
            statement.setObject(6, databaseTime(leaseExpiresAt));
            try (ResultSet result = statement.executeQuery()) {
              List<ReconciliationCandidate> candidates = new ArrayList<>();
              while (result.next()) {
                candidates.add(
                    new ReconciliationCandidate(
                        tenant,
                        result.getString("attempt_id"),
                        result.getString("adapter_id"),
                        result.getString("idempotency_key"),
                        result.getObject("unknown_since", OffsetDateTime.class).toInstant(),
                        result.getInt("lookup_attempts"),
                        result.getString("lease_owner"),
                        result.getObject("lease_expires_at", OffsetDateTime.class).toInstant()));
              }
              candidates.sort(
                  java.util.Comparator.comparing(ReconciliationCandidate::unknownSince)
                      .thenComparing(ReconciliationCandidate::attemptId));
              return List.copyOf(candidates);
            }
          }
        });
  }

  @Override
  public ExecutionAttempt completeReconciliation(
      ReconciliationCandidate candidate,
      String workerId,
      String outcome,
      String reasonCode,
      Instant occurredAt) {
    Objects.requireNonNull(candidate);
    requireIdentifier(workerId, 128, "worker ID");
    if (!List.of("succeeded", "failed").contains(outcome)) {
      throw new IllegalArgumentException("Reconciliation outcome must be succeeded or failed.");
    }
    requireReason(reasonCode);
    Objects.requireNonNull(occurredAt);
    return inTransaction(
        connection -> {
          requireReconciliationLease(connection, candidate, workerId, occurredAt);
          AttemptMetadata metadata =
              requireAttempt(connection, candidate.tenant(), candidate.attemptId(), true);
          if (!metadata.adapterId().equals(candidate.adapterId())
              || !metadata.idempotencyKey().equals(candidate.idempotencyKey())) {
            throw new IllegalArgumentException("The reconciliation binding does not match.");
          }
          ExecutionAttempt reconciled =
              reconcileLocked(connection, metadata, outcome, reasonCode, occurredAt);
          deleteReconciliationQueue(connection, candidate.tenant(), candidate.attemptId());
          return reconciled;
        });
  }

  @Override
  public void deferReconciliation(
      ReconciliationCandidate candidate,
      String workerId,
      String reasonCode,
      Instant nextAttemptAt) {
    Objects.requireNonNull(candidate);
    requireIdentifier(workerId, 128, "worker ID");
    requireReason(reasonCode);
    Objects.requireNonNull(nextAttemptAt);
    if (nextAttemptAt.isBefore(candidate.unknownSince())) {
      throw new IllegalArgumentException("The next reconciliation attempt is invalid.");
    }
    inTransaction(
        connection -> {
          try (PreparedStatement statement = connection.prepareStatement("""
              UPDATE tp_reconciliation_queue
                 SET lease_owner = NULL, lease_expires_at = NULL,
                     next_attempt_at = ?, last_reason_code = ?
               WHERE tenant_id = ? AND attempt_id = ? AND lease_owner = ?
              """)) {
            statement.setObject(1, databaseTime(nextAttemptAt));
            statement.setString(2, reasonCode);
            statement.setString(3, candidate.tenant().value());
            statement.setString(4, candidate.attemptId());
            statement.setString(5, workerId);
            if (statement.executeUpdate() != 1) {
              throw new IllegalStateException("The reconciliation lease is not active.");
            }
          }
          return null;
        });
  }

  @Override
  public ApprovalRequestState createPending(ApprovalBinding binding) {
    return createPendingInternal(binding, null, null);
  }

  @Override
  public ApprovalRequestState createPending(ApprovalBinding binding, JsonNode canonicalAction) {
    JsonNode event = ActionValidator.validate(canonicalAction).deepCopy();
    if (!binding.actionHash().equals(CanonicalJson.sha256(event))) {
      throw new IllegalArgumentException("APPROVAL_BINDING_MISMATCH");
    }
    return createPendingInternal(binding, event, null);
  }

  @Override
  public ApprovalRequestState createPendingBound(
      ApprovalBinding binding,
      JsonNode canonicalAction,
      ApprovalPolicyBinding policyBinding) {
    Objects.requireNonNull(binding);
    Objects.requireNonNull(policyBinding);
    if (!binding.policyId().equals(policyBinding.policyId())
        || binding.policyVersion() != policyBinding.policyVersion()) {
      throw new IllegalArgumentException("APPROVAL_BINDING_MISMATCH");
    }
    if (canonicalAction != null) {
      JsonNode event = ActionValidator.validate(canonicalAction).deepCopy();
      if (!binding.actionHash().equals(CanonicalJson.sha256(event))) {
        throw new IllegalArgumentException("APPROVAL_BINDING_MISMATCH");
      }
      return createPendingInternal(binding, event, policyBinding);
    }
    return createPendingInternal(binding, null, policyBinding);
  }

  private ApprovalRequestState createPendingInternal(
      ApprovalBinding binding, JsonNode event, ApprovalPolicyBinding policyBinding) {
    Objects.requireNonNull(binding);
    return inTransaction(connection -> {
      String sql = """
          INSERT INTO tp_approval_requests
            (tenant_id, approval_request_id, request_hash, action_hash, event_id,
             evaluation_id, subject_id, actor_lineage_hash, policy_id, policy_version,
             original_decision, tool_name, resource_hash, approval_group, created_at,
             expires_at, max_uses, status, transition_sequence, updated_at)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending', 1, ?)
          """;
      try (PreparedStatement request = connection.prepareStatement(sql)) {
        bindApprovalBinding(request, binding);
        request.setObject(18, databaseTime(binding.createdAt()));
        request.executeUpdate();
      }
      try (PreparedStatement transition = connection.prepareStatement("""
          INSERT INTO tp_approval_transitions
            (tenant_id, approval_request_id, sequence, from_status, to_status,
             reason_code, occurred_at)
          VALUES (?, ?, 1, NULL, 'pending', 'APPROVAL_REQUESTED', ?)
          """)) {
        transition.setString(1, binding.tenant().value());
        transition.setString(2, binding.approvalRequestId());
        transition.setObject(3, databaseTime(binding.createdAt()));
        transition.executeUpdate();
      }
      if (event != null) {
        try (PreparedStatement snapshot = connection.prepareStatement("""
            INSERT INTO tp_approval_action_snapshots
              (tenant_id, approval_request_id, canonical_action) VALUES (?, ?, ?::jsonb)
            """)) {
          snapshot.setString(1, binding.tenant().value());
          snapshot.setString(2, binding.approvalRequestId());
          snapshot.setString(3, toJson(event));
          snapshot.executeUpdate();
        }
      }
      if (policyBinding != null) {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO tp_approval_policy_bindings (
              tenant_id, approval_request_id, workspace_id, policy_id, policy_version,
              policy_generation, policy_content_hash, bound_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
          statement.setString(1, binding.tenant().value());
          statement.setString(2, binding.approvalRequestId());
          statement.setString(3, policyBinding.workspaceId());
          statement.setString(4, policyBinding.policyId());
          statement.setInt(5, policyBinding.policyVersion());
          statement.setLong(6, policyBinding.policyGeneration());
          statement.setString(7, policyBinding.policyContentHash());
          statement.setObject(8, databaseTime(policyBinding.boundAt()));
          statement.executeUpdate();
        }
      }
      ApprovalRequestState created = requireApproval(
          connection, binding.tenant(), binding.approvalRequestId(), false);
      appendApprovalEvidence(
          connection, created, "approval.requested", "APPROVAL_REQUESTED", binding.createdAt(), null, null);
      return created;
    });
  }

  @Override
  public ApprovalRequestState find(TenantPartition tenant, String approvalRequestId) {
    Objects.requireNonNull(tenant);
    requireIdentifier(approvalRequestId, 256, "approval request ID");
    try (Connection connection = dataSource.getConnection()) {
      return findApproval(connection, tenant, approvalRequestId, false);
    } catch (SQLException exception) {
      throw databaseFailure(exception);
    }
  }

  @Override
  public JsonNode findAction(TenantPartition tenant, String approvalRequestId) {
    Objects.requireNonNull(tenant);
    requireIdentifier(approvalRequestId, 256, "approval request ID");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement("""
            SELECT canonical_action::text FROM tp_approval_action_snapshots
            WHERE tenant_id = ? AND approval_request_id = ?
            """)) {
      statement.setString(1, tenant.value());
      statement.setString(2, approvalRequestId);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? parseJson(result.getString(1)) : null;
      }
    } catch (SQLException failure) {
      throw databaseFailure(failure);
    }
  }

  @Override
  public Optional<ApprovalPolicyBinding> findPolicyBinding(TenantPartition tenant, String approvalRequestId) {
    Objects.requireNonNull(tenant);
    requireIdentifier(approvalRequestId, 256, "approval request ID");
    try (Connection connection = dataSource.getConnection()) {
      return findPolicyBinding(connection, tenant, approvalRequestId);
    } catch (SQLException failure) {
      throw databaseFailure(failure);
    }
  }

  private Optional<ApprovalPolicyBinding> findPolicyBinding(
      Connection connection, TenantPartition tenant, String approvalRequestId) throws SQLException {
    String sql = """
        SELECT workspace_id, policy_id, policy_version, policy_generation,
               policy_content_hash, bound_at
          FROM tp_approval_policy_bindings
         WHERE tenant_id = ? AND approval_request_id = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant.value());
      statement.setString(2, approvalRequestId);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) return Optional.empty();
        return Optional.of(new ApprovalPolicyBinding(
            result.getString("workspace_id"),
            result.getString("policy_id"),
            result.getInt("policy_version"),
            result.getLong("policy_generation"),
            result.getString("policy_content_hash"),
            result.getObject("bound_at", OffsetDateTime.class).toInstant()));
      }
    }
  }

  @Override
  public List<ApprovalRequestState> listApprovals(
      TenantPartition tenant, String requesterSubjectId, int limit) {
    Objects.requireNonNull(tenant);
    if (requesterSubjectId != null) {
      if (!requesterSubjectId.matches("^[A-Za-z0-9][A-Za-z0-9._:@/+\\-#]{0,255}$")) {
        throw new IllegalArgumentException("Invalid requester subject ID.");
      }
    }
    if (limit < 1 || limit > 50) throw new IllegalArgumentException("Invalid approval page limit.");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement("""
            SELECT r.approval_request_id FROM tp_approval_requests r
            JOIN tp_approval_action_snapshots s USING (tenant_id, approval_request_id)
            WHERE r.tenant_id = ? AND (?::text IS NULL OR r.subject_id = ?)
            ORDER BY r.created_at DESC, r.approval_request_id LIMIT ?
            """)) {
      statement.setString(1, tenant.value());
      statement.setString(2, requesterSubjectId);
      statement.setString(3, requesterSubjectId);
      statement.setInt(4, limit);
      List<ApprovalRequestState> approvals = new ArrayList<>();
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          approvals.add(requireApproval(connection, tenant, result.getString(1), false));
        }
      }
      return List.copyOf(approvals);
    } catch (SQLException failure) {
      throw databaseFailure(failure);
    }
  }

  @Override
  public ApprovalRequestState decide(ApprovalDecision decision) {
    Objects.requireNonNull(decision);
    return inTransaction(connection -> {
      ApprovalRequestState current = requireApproval(
          connection, decision.tenant(), decision.approvalRequestId(), true);
      if (!"pending".equals(current.status())) {
        throw new IllegalStateException("APPROVAL_ALREADY_DECIDED");
      }
      if (!current.binding().expiresAt().isAfter(decision.decidedAt())) {
        appendApprovalTransition(
            connection, current, "expired", "APPROVAL_EXPIRED", decision.decidedAt(), null, null, null);
        ApprovalRequestState expired = requireApproval(
            connection, decision.tenant(), decision.approvalRequestId(), false);
        appendApprovalEvidence(
            connection, expired, "approval.expired", "APPROVAL_EXPIRED", decision.decidedAt(), null, null);
        return expired;
      }
      String status = "approve".equals(decision.decision()) ? "approved" : "denied";
      appendApprovalTransition(
          connection,
          current,
          status,
          decision.reasonCode(),
          decision.decidedAt(),
          decision,
          null,
          null);
      ApprovalRequestState updated = requireApproval(
          connection, decision.tenant(), decision.approvalRequestId(), false);
      appendApprovalEvidence(
          connection,
          updated,
          "approved".equals(status) ? "approval.approved" : "approval.denied",
          decision.reasonCode(),
          decision.decidedAt(),
          null,
          null);
      return updated;
    });
  }

  @Override
  public ApprovalConsumptionResult consumeAndClaim(ApprovalExecutionClaim claim) {
    Objects.requireNonNull(claim);
    try {
      return inTransaction(connection -> consumeAndClaim(connection, claim));
    } catch (PostgresStateException failure) {
      if (!hasSqlState(failure, "23505")) throw failure;
      ApprovalRequestState approval = find(
          claim.binding().tenant(), claim.binding().approvalRequestId());
      if (approval != null && "consumed".equals(approval.status())) {
        return new ApprovalConsumptionResult(
            ApprovalConsumptionStatus.APPROVAL_REPLAYED,
            approval,
            findAttemptSnapshot(
                claim.binding().tenant(), approval.consumedAttemptId()));
      }
      DurableClaimResult conflict = resolveClaimConflict(claim.executionClaim());
      if (approval == null || conflict == null) throw failure;
      ApprovalConsumptionStatus status = conflict.status() == DurableClaimStatus.IDEMPOTENCY_DUPLICATE
          ? ApprovalConsumptionStatus.IDEMPOTENCY_DUPLICATE
          : ApprovalConsumptionStatus.APPROVAL_REPLAYED;
      return new ApprovalConsumptionResult(status, approval, conflict.attempt());
    }
  }

  private ApprovalConsumptionResult consumeAndClaim(
      Connection connection, ApprovalExecutionClaim claim) throws SQLException {
    ApprovalRequestState approval = requireApproval(
        connection, claim.binding().tenant(), claim.binding().approvalRequestId(), true);
    if ("consumed".equals(approval.status())) {
      return new ApprovalConsumptionResult(
          ApprovalConsumptionStatus.APPROVAL_REPLAYED,
          approval,
          requireAttemptSnapshot(
              connection, claim.binding().tenant(), approval.consumedAttemptId()));
    }
    if ("approved".equals(approval.status())
        && !approval.binding().expiresAt().isAfter(claim.occurredAt())) {
      appendApprovalTransition(
          connection, approval, "expired", "APPROVAL_EXPIRED", claim.occurredAt(), null, null, null);
      ApprovalRequestState expired = requireApproval(
          connection, claim.binding().tenant(), claim.binding().approvalRequestId(), false);
      appendApprovalEvidence(
          connection, expired, "approval.expired", "APPROVAL_EXPIRED", claim.occurredAt(), null, null);
      return new ApprovalConsumptionResult(
          ApprovalConsumptionStatus.APPROVAL_EXPIRED, expired, null);
    }
    if (!"approved".equals(approval.status())) {
      throw new IllegalStateException("APPROVAL_NOT_EXECUTABLE");
    }
    if (!approval.binding().equals(claim.binding())
        || !Objects.equals(approval.approvalDecisionId(), claim.approvalDecisionId())) {
      throw new IllegalArgumentException("APPROVAL_BINDING_MISMATCH");
    }

    Optional<ApprovalPolicyBinding> storedBindingOpt = findPolicyBinding(
        connection, claim.binding().tenant(), claim.binding().approvalRequestId());

    if (claim.policyBinding() != null && storedBindingOpt.isEmpty()) {
      return new ApprovalConsumptionResult(
          ApprovalConsumptionStatus.STALE_POLICY_GENERATION, approval, null);
    }

    if (storedBindingOpt.isPresent()) {
      ApprovalPolicyBinding stored = storedBindingOpt.get();
      if (claim.policyBinding() != null && !stored.equals(claim.policyBinding())) {
        return new ApprovalConsumptionResult(
            ApprovalConsumptionStatus.STALE_POLICY_GENERATION, approval, null);
      }
      try (PreparedStatement checkPolicy = connection.prepareStatement("""
          SELECT a.active_version, a.activation_sequence, v.content_hash
          FROM tp_active_policies a
          JOIN tp_policy_versions v
            ON a.workspace_id = v.workspace_id
           AND a.policy_id = v.policy_id
           AND a.active_version = v.version
          WHERE a.workspace_id = ? AND a.policy_id = ?
          FOR SHARE OF a
          """)) {
        checkPolicy.setString(1, stored.workspaceId());
        checkPolicy.setString(2, stored.policyId());
        try (ResultSet rs = checkPolicy.executeQuery()) {
          if (!rs.next()) {
            return new ApprovalConsumptionResult(
                ApprovalConsumptionStatus.STALE_POLICY_GENERATION, approval, null);
          }
          int activeVersion = rs.getInt("active_version");
          long activeGeneration = rs.getLong("activation_sequence");
          String activeContentHash = rs.getString("content_hash");

          if (activeGeneration != stored.policyGeneration()
              || activeVersion != stored.policyVersion()
              || !activeContentHash.equals(stored.policyContentHash())) {
            return new ApprovalConsumptionResult(
                ApprovalConsumptionStatus.STALE_POLICY_GENERATION, approval, null);
          }
        }
      }
    }

    AttemptMetadata attempt = insertExecutionRows(connection, claim.executionClaim());
    appendApprovalTransition(
        connection,
        approval,
        "consumed",
        "APPROVAL_CONSUMED",
        claim.occurredAt(),
        null,
        attempt.permitId(),
        attempt.attemptId());
    ApprovalRequestState consumed = requireApproval(
        connection, claim.binding().tenant(), claim.binding().approvalRequestId(), false);
    appendApprovalEvidence(
        connection,
        consumed,
        "approval.consumed",
        "APPROVAL_CONSUMED",
        claim.occurredAt(),
        attempt.permitId(),
        attempt.attemptId());
    appendEvidence(
        connection,
        attempt,
        "execution.claimed",
        "executing",
        "EXECUTION_CLAIMED_AFTER_APPROVAL",
        claim.occurredAt());
    return new ApprovalConsumptionResult(
        ApprovalConsumptionStatus.STARTED,
        consumed,
        requireAttemptSnapshot(connection, claim.binding().tenant(), attempt.attemptId()));
  }

  private DurableClaimResult insertClaim(Connection connection, DurableExecutionClaim claim)
      throws SQLException {
    AttemptMetadata metadata = insertExecutionRows(connection, claim);
    appendEvidence(
        connection,
        metadata,
        "execution.claimed",
        "executing",
        "EXECUTION_CLAIMED",
        claim.occurredAt());
    return new DurableClaimResult(
        DurableClaimStatus.STARTED,
        requireAttemptSnapshot(connection, claim.tenant(), metadata.attemptId()));
  }

  private AttemptMetadata insertExecutionRows(Connection connection, DurableExecutionClaim claim)
      throws SQLException {
    String attemptId = createId.get();
    requireIdentifier(attemptId, 256, "attempt ID");
    try (PreparedStatement permit = connection.prepareStatement("""
        INSERT INTO tp_consumed_execution_permits
          (tenant_id, permit_id, expires_at, action_hash, claimed_at)
        VALUES (?, ?, ?, ?, ?)
        """)) {
      permit.setString(1, claim.tenant().value());
      permit.setString(2, claim.permitId());
      permit.setObject(3, databaseTime(claim.permitExpiresAt()));
      permit.setString(4, claim.actionHash());
      permit.setObject(5, databaseTime(claim.occurredAt()));
      permit.executeUpdate();
    }

    String insertAttempt = """
        INSERT INTO tp_execution_attempts
          (tenant_id, attempt_id, permit_id, adapter_id, idempotency_key,
           event_id, trace_id, evaluation_id, subject_id, action_hash,
           policy_id, policy_version, decision, credential_fingerprints,
           status, transition_sequence, started_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'executing', 1, ?, ?)
        """;
    try (PreparedStatement attempt = connection.prepareStatement(insertAttempt)) {
      attempt.setString(1, claim.tenant().value());
      attempt.setString(2, attemptId);
      attempt.setString(3, claim.permitId());
      attempt.setString(4, claim.adapterId());
      attempt.setString(5, claim.idempotencyKey());
      attempt.setString(6, claim.eventId());
      attempt.setString(7, claim.traceId());
      attempt.setString(8, claim.evaluationId());
      attempt.setString(9, claim.subjectId());
      attempt.setString(10, claim.actionHash());
      attempt.setString(11, claim.policyId());
      attempt.setInt(12, claim.policyVersion());
      attempt.setString(13, claim.decision());
      attempt.setObject(14, toJson(claim.credentialFingerprints()), Types.OTHER);
      attempt.setObject(15, databaseTime(claim.occurredAt()));
      attempt.setObject(16, databaseTime(claim.occurredAt()));
      attempt.executeUpdate();
    }

    try (PreparedStatement transition = connection.prepareStatement("""
        INSERT INTO tp_execution_transitions
          (tenant_id, attempt_id, sequence, from_status, to_status, occurred_at, reason_code)
        VALUES (?, ?, 1, NULL, 'executing', ?, 'EXECUTION_CLAIMED')
        """)) {
      transition.setString(1, claim.tenant().value());
      transition.setString(2, attemptId);
      transition.setObject(3, databaseTime(claim.occurredAt()));
      transition.executeUpdate();
    }

    AttemptMetadata metadata = new AttemptMetadata(
        claim.tenant(), attemptId, claim.permitId(), claim.adapterId(), claim.idempotencyKey(),
        claim.eventId(), claim.traceId(), claim.evaluationId(), claim.subjectId(), claim.actionHash(),
        claim.policyId(), claim.policyVersion(), claim.decision(), claim.credentialFingerprints(),
        "executing", 1, claim.occurredAt(), claim.occurredAt());
    return metadata;
  }

  private DurableClaimResult resolveClaimConflict(DurableExecutionClaim claim) {
    try (Connection connection = dataSource.getConnection()) {
      AttemptMetadata permitAttempt = findAttemptByPermit(connection, claim.tenant(), claim.permitId());
      if (permitAttempt != null) {
        return new DurableClaimResult(
            DurableClaimStatus.PERMIT_REPLAYED,
            requireAttemptSnapshot(connection, claim.tenant(), permitAttempt.attemptId()));
      }
      AttemptMetadata idempotent =
          findAttemptByKey(connection, claim.tenant(), claim.adapterId(), claim.idempotencyKey(), false);
      if (idempotent != null) {
        return new DurableClaimResult(
            DurableClaimStatus.IDEMPOTENCY_DUPLICATE,
            requireAttemptSnapshot(connection, claim.tenant(), idempotent.attemptId()));
      }
      return null;
    } catch (SQLException exception) {
      throw databaseFailure(exception);
    }
  }

  private static void bindApprovalBinding(PreparedStatement statement, ApprovalBinding binding)
      throws SQLException {
    statement.setString(1, binding.tenant().value());
    statement.setString(2, binding.approvalRequestId());
    statement.setString(3, binding.requestHash());
    statement.setString(4, binding.actionHash());
    statement.setString(5, binding.eventId());
    statement.setString(6, binding.evaluationId());
    statement.setString(7, binding.subjectId());
    statement.setString(8, binding.actorLineageHash());
    statement.setString(9, binding.policyId());
    statement.setInt(10, binding.policyVersion());
    statement.setString(11, binding.originalDecision());
    statement.setString(12, binding.toolName());
    statement.setString(13, binding.resourceHash());
    statement.setString(14, binding.approvalGroup());
    statement.setObject(15, databaseTime(binding.createdAt()));
    statement.setObject(16, databaseTime(binding.expiresAt()));
    statement.setInt(17, binding.maxUses());
  }

  private void appendApprovalTransition(
      Connection connection,
      ApprovalRequestState current,
      String toStatus,
      String reasonCode,
      Instant occurredAt,
      ApprovalDecision decision,
      String permitId,
      String attemptId) throws SQLException {
    String sql = """
        INSERT INTO tp_approval_transitions
          (tenant_id, approval_request_id, sequence, from_status, to_status,
           approval_decision_id, reviewer_subject_id, reviewer_authentication_method,
           reviewer_credential_fingerprint, reason_code, occurred_at, permit_id, attempt_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, current.binding().tenant().value());
      statement.setString(2, current.binding().approvalRequestId());
      statement.setInt(3, current.transitionSequence() + 1);
      statement.setString(4, current.status());
      statement.setString(5, toStatus);
      if (decision == null) {
        statement.setNull(6, Types.VARCHAR);
        statement.setNull(7, Types.VARCHAR);
        statement.setNull(8, Types.VARCHAR);
        statement.setNull(9, Types.CHAR);
      } else {
        statement.setString(6, decision.approvalDecisionId());
        statement.setString(7, decision.reviewerSubjectId());
        statement.setString(8, decision.reviewerAuthenticationMethod());
        statement.setString(9, decision.reviewerCredentialFingerprint());
      }
      statement.setString(10, reasonCode);
      statement.setObject(11, databaseTime(occurredAt));
      if (permitId == null) statement.setNull(12, Types.VARCHAR); else statement.setString(12, permitId);
      if (attemptId == null) statement.setNull(13, Types.VARCHAR); else statement.setString(13, attemptId);
      statement.executeUpdate();
    }
  }

  private ApprovalRequestState requireApproval(
      Connection connection,
      TenantPartition tenant,
      String approvalRequestId,
      boolean lock) throws SQLException {
    ApprovalRequestState approval = findApproval(connection, tenant, approvalRequestId, lock);
    if (approval == null) throw new IllegalArgumentException("APPROVAL_REQUEST_NOT_FOUND");
    return approval;
  }

  private ApprovalRequestState findApproval(
      Connection connection,
      TenantPartition tenant,
      String approvalRequestId,
      boolean lock) throws SQLException {
    String sql = """
        SELECT request_hash, action_hash, event_id, evaluation_id, subject_id,
               actor_lineage_hash, policy_id, policy_version, original_decision,
               tool_name, resource_hash, approval_group, created_at, expires_at,
               max_uses, status, transition_sequence, approval_decision_id,
               reviewer_subject_id, reviewer_authentication_method,
               reviewer_credential_fingerprint, decision_reason_code, decided_at,
               updated_at, consumed_permit_id, consumed_attempt_id
          FROM tp_approval_requests
         WHERE tenant_id = ? AND approval_request_id = ?
        """ + (lock ? " FOR UPDATE" : "");
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant.value());
      statement.setString(2, approvalRequestId);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) return null;
        ApprovalBinding binding = new ApprovalBinding(
            tenant,
            approvalRequestId,
            result.getString("request_hash"),
            result.getString("action_hash"),
            result.getString("event_id"),
            result.getString("evaluation_id"),
            result.getString("subject_id"),
            result.getString("actor_lineage_hash"),
            result.getString("policy_id"),
            result.getInt("policy_version"),
            result.getString("original_decision"),
            result.getString("tool_name"),
            result.getString("resource_hash"),
            result.getString("approval_group"),
            result.getObject("created_at", OffsetDateTime.class).toInstant(),
            result.getObject("expires_at", OffsetDateTime.class).toInstant(),
            result.getInt("max_uses"));
        OffsetDateTime decidedAt = result.getObject("decided_at", OffsetDateTime.class);
        return new ApprovalRequestState(
            binding,
            result.getString("status"),
            result.getInt("transition_sequence"),
            result.getString("approval_decision_id"),
            result.getString("reviewer_subject_id"),
            result.getString("reviewer_authentication_method"),
            result.getString("reviewer_credential_fingerprint"),
            result.getString("decision_reason_code"),
            decidedAt == null ? null : decidedAt.toInstant(),
            result.getObject("updated_at", OffsetDateTime.class).toInstant(),
            result.getString("consumed_permit_id"),
            result.getString("consumed_attempt_id"));
      }
    }
  }

  private ExecutionAttempt findAttemptSnapshot(TenantPartition tenant, String attemptId) {
    if (attemptId == null) return null;
    try (Connection connection = dataSource.getConnection()) {
      return requireAttemptSnapshot(connection, tenant, attemptId);
    } catch (SQLException exception) {
      throw databaseFailure(exception);
    }
  }

  private void enqueueReconciliation(
      Connection connection, AttemptMetadata metadata, Instant unknownSince) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        INSERT INTO tp_reconciliation_queue
          (tenant_id, attempt_id, adapter_id, idempotency_key, unknown_since,
           next_attempt_at)
        VALUES (?, ?, ?, ?, ?, ?)
        """)) {
      statement.setString(1, metadata.tenant().value());
      statement.setString(2, metadata.attemptId());
      statement.setString(3, metadata.adapterId());
      statement.setString(4, metadata.idempotencyKey());
      statement.setObject(5, databaseTime(unknownSince));
      statement.setObject(6, databaseTime(unknownSince));
      statement.executeUpdate();
    }
  }

  private ExecutionAttempt reconcileLocked(
      Connection connection,
      AttemptMetadata metadata,
      String outcome,
      String reasonCode,
      Instant occurredAt)
      throws SQLException {
    if (!"unknown".equals(metadata.status())) {
      throw new IllegalStateException(
          "Execution attempt is " + metadata.status() + ", not unknown.");
    }
    appendTransition(connection, metadata, outcome, reasonCode, occurredAt);
    appendEvidence(
        connection, metadata, "reconciliation.completed", outcome, reasonCode, occurredAt);
    return requireAttemptSnapshot(connection, metadata.tenant(), metadata.attemptId());
  }

  private void requireReconciliationLease(
      Connection connection,
      ReconciliationCandidate candidate,
      String workerId,
      Instant occurredAt)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT adapter_id, idempotency_key, unknown_since, lease_owner,
               lease_expires_at
          FROM tp_reconciliation_queue
         WHERE tenant_id = ? AND attempt_id = ?
         FOR UPDATE
        """)) {
      statement.setString(1, candidate.tenant().value());
      statement.setString(2, candidate.attemptId());
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          throw new IllegalArgumentException("The reconciliation candidate does not exist.");
        }
        OffsetDateTime leaseExpiry = result.getObject("lease_expires_at", OffsetDateTime.class);
        if (!candidate.adapterId().equals(result.getString("adapter_id"))
            || !candidate.idempotencyKey().equals(result.getString("idempotency_key"))
            || !candidate.unknownSince().equals(
                result.getObject("unknown_since", OffsetDateTime.class).toInstant())) {
          throw new IllegalArgumentException("The reconciliation binding does not match.");
        }
        if (!workerId.equals(result.getString("lease_owner"))
            || leaseExpiry == null
            || !leaseExpiry.toInstant().isAfter(occurredAt)) {
          throw new IllegalStateException("The reconciliation lease is not active.");
        }
      }
    }
  }

  private static void lockReconciliationQueueByKey(
      Connection connection,
      TenantPartition tenant,
      String adapterId,
      String idempotencyKey)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT attempt_id
          FROM tp_reconciliation_queue
         WHERE tenant_id = ? AND adapter_id = ? AND idempotency_key = ?
         FOR UPDATE
        """)) {
      statement.setString(1, tenant.value());
      statement.setString(2, adapterId);
      statement.setString(3, idempotencyKey);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          throw new IllegalArgumentException("The reconciliation candidate does not exist.");
        }
      }
    }
  }

  private static void deleteReconciliationQueue(
      Connection connection, TenantPartition tenant, String attemptId) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        DELETE FROM tp_reconciliation_queue
         WHERE tenant_id = ? AND attempt_id = ?
        """)) {
      statement.setString(1, tenant.value());
      statement.setString(2, attemptId);
      statement.executeUpdate();
    }
  }

  private void appendTransition(
      Connection connection,
      AttemptMetadata metadata,
      String outcome,
      String reasonCode,
      Instant occurredAt) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        INSERT INTO tp_execution_transitions
          (tenant_id, attempt_id, sequence, from_status, to_status, occurred_at, reason_code)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """)) {
      statement.setString(1, metadata.tenant().value());
      statement.setString(2, metadata.attemptId());
      statement.setInt(3, metadata.transitionSequence() + 1);
      statement.setString(4, metadata.status());
      statement.setString(5, outcome);
      statement.setObject(6, databaseTime(occurredAt));
      statement.setString(7, reasonCode);
      statement.executeUpdate();
    }
  }

  private void appendEvidence(
      Connection connection,
      AttemptMetadata metadata,
      String kind,
      String status,
      String reasonCode,
      Instant occurredAt) throws SQLException {
    appendEvidence(
        connection,
        metadata.tenant(),
        (evidenceEventId, sequence, recordedAt) -> evidencePayload(
            metadata, evidenceEventId, sequence, kind, status, reasonCode, occurredAt, recordedAt));
  }

  private void appendApprovalEvidence(
      Connection connection,
      ApprovalRequestState approval,
      String kind,
      String reasonCode,
      Instant occurredAt,
      String permitId,
      String attemptId) throws SQLException {
    appendEvidence(
        connection,
        approval.binding().tenant(),
        (evidenceEventId, sequence, recordedAt) -> approvalEvidencePayload(
            approval,
            evidenceEventId,
            sequence,
            kind,
            reasonCode,
            occurredAt,
            recordedAt,
            permitId,
            attemptId));
  }

  private void appendEvidence(
      Connection connection,
      TenantPartition tenant,
      EvidencePayloadFactory payloadFactory) throws SQLException {
    try (PreparedStatement createStream = connection.prepareStatement("""
        INSERT INTO tp_evidence_streams (tenant_id, stream_id)
        VALUES (?, ?)
        ON CONFLICT (tenant_id, stream_id) DO NOTHING
        """)) {
      createStream.setString(1, tenant.value());
      createStream.setString(2, STREAM_ID);
      createStream.executeUpdate();
    }

    long currentSequence;
    String previousHash;
    try (PreparedStatement lockStream = connection.prepareStatement("""
        SELECT head_sequence, head_hash
          FROM tp_evidence_streams
         WHERE tenant_id = ? AND stream_id = ?
         FOR UPDATE
        """)) {
      lockStream.setString(1, tenant.value());
      lockStream.setString(2, STREAM_ID);
      try (ResultSet result = lockStream.executeQuery()) {
        if (!result.next()) throw new SQLException("Evidence stream was not created.");
        currentSequence = result.getLong("head_sequence");
        previousHash = result.getString("head_hash");
      }
    }

    long sequence = currentSequence + 1;
    String evidenceEventId = createId.get();
    String outboxId = createId.get();
    requireIdentifier(evidenceEventId, 256, "evidence event ID");
    requireIdentifier(outboxId, 256, "outbox ID");
    Instant recordedAt = clock.instant();
    ObjectNode payload = payloadFactory.create(evidenceEventId, sequence, recordedAt);
    String eventHash = InMemoryEvidenceLedger.hash(payload, previousHash);

    try (PreparedStatement event = connection.prepareStatement("""
        INSERT INTO tp_evidence_events
          (tenant_id, stream_id, sequence, evidence_event_id, payload,
           previous_event_hash, event_hash, recorded_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """)) {
      event.setString(1, tenant.value());
      event.setString(2, STREAM_ID);
      event.setLong(3, sequence);
      event.setString(4, evidenceEventId);
      event.setObject(5, toJson(payload), Types.OTHER);
      if (previousHash == null) event.setNull(6, Types.CHAR); else event.setString(6, previousHash);
      event.setString(7, eventHash);
      event.setObject(8, databaseTime(recordedAt));
      event.executeUpdate();
    }

    try (PreparedStatement updateStream = connection.prepareStatement("""
        UPDATE tp_evidence_streams
           SET head_sequence = ?, head_hash = ?
         WHERE tenant_id = ? AND stream_id = ?
        """)) {
      updateStream.setLong(1, sequence);
      updateStream.setString(2, eventHash);
      updateStream.setString(3, tenant.value());
      updateStream.setString(4, STREAM_ID);
      updateStream.executeUpdate();
    }

    try (PreparedStatement outbox = connection.prepareStatement("""
        INSERT INTO tp_evidence_outbox
          (tenant_id, outbox_id, stream_id, sequence, evidence_event_id, payload_hash,
           created_at, next_attempt_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """)) {
      outbox.setString(1, tenant.value());
      outbox.setString(2, outboxId);
      outbox.setString(3, STREAM_ID);
      outbox.setLong(4, sequence);
      outbox.setString(5, evidenceEventId);
      outbox.setString(6, eventHash);
      outbox.setObject(7, databaseTime(recordedAt));
      outbox.setObject(8, databaseTime(recordedAt));
      outbox.executeUpdate();
    }
  }

  private static ObjectNode approvalEvidencePayload(
      ApprovalRequestState approval,
      String evidenceEventId,
      long sequence,
      String kind,
      String reasonCode,
      Instant occurredAt,
      Instant recordedAt,
      String permitId,
      String attemptId) {
    ApprovalBinding binding = approval.binding();
    ObjectNode correlation = JsonSupport.MAPPER.createObjectNode();
    correlation.put("eventId", binding.eventId());
    correlation.put("evaluationId", binding.evaluationId());
    correlation.put("approvalRequestId", binding.approvalRequestId());
    if (approval.approvalDecisionId() != null) {
      correlation.put("approvalDecisionId", approval.approvalDecisionId());
    }
    if (permitId != null) correlation.put("permitId", permitId);
    if (attemptId != null) correlation.put("attemptId", attemptId);
    ObjectNode policy = JsonSupport.MAPPER.createObjectNode();
    policy.put("policyId", binding.policyId());
    policy.put("policyVersion", binding.policyVersion());
    ObjectNode approvalDetail = JsonSupport.MAPPER.createObjectNode();
    approvalDetail.put("status", approval.status());
    approvalDetail.put("reasonCode", reasonCode);
    approvalDetail.put("requestHash", binding.requestHash());
    approvalDetail.put("actorLineageHash", binding.actorLineageHash());
    approvalDetail.put("resourceHash", binding.resourceHash());
    approvalDetail.put("toolName", binding.toolName());
    approvalDetail.put("maxUses", binding.maxUses());
    if (approval.reviewerSubjectId() != null) {
      approvalDetail.put("reviewerSubjectId", approval.reviewerSubjectId());
      approvalDetail.put("reviewerAuthenticationMethod", approval.reviewerAuthenticationMethod());
      approvalDetail.put(
          "reviewerCredentialFingerprint", approval.reviewerCredentialFingerprint());
    }

    ObjectNode payload = JsonSupport.MAPPER.createObjectNode();
    payload.put("schemaVersion", "2.0");
    payload.put("evidenceEventId", evidenceEventId);
    payload.put("streamId", STREAM_ID);
    payload.put("sequence", sequence);
    payload.put("kind", kind);
    payload.put("occurredAt", occurredAt.toString());
    payload.put("recordedAt", recordedAt.toString());
    payload.put("producer", PRODUCER);
    payload.set("correlation", correlation);
    payload.put("subjectId", binding.subjectId());
    payload.put("actionHash", binding.actionHash());
    payload.set("policy", policy);
    payload.put("decision", binding.originalDecision());
    payload.set("approval", approvalDetail);
    return payload;
  }

  private static ObjectNode evidencePayload(
      AttemptMetadata metadata,
      String evidenceEventId,
      long sequence,
      String kind,
      String status,
      String reasonCode,
      Instant occurredAt,
      Instant recordedAt) {
    ObjectNode correlation = JsonSupport.MAPPER.createObjectNode();
    correlation.put("traceId", metadata.traceId());
    correlation.put("eventId", metadata.eventId());
    correlation.put("evaluationId", metadata.evaluationId());
    correlation.put("permitId", metadata.permitId());
    correlation.put("attemptId", metadata.attemptId());
    correlation.put("idempotencyKey", metadata.idempotencyKey());
    ObjectNode policy = JsonSupport.MAPPER.createObjectNode();
    policy.put("policyId", metadata.policyId());
    policy.put("policyVersion", metadata.policyVersion());
    ObjectNode execution = JsonSupport.MAPPER.createObjectNode();
    execution.put("status", status);
    execution.put("reasonCode", reasonCode);
    ArrayNode fingerprints = JsonSupport.MAPPER.createArrayNode();
    metadata.credentialFingerprints().forEach(fingerprints::add);

    ObjectNode payload = JsonSupport.MAPPER.createObjectNode();
    payload.put("schemaVersion", "1.0");
    payload.put("evidenceEventId", evidenceEventId);
    payload.put("streamId", STREAM_ID);
    payload.put("sequence", sequence);
    payload.put("kind", kind);
    payload.put("occurredAt", occurredAt.toString());
    payload.put("recordedAt", recordedAt.toString());
    payload.put("producer", PRODUCER);
    payload.set("correlation", correlation);
    payload.put("subjectId", metadata.subjectId());
    payload.put("actionHash", metadata.actionHash());
    payload.set("policy", policy);
    payload.put("decision", metadata.decision());
    payload.set("execution", execution);
    payload.set("credentialFingerprints", fingerprints);
    return payload;
  }

  private AttemptMetadata requireAttempt(
      Connection connection, TenantPartition tenant, String attemptId, boolean lock)
      throws SQLException {
    AttemptMetadata metadata = findAttempt(connection, tenant, attemptId, lock);
    if (metadata == null) throw new IllegalArgumentException("Execution attempt does not exist.");
    return metadata;
  }

  private AttemptMetadata requireAttemptByKey(
      Connection connection,
      TenantPartition tenant,
      String adapterId,
      String idempotencyKey,
      boolean lock) throws SQLException {
    AttemptMetadata metadata = findAttemptByKey(connection, tenant, adapterId, idempotencyKey, lock);
    if (metadata == null) throw new IllegalArgumentException("Execution attempt does not exist.");
    return metadata;
  }

  private AttemptMetadata findAttempt(
      Connection connection, TenantPartition tenant, String attemptId, boolean lock)
      throws SQLException {
    return queryAttempt(
        connection,
        "WHERE tenant_id = ? AND attempt_id = ?" + (lock ? " FOR UPDATE" : ""),
        tenant,
        statement -> statement.setString(2, attemptId));
  }

  private AttemptMetadata findAttemptByPermit(
      Connection connection, TenantPartition tenant, String permitId) throws SQLException {
    return queryAttempt(
        connection,
        "WHERE tenant_id = ? AND permit_id = ?",
        tenant,
        statement -> statement.setString(2, permitId));
  }

  private AttemptMetadata findAttemptByKey(
      Connection connection,
      TenantPartition tenant,
      String adapterId,
      String idempotencyKey,
      boolean lock) throws SQLException {
    return queryAttempt(
        connection,
        "WHERE tenant_id = ? AND adapter_id = ? AND idempotency_key = ?" +
            (lock ? " FOR UPDATE" : ""),
        tenant,
        statement -> {
          statement.setString(2, adapterId);
          statement.setString(3, idempotencyKey);
        });
  }

  private AttemptMetadata queryAttempt(
      Connection connection,
      String condition,
      TenantPartition tenant,
      StatementBinder binder) throws SQLException {
    String sql = """
        SELECT attempt_id, permit_id, adapter_id, idempotency_key, event_id,
               trace_id, evaluation_id, subject_id, action_hash, policy_id,
               policy_version, decision, credential_fingerprints::text,
               status, transition_sequence, started_at, updated_at
          FROM tp_execution_attempts
        """ + condition;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant.value());
      binder.bind(statement);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) return null;
        return new AttemptMetadata(
            tenant,
            result.getString("attempt_id"),
            result.getString("permit_id"),
            result.getString("adapter_id"),
            result.getString("idempotency_key"),
            result.getString("event_id"),
            result.getString("trace_id"),
            result.getString("evaluation_id"),
            result.getString("subject_id"),
            result.getString("action_hash"),
            result.getString("policy_id"),
            result.getInt("policy_version"),
            result.getString("decision"),
            strings(parseJson(result.getString("credential_fingerprints"))),
            result.getString("status"),
            result.getInt("transition_sequence"),
            result.getObject("started_at", OffsetDateTime.class).toInstant(),
            result.getObject("updated_at", OffsetDateTime.class).toInstant());
      }
    }
  }

  private ExecutionAttempt requireAttemptSnapshot(
      Connection connection, TenantPartition tenant, String attemptId) throws SQLException {
    AttemptMetadata metadata = requireAttempt(connection, tenant, attemptId, false);
    List<ExecutionTransition> transitions = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT sequence, from_status, to_status, occurred_at, reason_code
          FROM tp_execution_transitions
         WHERE tenant_id = ? AND attempt_id = ?
         ORDER BY sequence
        """)) {
      statement.setString(1, tenant.value());
      statement.setString(2, attemptId);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          transitions.add(new ExecutionTransition(
              result.getInt("sequence"),
              result.getString("from_status"),
              result.getString("to_status"),
              result.getObject("occurred_at", OffsetDateTime.class).toInstant(),
              result.getString("reason_code")));
        }
      }
    }
    return new ExecutionAttempt(
        metadata.attemptId(),
        metadata.eventId(),
        metadata.permitId(),
        metadata.idempotencyKey(),
        metadata.status(),
        metadata.startedAt(),
        metadata.updatedAt(),
        transitions,
        null);
  }

  private void verifySchemaVersion() {
    try (Connection connection = dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT contract_version FROM tp_enforcement_schema_guard WHERE singleton = TRUE");
         ResultSet result = statement.executeQuery()) {
      if (!result.next() || result.getInt(1) != 8 || result.next()) {
        throw new IllegalStateException("INCOMPATIBLE_DURABLE_STATE_SCHEMA");
      }
    } catch (SQLException exception) {
      throw databaseFailure(exception);
    }
  }

  private <T> T inTransaction(SqlWork<T> work) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        T result = work.run(connection);
        connection.commit();
        return result;
      } catch (RuntimeException | SQLException failure) {
        try {
          connection.rollback();
        } catch (SQLException rollbackFailure) {
          failure.addSuppressed(rollbackFailure);
        }
        if (failure instanceof RuntimeException runtime) throw runtime;
        throw databaseFailure(failure);
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (SQLException exception) {
      throw databaseFailure(exception);
    }
  }

  private static String toJson(Object value) {
    try {
      return JsonSupport.MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("Cannot encode durable state projection.", exception);
    }
  }

  private static JsonNode parseJson(String value) {
    try {
      return JsonSupport.MAPPER.readTree(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Stored durable state JSON is invalid.", exception);
    }
  }

  private static List<String> strings(JsonNode values) {
    List<String> result = new ArrayList<>();
    values.forEach(value -> result.add(value.asText()));
    return List.copyOf(result);
  }

  private static OffsetDateTime databaseTime(Instant value) {
    return value.atOffset(ZoneOffset.UTC);
  }

  private static void requireIdentifier(String value, int maximum, String label) {
    Objects.requireNonNull(value);
    if (value.isEmpty() || value.length() > maximum ||
        !value.matches("^[A-Za-z0-9][A-Za-z0-9._:@/+\\-]*$")) {
      throw new IllegalArgumentException("The " + label + " is invalid.");
    }
  }

  private static void requireHash(String value, String label) {
    Objects.requireNonNull(value);
    if (!value.matches("^sha256:[a-f0-9]{64}$")) {
      throw new IllegalArgumentException("The " + label + " is invalid.");
    }
  }

  private static void requireReason(String value) {
    Objects.requireNonNull(value);
    if (!value.matches("^[A-Z][A-Z0-9_]{0,127}$")) {
      throw new IllegalArgumentException("The execution reason code is invalid.");
    }
  }

  private static boolean hasSqlState(Throwable failure, String sqlState) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof SQLException sql && sqlState.equals(sql.getSQLState())) return true;
      current = current.getCause();
    }
    return false;
  }

  private static PostgresStateException databaseFailure(Throwable cause) {
    return new PostgresStateException("DURABLE_STATE_UNAVAILABLE", cause);
  }

  @FunctionalInterface
  private interface SqlWork<T> {
    T run(Connection connection) throws SQLException;
  }

  @FunctionalInterface
  private interface StatementBinder {
    void bind(PreparedStatement statement) throws SQLException;
  }

  @FunctionalInterface
  private interface EvidencePayloadFactory {
    ObjectNode create(String evidenceEventId, long sequence, Instant recordedAt);
  }

  private record AttemptMetadata(
      TenantPartition tenant,
      String attemptId,
      String permitId,
      String adapterId,
      String idempotencyKey,
      String eventId,
      String traceId,
      String evaluationId,
      String subjectId,
      String actionHash,
      String policyId,
      int policyVersion,
      String decision,
      List<String> credentialFingerprints,
      String status,
      int transitionSequence,
      Instant startedAt,
      Instant updatedAt) {
    private AttemptMetadata {
      credentialFingerprints = List.copyOf(credentialFingerprints);
    }
  }
}
