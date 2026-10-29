package io.tracepermit.persistence.operations;

import static io.tracepermit.application.BusinessOperationStorageException.Code.*;
import static io.tracepermit.persistence.operations.QuarantineAdmissionFixture.*;
import static io.tracepermit.persistence.operations.QuarantineAdmissionSource.Fault.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.application.BusinessOperationAuthorityProfile;
import io.tracepermit.application.BusinessOperationScope;
import io.tracepermit.application.BusinessOperationStorageException;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Component proof only: no installed org admin/probe, delivery, target or live infrastructure. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(120)
final class PostgresQuarantineAdmissionTest {
  private final QuarantineAdmissionFixture fixture = new QuarantineAdmissionFixture();

  @BeforeAll void start() { fixture.start(); }
  @BeforeEach void reset() throws Exception { fixture.reset(); }
  @AfterEach void noOwnedConnectionsRemain() {
    fixture.assertNoOwnedConnections();
    var sources = List.of(fixture.owner, fixture.runtime, fixture.observer);
    var roles = List.of("owner", "runtime", "observer");
    for (int index = 0; index < sources.size(); index++) {
      var source = sources.get(index);
      assertThat(source.commits.get()).isEqualTo(source.quarantineCommits.get()
          + source.admissionCommits.get() + source.unselectedCommits.get());
      System.out.printf("QUARANTINE_JDBC_COUNTS role=%s total=%d quarantine=%d admission=%d unselected=%d open=%d%n",
          roles.get(index), source.commits.get(), source.quarantineCommits.get(),
          source.admissionCommits.get(), source.unselectedCommits.get(), source.open.get());
    }
  }
  @AfterAll void stop() { fixture.close(); }

  @Test void readyPositiveCalibrationAdmitsExactlyOnceWithoutDelivery() throws Exception {
    JsonNode approved = fixture.approved();
    var repository = fixture.admission();
    String permit = fixture.issuer().issue(fixture.execution(approved, fresh())).serialized();
    var before = fixture.control();
    assertValidTime(permit);
    JsonNode admitted = repository.admit(fixture.execution(approved, fresh()), permit);
    assertThat(admitted.path("execution").path("status").asText()).isEqualTo("claimed");
    assertClaimedWithoutDelivery();
    assertThat(fixture.control()).isEqualTo(before);
    assertThat(fixture.runtime.admissionWrites.get()).isEqualTo(1);
    assertThat(fixture.runtime.admissionCommits.get()).isEqualTo(1);
    assertThat(fixture.runtime.quarantineCommits.get()).isZero();
  }

  @Test void preissuedUnspentPermitReachesAdmissionAfterAcknowledgedExactQuarantine() throws Exception {
    JsonNode approved = fixture.approved();
    var repository = fixture.admission();
    String permit = fixture.issuer().issue(fixture.execution(approved, fresh())).serialized();
    var before = fixture.control(); var data = fixture.data();
    OperationAuthorityAdministration.quarantine(fixture.owner, fixture.profile);
    fixture.assertQuarantined(before);
    assertThat(fixture.owner.quarantineWrites.get()).isEqualTo(1);
    assertThat(fixture.owner.quarantineCommits.get()).isEqualTo(1);
    assertThat(fixture.owner.admissionCommits.get()).isZero();
    assertValidTime(permit);
    assertCode(() -> repository.admit(fixture.execution(approved, fresh()), permit), OPERATION_AUTHORITY_NOT_READY);
    assertThat(fixture.runtime.admissionWrites.get()).isZero();
    assertThat(fixture.data()).isEqualTo(data);
    fixture.assertQuarantined(before);
  }

  @Test void issuerRefusalAfterQuarantineIsNotAnAdmissionProbe() throws Exception {
    JsonNode approved = fixture.approved(); var issuer = fixture.issuer();
    var before = fixture.control(); var data = fixture.data();
    OperationAuthorityAdministration.quarantine(fixture.owner, fixture.profile);
    assertCode(() -> issuer.issue(fixture.execution(approved, fresh())), OPERATION_AUTHORITY_NOT_READY);
    fixture.assertQuarantined(before);
    assertThat(fixture.runtime.admissionWrites.get()).isZero();
    assertThat(fixture.data()).isEqualTo(data);
  }

  @ParameterizedTest
  @EnumSource(value = QuarantineAdmissionSource.Fault.class, names = {"QUARANTINE_COMMIT", "QUARANTINE_CLOSE"})
  void quarantineUnknownRemainsUnknownDespiteIndependentReadback(QuarantineAdmissionSource.Fault fault) throws Exception {
    fixture.approved();
    var before = fixture.control(); var data = fixture.data();
    fixture.owner.fault = fault;
    try { assertCode(() -> OperationAuthorityAdministration.quarantine(fixture.owner, fixture.profile), OPERATION_COMMIT_UNKNOWN); }
    finally { fixture.owner.fault = NONE; }
    assertThat(fixture.owner.injected.get()).isEqualTo(1);
    assertThat(fixture.owner.quarantineWrites.get()).isEqualTo(1);
    assertThat(fixture.owner.quarantineCommits.get()).isEqualTo(1);
    assertThat(fixture.owner.admissionCommits.get()).isZero();
    fixture.assertQuarantined(before);
    fixture.assertQuarantined(before); // A second read never promotes this call to acknowledged success.
    assertThat(fixture.data()).isEqualTo(data);
    assertThat(fixture.runtime.admissionWrites.get()).isZero();
    assertThat(fixture.owner.quarantineWrites.get()).isEqualTo(1);
  }

  @ParameterizedTest
  @EnumSource(value = QuarantineAdmissionSource.Fault.class, names = {"ADMISSION_COMMIT", "ADMISSION_CLOSE"})
  void admissionUnknownNeverContinuesToDispatchOrResends(QuarantineAdmissionSource.Fault fault) throws Exception {
    JsonNode approved = fixture.approved(); var repository = fixture.admission();
    String permit = fixture.issuer().issue(fixture.execution(approved, fresh())).serialized();
    var before = fixture.control();
    fixture.runtime.resetObservations(); fixture.runtime.fault = fault;
    assertValidTime(permit);
    try { assertCode(() -> repository.admit(fixture.execution(approved, fresh()), permit), OPERATION_COMMIT_UNKNOWN); }
    finally { fixture.runtime.fault = NONE; }
    assertThat(fixture.runtime.injected.get()).isEqualTo(1);
    assertThat(fixture.runtime.commits.get()).isEqualTo(1);
    assertThat(fixture.runtime.admissionCommits.get()).isEqualTo(1);
    assertThat(fixture.runtime.quarantineCommits.get()).isZero();
    assertThat(fixture.runtime.admissionWrites.get()).isEqualTo(1);
    assertClaimedWithoutDelivery();
    var observed = fixture.data();
    assertThat(fixture.data()).isEqualTo(observed);
    assertThat(fixture.control()).isEqualTo(before);
    assertThat(fixture.runtime.admissionWrites.get()).isEqualTo(1);
  }

  @Test void runtimeAndObserverCannotQuarantineOtherwiseValidReadyControl() throws Exception {
    var before = fixture.control(); var data = fixture.data();
    // Prove the exact values/conditional statement are valid, then roll back the admin fixture.
    try (var connection = fixture.owner.getConnection()) {
      connection.setAutoCommit(false);
      try (var statement = connection.prepareStatement("UPDATE tp_operation_authority_controls SET control_state = 'quarantined' WHERE scope_hash = ? AND profile_hash = ? AND recovery_generation = ? AND control_state = 'ready'")) {
        statement.setQueryTimeout(5); statement.setString(1, before.scope()); statement.setString(2, before.profile()); statement.setLong(3, before.generation());
        assertThat(statement.executeUpdate()).isEqualTo(1);
      } finally { connection.rollback(); }
    }
    assertThat(fixture.control()).isEqualTo(before);
    assertThat(fixture.owner.quarantineCommits.get()).isZero();
    assertCode(() -> OperationAuthorityAdministration.quarantine(fixture.runtime, fixture.profile), OPERATION_STORAGE_UNAVAILABLE);
    assertThat(fixture.runtime.deniedState).isEqualTo("42501");
    assertThat(fixture.runtime.quarantineWrites.get()).isEqualTo(1);
    assertThat(fixture.runtime.quarantineCommits.get()).isZero();
    assertCode(() -> OperationAuthorityAdministration.quarantine(fixture.observer, fixture.profile), OPERATION_STORAGE_UNAVAILABLE);
    assertThat(fixture.observer.deniedState).isEqualTo("42501");
    assertThat(fixture.observer.quarantineCommits.get()).isZero();
    try (var connection = fixture.runtime.getConnection()) {
      try (var query = connection.prepareStatement("SELECT has_column_privilege(current_user, 'tp_operation_authority_controls', 'lock_marker', 'UPDATE'), has_table_privilege(current_user, 'tp_operation_delivery_events', 'INSERT')")) {
        query.setQueryTimeout(5);
        try (var rows = query.executeQuery()) {
          assertThat(rows.next()).isTrue(); assertThat(rows.getBoolean(1)).isTrue(); assertThat(rows.getBoolean(2)).isFalse();
        }
      }
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED); connection.setAutoCommit(false);
      try { OperationAuthorityControl.requireCurrent(connection, fixture.profile); }
      finally { connection.rollback(); }
    }
    assertThat(fixture.control()).isEqualTo(before); assertThat(fixture.data()).isEqualTo(data);
  }

  @Test void actualControlLockWaitPrecedesQuarantineAndAdmissionRefusal() throws Exception {
    JsonNode approved = fixture.approved(); var repository = fixture.admission();
    String permit = fixture.issuer().issue(fixture.execution(approved, fresh())).serialized();
    var before = fixture.control(); var data = fixture.data();
    var executor = Executors.newSingleThreadExecutor();
    fixture.owner.quarantineAttempt = new CountDownLatch(1);
    try (var holder = fixture.runtime.getConnection()) {
      holder.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED); holder.setAutoCommit(false);
      OperationAuthorityControl.requireCurrent(holder, fixture.profile);
      var future = executor.submit(() -> OperationAuthorityAdministration.quarantine(fixture.owner, fixture.profile));
      assertThat(fixture.owner.quarantineAttempt.await(5, TimeUnit.SECONDS)).isTrue();
      assertActualLockWait(fixture.owner.quarantinePid.get());
      assertThat(future.isDone()).isFalse();
      assertThat(fixture.control()).isEqualTo(before);
      holder.commit();
      future.get(10, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow(); assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
      fixture.owner.quarantineAttempt = null;
    }
    fixture.assertQuarantined(before);
    assertValidTime(permit);
    assertCode(() -> repository.admit(fixture.execution(approved, fresh()), permit), OPERATION_AUTHORITY_NOT_READY);
    assertThat(fixture.runtime.admissionWrites.get()).isZero(); assertThat(fixture.data()).isEqualTo(data);
  }

  @ParameterizedTest @ValueSource(strings = {"profile", "generation", "scope"})
  void staleAdminPinsCannotChangeAnyControlOrOperationBytes(String mismatch) throws Exception {
    var before = fixture.control(); var data = fixture.data();
    ObjectNode policy = fixture.policy.deepCopy();
    if (mismatch.equals("profile")) policy.put("description", "Different synthetic profile");
    BusinessOperationScope scope = mismatch.equals("scope")
        ? new BusinessOperationScope(SCOPE.tenantId(), "other-synthetic-workspace", SCOPE.requesterPrincipalId()) : SCOPE;
    BusinessOperationAuthorityProfile expected = fixture.profile(scope, mismatch.equals("generation") ? 2 : 1, policy);
    assertCode(() -> OperationAuthorityAdministration.quarantine(fixture.owner, expected), OPERATION_STATE_STALE);
    assertThat(fixture.owner.quarantineCommits.get()).isZero();
    assertThat(fixture.control()).isEqualTo(before); assertThat(fixture.data()).isEqualTo(data);
  }

  @ParameterizedTest @ValueSource(strings = {"missing", "staged"})
  void sameAdmissionRefusalDoesNotProveQuarantineWhenControlIsMissingOrStaged(String state) throws Exception {
    JsonNode approved = fixture.approved(); var repository = fixture.admission();
    String permit = fixture.issuer().issue(fixture.execution(approved, fresh())).serialized();
    if (state.equals("missing")) fixture.sql("DELETE FROM tp_operation_authority_controls");
    else fixture.sql("UPDATE tp_operation_authority_controls SET control_state = 'staged'");
    var data = fixture.data();
    assertValidTime(permit);
    assertCode(() -> repository.admit(fixture.execution(approved, fresh()), permit), OPERATION_AUTHORITY_NOT_READY);
    if (state.equals("missing")) assertThat(fixture.control()).isNull();
    else assertThat(fixture.control().state()).isEqualTo("staged");
    assertThat(fixture.data()).isEqualTo(data); assertThat(fixture.runtime.admissionWrites.get()).isZero();
  }

  @Test void expiredSignedPermitIsRefusedWhileAuthorityIsStillReady() throws Exception {
    JsonNode approved = fixture.approved(); var repository = fixture.admission();
    String permit = fixture.issuer().issue(fixture.execution(approved, fresh())).serialized();
    var codec = new OperationPermitCodec(fixture.profile.admissionSettings(), fixture.keys.getPrivate());
    ObjectNode expired = codec.verify(permit).claims().deepCopy(); Instant now = fixture.storeNow();
    expired.put("issuedAt", now.minusSeconds(20).toString()).put("expiresAt", now.minusSeconds(1).toString());
    String token = codec.sign(expired); var before = fixture.control(); var data = fixture.data();
    assertCode(() -> repository.admit(fixture.execution(approved, fresh()), token), OPERATION_PERMIT_EXPIRED);
    assertThat(fixture.control()).isEqualTo(before); assertThat(before.state()).isEqualTo("ready");
    assertThat(fixture.data()).isEqualTo(data); assertThat(fixture.runtime.admissionWrites.get()).isZero();
  }

  @Test void invalidSignatureRefusalIsNotAStopProof() throws Exception {
    JsonNode approved = fixture.approved(); var repository = fixture.admission();
    String permit = fixture.issuer().issue(fixture.execution(approved, fresh())).serialized();
    var before = fixture.control(); var data = fixture.data();
    assertCode(() -> repository.admit(fixture.execution(approved, fresh()), permit + "x"), OPERATION_PERMIT_INVALID);
    assertThat(fixture.control()).isEqualTo(before); assertThat(before.state()).isEqualTo("ready");
    assertThat(fixture.data()).isEqualTo(data); assertThat(fixture.runtime.admissionWrites.get()).isZero();
  }

  @Test void duplicateQuarantineIsStaleAndDoesNotReactivateOrRewriteHistory() throws Exception {
    fixture.approved(); var before = fixture.control(); var data = fixture.data();
    OperationAuthorityAdministration.quarantine(fixture.owner, fixture.profile);
    assertCode(() -> OperationAuthorityAdministration.quarantine(fixture.owner, fixture.profile), OPERATION_STATE_STALE);
    assertCode(() -> OperationAuthorityAdministration.activateStaged(fixture.owner, fixture.profile), OPERATION_STATE_STALE);
    fixture.assertQuarantined(before); assertThat(fixture.data()).isEqualTo(data);
    assertThat(fixture.owner.quarantineCommits.get()).isEqualTo(1);
    assertThat(fixture.owner.admissionCommits.get()).isZero();
  }

  @Test void syntheticConnectionUnavailabilityIsNotAnEstablishedStop() throws Exception {
    JsonNode approved = fixture.approved(); var repository = fixture.admission();
    String permit = fixture.issuer().issue(fixture.execution(approved, fresh())).serialized();
    var before = fixture.control(); var data = fixture.data();
    assertValidTime(permit); fixture.runtime.unavailable = true;
    try { assertCode(() -> repository.admit(fixture.execution(approved, fresh()), permit), OPERATION_STORAGE_UNAVAILABLE); }
    finally { fixture.runtime.unavailable = false; }
    assertThat(fixture.control()).isEqualTo(before); assertThat(before.state()).isEqualTo("ready");
    assertThat(fixture.data()).isEqualTo(data); assertThat(fixture.runtime.admissionWrites.get()).isZero();
  }

  @Test void expiredFreshRequesterIsRefusedWithReadyAuthorityAndValidPermit() throws Exception {
    JsonNode approved = fixture.approved(); var repository = fixture.admission();
    String permit = fixture.issuer().issue(fixture.execution(approved, fresh())).serialized();
    var before = fixture.control(); var data = fixture.data(); assertValidTime(permit);
    assertCode(() -> repository.admit(fixture.execution(approved, Instant.EPOCH), permit), OPERATION_AUTHENTICATION_EXPIRED);
    assertThat(fixture.control()).isEqualTo(before); assertThat(before.state()).isEqualTo("ready");
    assertThat(fixture.data()).isEqualTo(data); assertThat(fixture.runtime.admissionWrites.get()).isZero();
  }

  private void assertValidTime(String permit) throws Exception {
    JsonNode claims = new OperationPermitCodec(fixture.profile.admissionSettings(), null).verify(permit).claims();
    assertThat(fixture.storeNow()).isBefore(Instant.parse(claims.path("expiresAt").asText()));
  }

  private void assertClaimedWithoutDelivery() throws Exception {
    Map<String, List<String>> data = fixture.data();
    assertThat(data.get("tp_operation_admissions")).hasSize(1);
    assertThat(data.get("tp_operation_admission_outbox")).hasSize(1);
    assertThat(data.get("tp_operation_lifecycle_facts")).hasSize(3);
    assertThat(data.get("tp_operation_delivery_events")).isEmpty();
    JsonNode head = fixture.storedHead();
    assertThat(head.path("execution").path("status").asText()).isEqualTo("claimed");
    assertThat(head.path("authorization").path("status").asText()).isEqualTo("approved");
  }

  private void assertActualLockWait(int pid) throws Exception {
    assertThat(pid).isPositive();
    // Fixture admin is separate from both the blocked connection and SELECT-only observation role.
    try (var connection = fixture.owner.getConnection(); var statement = connection.prepareStatement("SELECT wait_event_type FROM pg_stat_activity WHERE pid = ?")) {
      statement.setQueryTimeout(3); statement.setInt(1, pid);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      do {
        try (var rows = statement.executeQuery()) { if (rows.next() && "Lock".equals(rows.getString(1))) return; }
        Thread.sleep(10);
      } while (System.nanoTime() < deadline);
      fail("SYNTHETIC_QUARANTINE_LOCK_WAIT_NOT_OBSERVED");
    }
  }

  private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, BusinessOperationStorageException.Code code) {
    assertThatThrownBy(action).isInstanceOfSatisfying(BusinessOperationStorageException.class, fixed -> {
      assertThat(fixed.code()).isEqualTo(code); assertThat(fixed.getSuppressed()).isEmpty();
    }).hasMessage(code.name()).hasNoCause();
  }
}
