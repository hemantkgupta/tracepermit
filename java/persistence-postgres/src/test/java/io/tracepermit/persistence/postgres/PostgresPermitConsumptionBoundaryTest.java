package io.tracepermit.persistence.postgres;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.TenantPartition;
import io.tracepermit.security.ExecutionPermitAuthority;
import io.tracepermit.security.ExecutionPermitException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class PostgresPermitConsumptionBoundaryTest {
  private static final Path ROOT = Path.of(System.getProperty("tracepermit.repositoryRoot"));
  private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");
  private static final TenantPartition ACME = new TenantPartition("acme");
  private static final String ACTION_HASH = "sha256:" + "a".repeat(64);
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16.4-alpine");

  @BeforeAll
  static void startDatabase() {
    POSTGRES.start();
  }

  @AfterAll
  static void stopDatabase() {
    POSTGRES.stop();
  }

  @Test
  void changingTenantCannotConsumeAnAlreadyUsedSignedPermitAgain() {
    var store = new PostgresPermitConsumptionStore(source(), ACME);
    var authority = authority(store);
    JsonNode vector = vector();
    JsonNode event = event(vector);
    String audience = vector.path("audience").asText();
    String permit = authority.issue(event, vector.path("evaluation"), audience);
    JsonNode claims = authority.verifyAndConsume("acme", permit, event, audience);
    assertThat(store.isConsumed("acme", claims.path("permitId").asText())).isTrue();
    assertThatThrownBy(() -> authority.verifyAndConsume("other", permit, event, audience))
        .isInstanceOf(ExecutionPermitException.class)
        .extracting("code").isEqualTo("PERMIT_TENANT_MISMATCH");
    assertThat(store.isConsumed("other", claims.path("permitId").asText())).isFalse();
    assertThatThrownBy(() -> authority.verifyAndConsume(permit, event, audience))
        .isInstanceOf(ExecutionPermitException.class)
        .extracting("code").isEqualTo("EXECUTION_PERMIT_REPLAYED");
  }

  @Test
  void invalidTenantAndChangedActionFailBeforeAnyClaim() {
    var store = new PostgresPermitConsumptionStore(source(), ACME);
    var authority = authority(store);
    JsonNode vector = vector();
    ObjectNode event = (ObjectNode) event(vector);
    String audience = vector.path("audience").asText();
    String permit = authority.issue(event, vector.path("evaluation"), audience);
    String id = authority.verify(permit, event, audience).path("permitId").asText();
    for (String tenant : new String[] {null, "", "bad tenant", "other"}) {
      assertThatThrownBy(() -> authority.verifyAndConsume(tenant, permit, event, audience))
          .isInstanceOf(ExecutionPermitException.class)
          .extracting("code").isEqualTo("PERMIT_TENANT_MISMATCH");
      assertThat(store.isConsumed("acme", id)).isFalse();
      assertThat(store.isConsumed("other", id)).isFalse();
    }
    ObjectNode changed = event.deepCopy();
    ((ObjectNode) changed.path("identity").path("subject")).put("organizationId", "other");
    assertThatThrownBy(() -> authority.verifyAndConsume("other", permit, changed, audience))
        .isInstanceOf(ExecutionPermitException.class)
        .extracting("code").isEqualTo("PERMIT_ACTION_MISMATCH");
    assertThat(store.isConsumed("other", id)).isFalse();
    assertThat(authority.verifyAndConsume("acme", permit, event, audience).path("permitId").asText())
        .isEqualTo(id);
  }

  @Test
  void implicitConsumptionUsesVerifiedTenantNotConfiguredDefault() {
    var store = new PostgresPermitConsumptionStore(source(), "other");
    var authority = authority(store);
    JsonNode vector = vector();
    JsonNode event = event(vector);
    String audience = vector.path("audience").asText();
    String permit = authority.issue(event, vector.path("evaluation"), audience);
    String id = authority.verifyAndConsume(permit, event, audience).path("permitId").asText();
    assertThat(store.isConsumed("acme", id)).isTrue();
    assertThat(store.isConsumed("other", id)).isFalse();
    assertThatThrownBy(() -> authority.verifyAndConsume("acme", permit, event, audience))
        .isInstanceOf(ExecutionPermitException.class)
        .extracting("code").isEqualTo("EXECUTION_PERMIT_REPLAYED");
  }

  @Test
  void tenantlessPermitCannotFallBackToADurableDefaultPartition() {
    var store = new PostgresPermitConsumptionStore(source(), ACME);
    var authority = authority(store);
    JsonNode vector = vector();
    ObjectNode event = (ObjectNode) event(vector);
    ((ObjectNode) event.path("identity").path("subject")).remove("organizationId");
    String audience = vector.path("audience").asText();
    String permit = authority.issue(event, vector.path("evaluation"), audience);
    String id = authority.verify(permit, event, audience).path("permitId").asText();
    assertThatThrownBy(() -> authority.verifyAndConsume(permit, event, audience))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(store.isConsumed("acme", id)).isFalse();
    assertThat(store.isConsumed("default", id)).isFalse();
  }

  @Test
  void returningSuccessRequiresACommittedConsumptionWhenAutoCommitIsDisabled() {
    var source = faultSource();
    var store = new PostgresPermitConsumptionStore(source.dataSource(), ACME);
    source.fault().set(Fault.MANUAL);
    var authority = authority(store);
    JsonNode vector = vector();
    JsonNode event = event(vector);
    String audience = vector.path("audience").asText();
    String permit = authority.issue(event, vector.path("evaluation"), audience);
    String id = authority.verifyAndConsume(permit, event, audience).path("permitId").asText();
    var independentReader = new PostgresPermitConsumptionStore(source(), ACME);
    assertThat(independentReader.isConsumed(id)).isTrue();
    assertThat(store.consume(id, NOW.plusSeconds(30), CanonicalJson.sha256(event), NOW)).isFalse();
  }

  @Test
  void failedCommitRollsBackAndCannotReachAnEffect() {
    var source = faultSource();
    var store = new PostgresPermitConsumptionStore(source.dataSource(), ACME);
    source.fault().set(Fault.BEFORE_COMMIT);
    String id = id();
    var effects = new AtomicInteger();
    assertThatThrownBy(() -> invokeAfterConsumption(store, id, effects))
        .isInstanceOf(PostgresStateException.class);
    assertThat(effects.get()).isZero();
    var independent = new PostgresPermitConsumptionStore(source(), ACME);
    assertThat(independent.isConsumed(id)).isFalse();
    assertThat(independent.consume(id, NOW.plusSeconds(30), ACTION_HASH, NOW)).isTrue();
  }

  @Test
  void lostCommitAcknowledgementIsNotSuccessAndDoesNotPermitReplay() {
    var source = faultSource();
    var store = new PostgresPermitConsumptionStore(source.dataSource(), ACME);
    source.fault().set(Fault.AFTER_COMMIT);
    String id = id();
    var effects = new AtomicInteger();
    assertThatThrownBy(() -> invokeAfterConsumption(store, id, effects))
        .isInstanceOf(PostgresStateException.class);
    assertThat(effects.get()).isZero();
    var independent = new PostgresPermitConsumptionStore(source(), ACME);
    assertThat(independent.isConsumed(id)).isTrue();
    assertThat(independent.consume(id, NOW.plusSeconds(30), ACTION_HASH, NOW)).isFalse();
  }

  @Test
  void rollbackFailureIsSuppressedWithoutEnablingAutoCommit() {
    var source = faultSource();
    var store = new PostgresPermitConsumptionStore(source.dataSource(), ACME);
    source.fault().set(Fault.COMMIT_AND_ROLLBACK);
    String id = id();
    assertThatThrownBy(() -> store.consume(id, NOW.plusSeconds(30), ACTION_HASH, NOW))
        .isInstanceOf(PostgresStateException.class)
        .satisfies(failure -> {
          assertThat(failure.getCause()).hasMessage("synthetic commit failure");
          assertThat(failure.getCause().getSuppressed()).hasSize(1);
        });
    assertThat(new PostgresPermitConsumptionStore(source(), ACME).isConsumed(id)).isFalse();
  }

  @Test
  void pruningCommitsManualTransactionsAndRollsBackFailedCommits() {
    var source = faultSource();
    var store = new PostgresPermitConsumptionStore(source.dataSource(), ACME);
    String id = id();
    assertThat(store.consume(id, NOW.plusSeconds(10), ACTION_HASH, NOW)).isTrue();
    var independent = new PostgresPermitConsumptionStore(source(), ACME);
    source.fault().set(Fault.BEFORE_COMMIT);
    assertThatThrownBy(() -> store.pruneExpiredUnreferenced(NOW.plusSeconds(11)))
        .isInstanceOf(PostgresStateException.class);
    assertThat(independent.isConsumed(id)).isTrue();
    source.fault().set(Fault.MANUAL);
    assertThat(store.pruneExpiredUnreferenced(NOW.plusSeconds(11))).isPositive();
    assertThat(independent.isConsumed(id)).isFalse();
  }

  @Test
  void expiryAndMissingHashRejectWithoutAcquiringAConnection() {
    var source = faultSource();
    var store = new PostgresPermitConsumptionStore(source.dataSource(), ACME);
    source.fault().set(Fault.ACQUIRE);
    String id = id();
    assertThat(store.consume(id, NOW, ACTION_HASH, NOW)).isFalse();
    assertThatThrownBy(() -> store.consume(id, NOW.plusSeconds(30), NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.consume("other", id, NOW.plusSeconds(30), NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.consume(id, NOW.plusSeconds(30), ACTION_HASH, NOW))
        .isInstanceOf(PostgresStateException.class);
    assertThatThrownBy(() -> store.isConsumed(id)).isInstanceOf(PostgresStateException.class);
    assertThatThrownBy(() -> store.pruneExpiredUnreferenced(NOW))
        .isInstanceOf(PostgresStateException.class);
    assertThat(new PostgresPermitConsumptionStore(source(), ACME).isConsumed(id)).isFalse();
  }

  @Test
  void closeFailureAfterCommitNeverReportsSuccess() {
    var source = faultSource();
    var store = new PostgresPermitConsumptionStore(source.dataSource(), ACME);
    source.fault().set(Fault.CLOSE);
    String id = id();
    var effects = new AtomicInteger();
    assertThatThrownBy(() -> invokeAfterConsumption(store, id, effects))
        .isInstanceOf(PostgresStateException.class);
    assertThat(effects.get()).isZero();
    assertThat(new PostgresPermitConsumptionStore(source(), ACME).isConsumed(id)).isTrue();
  }

  @Test
  void uncheckedStatementFailureAlsoRollsBack() {
    var source = faultSource();
    var store = new PostgresPermitConsumptionStore(source.dataSource(), ACME);
    source.fault().set(Fault.RUNTIME);
    String id = id();
    assertThatThrownBy(() -> store.consume(id, NOW.plusSeconds(30), ACTION_HASH, NOW))
        .isInstanceOf(IllegalStateException.class).hasMessage("synthetic statement failure");
    assertThat(new PostgresPermitConsumptionStore(source(), ACME).isConsumed(id)).isFalse();
  }

  private static void invokeAfterConsumption(
      PostgresPermitConsumptionStore store, String id, AtomicInteger effects) {
    if (store.consume(id, NOW.plusSeconds(30), ACTION_HASH, NOW)) effects.incrementAndGet();
  }

  private static ExecutionPermitAuthority authority(PostgresPermitConsumptionStore store) {
    return new ExecutionPermitAuthority(
        ExecutionPermitAuthority.DEFAULT_ISSUER, "synthetic-boundary-key", new byte[32], 30,
        Clock.fixed(NOW, ZoneOffset.UTC), PostgresPermitConsumptionBoundaryTest::id, store);
  }

  private static JsonNode vector() {
    return JsonSupport.read(ROOT.resolve("contracts/vectors/execution-permit-v1.json"));
  }

  private static JsonNode event(JsonNode vector) {
    return JsonSupport.read(ROOT.resolve(vector.path("fixture").asText()));
  }

  private static String id() {
    return "boundary-" + UUID.randomUUID();
  }

  private enum Fault { NONE, MANUAL, BEFORE_COMMIT, AFTER_COMMIT, COMMIT_AND_ROLLBACK, ACQUIRE, CLOSE, RUNTIME }

  private record FaultSource(DataSource dataSource, AtomicReference<Fault> fault) {}

  private static FaultSource faultSource() {
    var delegate = source();
    var mode = new AtomicReference<>(Fault.NONE);
    DataSource wrapped = (DataSource) Proxy.newProxyInstance(
        DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
        (proxy, method, args) -> {
          if (method.getName().equals("getConnection") && mode.get() == Fault.ACQUIRE) {
            throw new SQLException("synthetic acquisition failure");
          }
          Object result = invoke(delegate, method, args);
          if (!(result instanceof Connection connection) || mode.get() == Fault.NONE) return result;
          Fault fault = mode.get();
          connection.setAutoCommit(false);
          return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
              (connectionProxy, connectionMethod, connectionArgs) -> {
                String name = connectionMethod.getName();
                if (name.equals("commit")
                    && (fault == Fault.BEFORE_COMMIT || fault == Fault.COMMIT_AND_ROLLBACK)) {
                  throw new SQLException("synthetic commit failure");
                }
                if (name.equals("rollback") && fault == Fault.COMMIT_AND_ROLLBACK) {
                  throw new SQLException("synthetic rollback failure");
                }
                if (name.equals("prepareStatement") && fault == Fault.RUNTIME) {
                  throw new IllegalStateException("synthetic statement failure");
                }
                Object value = invoke(connection, connectionMethod, connectionArgs);
                if (name.equals("commit") && fault == Fault.AFTER_COMMIT) {
                  throw new SQLException("synthetic lost commit acknowledgement");
                }
                if (name.equals("close") && fault == Fault.CLOSE) {
                  throw new SQLException("synthetic close failure");
                }
                return value;
              });
        });
    return new FaultSource(wrapped, mode);
  }

  private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException exception) {
      throw exception.getCause();
    }
  }

  private static PGSimpleDataSource source() {
    var source = new PGSimpleDataSource();
    source.setUrl(POSTGRES.getJdbcUrl());
    source.setUser(POSTGRES.getUsername());
    source.setPassword(POSTGRES.getPassword());
    return source;
  }
}
