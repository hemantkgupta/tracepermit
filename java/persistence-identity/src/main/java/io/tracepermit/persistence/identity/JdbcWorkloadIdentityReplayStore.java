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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Shared durable implementation of {@link WorkloadIdentityReplayStore} backed by PostgreSQL 16.4
 * and MariaDB 11.4.13/10.6.25 (InnoDB).
 *
 * <p>Key properties:
 * <ul>
 *   <li>Domain-separated, length-delimited SHA-256 compound hashing prevents collision and delimiter attacks.</li>
 *   <li>Stores bounded cryptographic fingerprints and timestamps; zero raw credentials or bearer tokens are saved.</li>
 *   <li>Single-winner concurrency via plain unique insert; duplicate-key exceptions represent replay refusals.</li>
 *   <li>Finite capacity with fail-closed saturation via atomic counter update; unexpired claims are never evicted.</li>
 *   <li>Enforces agreed operator-persisted capacity, rejecting mismatched store instances.</li>
 *   <li>Monotonic elapsed time prevents authority acknowledgement after lock waits cross token expiry.</li>
 *   <li>Exceptions discard JDBC causes and suppressed exceptions to prevent credential leakage.</li>
 * </ul>
 *
 * <p>A successful consumption returns {@code true} only after durable commit and connection
 * cleanup acknowledgement.
 */
public final class JdbcWorkloadIdentityReplayStore implements WorkloadIdentityReplayStore {
  public static final long DEFAULT_CAPACITY = WorkloadIdentityReplayDatabase.DEFAULT_CAPACITY;
  public static final Duration DEFAULT_DML_TIMEOUT = Duration.ofSeconds(5);
  public static final int MAX_TENANT_ID_LENGTH = 128;
  public static final int MAX_ISSUER_LENGTH = 256;
  public static final int MAX_TOKEN_ID_LENGTH = 256;

  private static final String HASH_DOMAIN = "tracepermit:workload-identity-replay:v1\0";

  private final DataSource dataSource;
  private final long capacity;
  private final Duration dmlTimeout;
  private final WorkloadIdentityReplayDatabase.Engine engine;

  public JdbcWorkloadIdentityReplayStore(DataSource dataSource) {
    this(dataSource, DEFAULT_CAPACITY, DEFAULT_DML_TIMEOUT);
  }

  public JdbcWorkloadIdentityReplayStore(DataSource dataSource, long capacity) {
    this(dataSource, capacity, DEFAULT_DML_TIMEOUT);
  }

  public JdbcWorkloadIdentityReplayStore(DataSource dataSource, long capacity, Duration dmlTimeout) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    if (capacity <= 0) {
      throw new IllegalArgumentException("capacity must be positive: " + capacity);
    }
    this.capacity = capacity;
    this.dmlTimeout = Objects.requireNonNull(dmlTimeout, "dmlTimeout");
    if (dmlTimeout.isNegative() || dmlTimeout.isZero()) {
      throw new IllegalArgumentException("dmlTimeout must be positive: " + dmlTimeout);
    }
    try (Connection conn = dataSource.getConnection()) {
      this.engine = WorkloadIdentityReplayDatabase.inspectWriter(conn);
    } catch (WorkloadIdentityReplayException failure) {
      throw failure;
    } catch (SQLException failure) {
      throw new WorkloadIdentityReplayException(REPLAY_STORAGE_UNAVAILABLE);
    }
    WorkloadIdentityReplayDatabase.validateExisting(dataSource, capacity);
  }

  @Override
  public boolean consume(
      String tenantId, String issuer, String tokenId, Instant expiresAt, Instant now) {
    long startNanos = System.nanoTime();
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(issuer, "issuer");
    Objects.requireNonNull(tokenId, "tokenId");
    Objects.requireNonNull(expiresAt, "expiresAt");
    Objects.requireNonNull(now, "now");

    // Expired or exact-boundary submission fails closed and writes nothing.
    if (!expiresAt.isAfter(now)) {
      return false;
    }
    if (isExpired(expiresAt, now, startNanos)) {
      return false;
    }

    byte[] tenantBytes = encodeUtf8("tenantId", tenantId, MAX_TENANT_ID_LENGTH);
    byte[] issuerBytes = encodeUtf8("issuer", issuer, MAX_ISSUER_LENGTH);
    byte[] tokenBytes = encodeUtf8("tokenId", tokenId, MAX_TOKEN_ID_LENGTH);

    String claimHash = computeClaimHash(tenantBytes, issuerBytes, tokenBytes);
    String tenantHash = computeFingerprint("tracepermit:tenant:v1\0", tenantBytes);
    String tokenFingerprint = computeFingerprint("tracepermit:token:v1\0", tokenBytes);

    if (isExpired(expiresAt, now, startNanos)) {
      return false;
    }

    Connection connection = null;
    boolean commitAttempted = false;

    try {
      try {
        connection = dataSource.getConnection();
      } catch (SQLException connEx) {
        if (engine.isPermissionDenied(connEx)) {
          throw new WorkloadIdentityReplayException(REPLAY_STORAGE_PERMISSION_DENIED);
        }
        throw new WorkloadIdentityReplayException(REPLAY_STORAGE_UNAVAILABLE);
      }

      // Recheck expiry after connection borrow wait
      if (isExpired(expiresAt, now, startNanos)) {
        closeQuietly(connection);
        return false;
      }

      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      connection.setAutoCommit(false);

      // Step 1: Plain unique insert of the claim
      int insertTimeout = computeQueryTimeoutSeconds(expiresAt, now, startNanos);
      if (insertTimeout <= 0) {
        connection.rollback();
        closeQuietly(connection);
        return false;
      }
      try (PreparedStatement insert =
          connection.prepareStatement("""
          INSERT INTO tp_workload_replay_claims
            (claim_hash, tenant_hash, token_fingerprint, expires_at, claimed_at)
          VALUES (?, ?, ?, ?, ?)
          """)) {
        insert.setQueryTimeout(insertTimeout);
        insert.setString(1, claimHash);
        insert.setString(2, tenantHash);
        insert.setString(3, tokenFingerprint);
        setTimestamp(insert, 4, expiresAt, engine);
        setTimestamp(insert, 5, now, engine);
        insert.executeUpdate();
      } catch (SQLException insertFailure) {
        connection.rollback();
        closeQuietly(connection);
        if (engine.isDuplicateKey(insertFailure)) {
          return false;
        }
        if (engine.isPermissionDenied(insertFailure)) {
          throw new WorkloadIdentityReplayException(REPLAY_STORAGE_PERMISSION_DENIED);
        }
        throw new WorkloadIdentityReplayException(REPLAY_STORAGE_UNAVAILABLE);
      }

      // Recheck expiry after insert wait
      if (isExpired(expiresAt, now, startNanos)) {
        connection.rollback();
        closeQuietly(connection);
        return false;
      }

      // Step 2: Atomic capacity check and counter update
      int updateTimeout = computeQueryTimeoutSeconds(expiresAt, now, startNanos);
      if (updateTimeout <= 0) {
        connection.rollback();
        closeQuietly(connection);
        return false;
      }
      try (PreparedStatement updateCounter =
          connection.prepareStatement("""
          UPDATE tp_workload_replay_state
             SET total_claims = total_claims + 1
           WHERE singleton = 1 AND capacity = ? AND total_claims < capacity
          """)) {
        updateCounter.setQueryTimeout(updateTimeout);
        updateCounter.setLong(1, capacity);
        int updated = updateCounter.executeUpdate();
        if (updated == 0) {
          // Saturated or capacity mismatch: rollback claim insert
          connection.rollback();
          closeQuietly(connection);
          return false;
        }
      } catch (SQLException updateFailure) {
        connection.rollback();
        closeQuietly(connection);
        if (engine.isPermissionDenied(updateFailure)) {
          throw new WorkloadIdentityReplayException(REPLAY_STORAGE_PERMISSION_DENIED);
        }
        throw new WorkloadIdentityReplayException(REPLAY_STORAGE_UNAVAILABLE);
      }

      // Recheck expiry after counter update lock wait
      if (isExpired(expiresAt, now, startNanos)) {
        connection.rollback();
        closeQuietly(connection);
        return false;
      }

      // Step 3: Commit transaction
      commitAttempted = true;
      connection.commit();

      // Post-commit expiry check: if commit wait crossed expiry, cannot acknowledge success
      if (isExpired(expiresAt, now, startNanos)) {
        closeQuietly(connection);
        throw new WorkloadIdentityReplayException(REPLAY_COMMIT_UNKNOWN);
      }

      // Step 4: Close connection and acknowledge cleanup
      try {
        connection.close();
      } catch (SQLException closeEx) {
        throw new WorkloadIdentityReplayException(REPLAY_COMMIT_UNKNOWN);
      }

      // Final post-cleanup expiry check: if cleanup crossed expiry, cannot acknowledge success
      if (isExpired(expiresAt, now, startNanos)) {
        throw new WorkloadIdentityReplayException(REPLAY_COMMIT_UNKNOWN);
      }

      return true;
    } catch (WorkloadIdentityReplayException failure) {
      closeQuietly(connection);
      throw failure;
    } catch (SQLException failure) {
      if (commitAttempted) {
        closeQuietly(connection);
        throw new WorkloadIdentityReplayException(REPLAY_COMMIT_UNKNOWN);
      }
      try {
        if (connection != null) {
          connection.rollback();
        }
      } catch (SQLException ignored) {
      }
      closeQuietly(connection);
      throw new WorkloadIdentityReplayException(REPLAY_STORAGE_UNAVAILABLE);
    }
  }

  private boolean isExpired(Instant expiresAt, Instant now, long startNanos) {
    long elapsedNanos = Math.max(0L, System.nanoTime() - startNanos);
    Instant conservativeNow = now.plusNanos(elapsedNanos);
    return !expiresAt.isAfter(conservativeNow);
  }

  private int computeQueryTimeoutSeconds(Instant expiresAt, Instant now, long startNanos) {
    long elapsedNanos = Math.max(0L, System.nanoTime() - startNanos);
    Instant conservativeNow = now.plusNanos(elapsedNanos);
    Duration remaining = Duration.between(conservativeNow, expiresAt);
    if (remaining.isNegative() || remaining.isZero()) {
      return 0;
    }
    long remainingSecs = (long) Math.ceil(remaining.toMillis() / 1000.0);
    long boundedSecs = Math.min(dmlTimeout.toSeconds(), Math.max(1L, remainingSecs));
    return (int) Math.max(1L, boundedSecs);
  }

  private static void closeQuietly(Connection connection) {
    if (connection != null) {
      try {
        connection.close();
      } catch (SQLException ignored) {
      }
    }
  }

  public long capacity() {
    return capacity;
  }

  public Duration dmlTimeout() {
    return dmlTimeout;
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
    } catch (WorkloadIdentityReplayException failure) {
      throw failure;
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
    } catch (WorkloadIdentityReplayException failure) {
      throw failure;
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
