package io.tracepermit.persistence.identity;

import static io.tracepermit.persistence.identity.WorkloadIdentityReplayException.Code.*;

import io.tracepermit.identity.WorkloadIdentityReplayStore;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * JDBC-backed durable workload-identity replay store (SEC-006, REL-004, FR-011, OPS-004).
 *
 * <p>Enforces atomic single-use credential consumption across process instances and restarts by
 * persisting collision-safe, domain-separated claims in {@code tp_workload_replay_claims} with
 * unique primary-key conflict rejection. The store enforces finite capacity and fail-closed
 * saturation without live entry eviction.
 *
 * <p>A successful consumption returns {@code true} only after durable commit and connection
 * cleanup acknowledgement.
 */
public final class JdbcWorkloadIdentityReplayStore implements WorkloadIdentityReplayStore {
  public static final long DEFAULT_CAPACITY = 65_536;
  public static final int MAX_TENANT_ID_LENGTH = 128;
  public static final int MAX_ISSUER_LENGTH = 256;
  public static final int MAX_TOKEN_ID_LENGTH = 256;

  private static final String HASH_DOMAIN = "tracepermit:workload-identity-replay:v1\0";

  private final DataSource dataSource;
  private final long capacity;

  public JdbcWorkloadIdentityReplayStore(DataSource dataSource) {
    this(dataSource, DEFAULT_CAPACITY);
  }

  public JdbcWorkloadIdentityReplayStore(DataSource dataSource, long capacity) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    if (capacity <= 0) {
      throw new IllegalArgumentException("capacity must be positive: " + capacity);
    }
    this.capacity = capacity;
    WorkloadIdentityReplayDatabase.validateExisting(dataSource);
  }

  @Override
  public boolean consume(
      String tenantId, String issuer, String tokenId, Instant expiresAt, Instant now) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(issuer, "issuer");
    Objects.requireNonNull(tokenId, "tokenId");
    Objects.requireNonNull(expiresAt, "expiresAt");
    Objects.requireNonNull(now, "now");

    byte[] tenantBytes = encodeUtf8("tenantId", tenantId, MAX_TENANT_ID_LENGTH);
    byte[] issuerBytes = encodeUtf8("issuer", issuer, MAX_ISSUER_LENGTH);
    byte[] tokenBytes = encodeUtf8("tokenId", tokenId, MAX_TOKEN_ID_LENGTH);

    // Expired or exact-boundary submission fails closed and writes nothing.
    if (!expiresAt.isAfter(now)) {
      return false;
    }

    String claimHash = computeClaimHash(tenantBytes, issuerBytes, tokenBytes);
    String tenantHash = computeFingerprint("tracepermit:tenant:v1\0", tenantBytes);
    String tokenFingerprint = computeFingerprint("tracepermit:token:v1\0", tokenBytes);

    boolean commitAttempted = false;
    boolean isRefusal = false;

    try (Connection connection = dataSource.getConnection()) {
      WorkloadIdentityReplayDatabase.Engine engine =
          WorkloadIdentityReplayDatabase.inspectWriter(connection);
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      connection.setAutoCommit(false);

      try {
        // Step 1: Plain unique insert of the claim
        try (PreparedStatement insert =
            connection.prepareStatement("""
            INSERT INTO tp_workload_replay_claims
              (claim_hash, tenant_hash, token_fingerprint, expires_at, claimed_at)
            VALUES (?, ?, ?, ?, ?)
            """)) {
          insert.setString(1, claimHash);
          insert.setString(2, tenantHash);
          insert.setString(3, tokenFingerprint);
          setTimestamp(insert, 4, expiresAt, engine);
          setTimestamp(insert, 5, now, engine);
          insert.executeUpdate();
        } catch (SQLException insertFailure) {
          connection.rollback();
          if (engine.isDuplicateKey(insertFailure)) {
            isRefusal = true;
            return false;
          }
          if (engine.isPermissionDenied(insertFailure)) {
            throw new WorkloadIdentityReplayException(REPLAY_STORAGE_PERMISSION_DENIED);
          }
          throw new WorkloadIdentityReplayException(REPLAY_STORAGE_UNAVAILABLE);
        }

        // Step 2: Atomic capacity check and counter update
        try (PreparedStatement updateCounter =
            connection.prepareStatement("""
            UPDATE tp_workload_replay_state
               SET total_claims = total_claims + 1
             WHERE singleton = 1 AND total_claims < ?
            """)) {
          updateCounter.setLong(1, capacity);
          int updated = updateCounter.executeUpdate();
          if (updated == 0) {
            // Saturated: rollback claim insert
            connection.rollback();
            isRefusal = true;
            return false;
          }
        } catch (SQLException updateFailure) {
          connection.rollback();
          if (engine.isPermissionDenied(updateFailure)) {
            throw new WorkloadIdentityReplayException(REPLAY_STORAGE_PERMISSION_DENIED);
          }
          throw new WorkloadIdentityReplayException(REPLAY_STORAGE_UNAVAILABLE);
        }

        // Step 3: Commit transaction
        commitAttempted = true;
        connection.commit();
      } catch (SQLException failure) {
        try {
          connection.rollback();
        } catch (SQLException rollbackFailure) {
          throw new WorkloadIdentityReplayException(
              commitAttempted ? REPLAY_COMMIT_UNKNOWN : REPLAY_STORAGE_UNAVAILABLE);
        }
        throw new WorkloadIdentityReplayException(
            commitAttempted ? REPLAY_COMMIT_UNKNOWN : REPLAY_STORAGE_UNAVAILABLE);
      }
    } catch (WorkloadIdentityReplayException failure) {
      throw failure;
    } catch (SQLException failure) {
      if (isRefusal) {
        return false;
      }
      throw new WorkloadIdentityReplayException(
          commitAttempted ? REPLAY_COMMIT_UNKNOWN : REPLAY_STORAGE_UNAVAILABLE);
    }

    // Return true only after commit and connection cleanup acknowledgement
    return true;
  }

  public long capacity() {
    return capacity;
  }

  public long totalClaims() {
    try (Connection connection = dataSource.getConnection()) {
      WorkloadIdentityReplayDatabase.inspectWriter(connection);
      try (PreparedStatement statement =
          connection.prepareStatement(
              "SELECT total_claims FROM tp_workload_replay_state WHERE singleton = 1")) {
        statement.setQueryTimeout(5);
        try (ResultSet rows = statement.executeQuery()) {
          if (rows.next()) {
            return rows.getLong(1);
          }
          throw new WorkloadIdentityReplayException(REPLAY_SCHEMA_NOT_READY);
        }
      }
    } catch (SQLException failure) {
      throw new WorkloadIdentityReplayException(REPLAY_STORAGE_UNAVAILABLE);
    }
  }

  public boolean isClaimed(String tenantId, String issuer, String tokenId) {
    return findClaimExpiry(tenantId, issuer, tokenId).isPresent();
  }

  public Optional<Instant> findClaimExpiry(String tenantId, String issuer, String tokenId) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(issuer, "issuer");
    Objects.requireNonNull(tokenId, "tokenId");

    byte[] tenantBytes = encodeUtf8("tenantId", tenantId, MAX_TENANT_ID_LENGTH);
    byte[] issuerBytes = encodeUtf8("issuer", issuer, MAX_ISSUER_LENGTH);
    byte[] tokenBytes = encodeUtf8("tokenId", tokenId, MAX_TOKEN_ID_LENGTH);

    String claimHash = computeClaimHash(tenantBytes, issuerBytes, tokenBytes);

    try (Connection connection = dataSource.getConnection()) {
      WorkloadIdentityReplayDatabase.Engine engine =
          WorkloadIdentityReplayDatabase.inspectWriter(connection);
      try (PreparedStatement statement =
          connection.prepareStatement(
              "SELECT expires_at FROM tp_workload_replay_claims WHERE claim_hash = ?")) {
        statement.setQueryTimeout(5);
        statement.setString(1, claimHash);
        try (ResultSet rows = statement.executeQuery()) {
          if (!rows.next()) {
            return Optional.empty();
          }
          return Optional.of(readTimestamp(rows, 1, engine));
        }
      }
    } catch (SQLException failure) {
      throw new WorkloadIdentityReplayException(REPLAY_STORAGE_UNAVAILABLE);
    }
  }

  public static String computeClaimHash(
      byte[] tenantBytes, byte[] issuerBytes, byte[] tokenBytes) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(HASH_DOMAIN.getBytes(StandardCharsets.UTF_8));

      digest.update(ByteBuffer.allocate(4).putInt(tenantBytes.length).array());
      digest.update(tenantBytes);

      digest.update(ByteBuffer.allocate(4).putInt(issuerBytes.length).array());
      digest.update(issuerBytes);

      digest.update(ByteBuffer.allocate(4).putInt(tokenBytes.length).array());
      digest.update(tokenBytes);

      return "sha256:" + HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 algorithm unavailable", e);
    }
  }

  public static String computeFingerprint(String domain, byte[] inputBytes) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(domain.getBytes(StandardCharsets.UTF_8));
      digest.update(inputBytes);
      return "sha256:" + HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 algorithm unavailable", e);
    }
  }

  public static byte[] encodeUtf8(String fieldName, String value, int maxChars) {
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException(fieldName + " must not be null or empty");
    }
    if (value.length() > maxChars) {
      throw new IllegalArgumentException(
          fieldName + " exceeds maximum allowed length of " + maxChars);
    }
    CharsetEncoder encoder =
        StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    try {
      ByteBuffer buffer = encoder.encode(CharBuffer.wrap(value));
      byte[] bytes = new byte[buffer.remaining()];
      buffer.get(bytes);
      return bytes;
    } catch (CharacterCodingException e) {
      throw new IllegalArgumentException(
          fieldName + " contains malformed or unmappable Unicode characters");
    }
  }

  private static void setTimestamp(
      PreparedStatement statement,
      int parameterIndex,
      Instant instant,
      WorkloadIdentityReplayDatabase.Engine engine)
      throws SQLException {
    if (engine == WorkloadIdentityReplayDatabase.Engine.POSTGRESQL) {
      statement.setObject(parameterIndex, OffsetDateTime.ofInstant(instant, ZoneOffset.UTC));
    } else {
      statement.setObject(parameterIndex, LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }
  }

  private static Instant readTimestamp(
      ResultSet rows, int columnIndex, WorkloadIdentityReplayDatabase.Engine engine)
      throws SQLException {
    if (engine == WorkloadIdentityReplayDatabase.Engine.POSTGRESQL) {
      OffsetDateTime odt = rows.getObject(columnIndex, OffsetDateTime.class);
      return odt.toInstant();
    } else {
      LocalDateTime ldt = rows.getObject(columnIndex, LocalDateTime.class);
      return ldt.toInstant(ZoneOffset.UTC);
    }
  }
}
