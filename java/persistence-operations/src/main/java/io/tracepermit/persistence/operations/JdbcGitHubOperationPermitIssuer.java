package io.tracepermit.persistence.operations;

import static io.tracepermit.application.BusinessOperationStorageException.Code.*;
import static io.tracepermit.persistence.operations.JdbcBusinessOperationRepository.read;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.adapters.remote.github.GitHubBusinessOperationBinding;
import io.tracepermit.application.BusinessOperationAuthorityProfile;
import io.tracepermit.application.BusinessOperationExecution;
import io.tracepermit.application.BusinessOperationStorageException;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import java.security.PrivateKey;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * Opt-in closed issuer loading actual approved/current state. The read transaction AND cleanup
 * finish before signing. No public proposed claims, clock/signing callback, consumption, dispatch,
 * target or network port. An issued permit alone is NOT admission or execution authority.
 */
public final class JdbcGitHubOperationPermitIssuer {
  private final DataSource source;
  private final BusinessOperationAuthorityProfile profile;
  private final GitHubBusinessOperationBinding.ExpectedProfile target;
  private final OperationPermitCodec codec;

  public JdbcGitHubOperationPermitIssuer(DataSource source, BusinessOperationAuthorityProfile profile,
      GitHubBusinessOperationBinding.ExpectedProfile target, PrivateKey privateKey) {
    if (source == null || profile == null || target == null || privateKey == null) throw error(OPERATION_COMMAND_INVALID);
    this.source = source;
    this.profile = profile;
    this.target = target;
    this.codec = new OperationPermitCodec(profile.admissionSettings(), privateKey);
    OperationDatabase.validateReviewExisting(source);
  }

  public IssuedPermit issue(BusinessOperationExecution command) {
    if (command == null) throw error(OPERATION_COMMAND_INVALID);
    if (!profile.scope().equals(command.scope())) throw error(OPERATION_PROFILE_MISMATCH);
    ObjectNode claims;
    try (Connection connection = source.getConnection()) {
      var engine = OperationDatabase.inspectWriter(connection);
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      connection.setAutoCommit(false);
      try {
        OperationAuthorityControl.requireCurrent(connection, profile);
        OperationDatabase.requireReviewVersion(connection);
        var initial = read(connection, command.scope(), "operation_id", command.operationId())
            .orElseThrow(() -> error(OPERATION_NOT_FOUND));
        var approved = JdbcBusinessOperationLifecycleRepository.current(connection, command.scope(), command.operationId(), initial, true);
        var now = JdbcBusinessOperationLifecycleRepository.storeTime(connection, engine);
        claims = OperationPermitClaims.capture(connection, initial, approved, profile, target, command, now);
      } finally {
        // No write is made. A failed rollback/close means no permit may be returned or signed.
        connection.rollback();
      }
    } catch (BusinessOperationStorageException failure) {
      throw error(failure.code());
    } catch (SQLException | RuntimeException failure) {
      throw error(OPERATION_STORAGE_UNAVAILABLE);
    }
    // Deliberately outside both the transaction and connection lifetime. Signing delay never
    // refreshes the captured issue time/deadlines; a future admission must recheck current state.
    return new IssuedPermit(codec.sign(claims));
  }

  public static final class IssuedPermit {
    private final String serialized;
    private IssuedPermit(String serialized) { this.serialized = serialized; }
    /** Sensitive in-memory transport value. Never log, persist or put in evidence. */
    public String serialized() { return serialized; }
    public String fingerprint() {
      return OperationPermitCodec.fingerprint(serialized);
    }
    @Override public String toString() { return "IssuedOperationPermit[redacted; NOT admitted]"; }
  }

  private static BusinessOperationStorageException error(BusinessOperationStorageException.Code code) {
    return new BusinessOperationStorageException(code);
  }
  @Override public String toString() { return "JdbcGitHubOperationPermitIssuer[redacted; no admission or target]"; }
}
