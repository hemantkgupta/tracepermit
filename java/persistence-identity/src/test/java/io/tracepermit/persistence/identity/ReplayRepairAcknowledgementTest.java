package io.tracepermit.persistence.identity;

import static io.tracepermit.persistence.identity.WorkloadIdentityReplayException.Code.REPLAY_COMMIT_UNKNOWN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/** Additional PR113 evidence for SEC-006 and REL-001/004; no admission or target wiring. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class ReplayRepairAcknowledgementTest {
  private static final String TENANT = "synthetic-qualification-tenant";
  private static final String ISSUER = "synthetic-qualification-issuer";
  private static final Duration VALIDITY = Duration.ofSeconds(1);
  private final Map<Profile, Fixture> fixtures = new EnumMap<>(Profile.class);

  enum Profile {
    POSTGRES_16_4,
    MARIA_11_4_13,
    MARIA_10_6_25
  }

  private enum Delay {
    NONE,
    BORROW,
    COMMIT,
    CLOSE
  }

  @AfterAll
  void stopOwnedContainers() {
    fixtures.values().forEach(fixture -> fixture.database.stop());
  }

  @ParameterizedTest
  @EnumSource(Profile.class)
  void successfulCommitReturningAfterExpiryCannotAcknowledgeConsumption(Profile profile) {
    assertLateAcknowledgement(profile, Delay.COMMIT, "late-commit");
  }

  @ParameterizedTest
  @EnumSource(Profile.class)
  void successfulCleanupReturningAfterExpiryCannotAcknowledgeConsumption(Profile profile) {
    assertLateAcknowledgement(profile, Delay.CLOSE, "late-close");
  }

  private void assertLateAcknowledgement(Profile profile, Delay delay, String token) {
    Fixture fixture = fixture(profile);
    GateSource source = new GateSource(fixture.source);
    var store = new JdbcWorkloadIdentityReplayStore(source);
    long before = fixture.observer.totalClaims();
    Instant now = source.arm(delay);

    assertThatThrownBy(() -> store.consume(TENANT, ISSUER, token, now.plus(VALIDITY), now))
        .isInstanceOfSatisfying(WorkloadIdentityReplayException.class, failure -> {
          assertThat(failure.code()).isEqualTo(REPLAY_COMMIT_UNKNOWN);
          assertThat(failure.getCause()).isNull();
          assertThat(failure.getSuppressed()).isEmpty();
        });

    assertThat(source.delayed.get()).as("selected JDBC stage actually delayed").isTrue();
    assertThat(source.closed.get()).as("real connection closed").isTrue();
    assertThat(source.dmlTimeouts).containsExactly(1, 1);
    assertThat(fixture.observer.totalClaims()).isEqualTo(before + 1);
    assertThat(fixture.observer.isClaimed(TENANT, ISSUER, token)).isTrue();
    Instant retryNow = Instant.now();
    assertThat(fixture.observer.consume(TENANT, ISSUER, token, retryNow.plusSeconds(60), retryNow))
        .as("committed claim still refuses replay despite lost affirmative acknowledgement")
        .isFalse();
  }

  @ParameterizedTest
  @EnumSource(Profile.class)
  void connectionBorrowReturningAfterExpiryWritesNothingAndCloses(Profile profile) {
    Fixture fixture = fixture(profile);
    GateSource source = new GateSource(fixture.source);
    var store = new JdbcWorkloadIdentityReplayStore(source);
    long before = fixture.observer.totalClaims();
    Instant now = source.arm(Delay.BORROW);
    String token = "late-borrow";

    assertThat(store.consume(TENANT, ISSUER, token, now.plus(VALIDITY), now)).isFalse();
    assertThat(source.delayed.get()).isTrue();
    assertThat(source.closed.get()).isTrue();
    assertThat(source.dmlTimeouts).isEmpty();
    assertThat(fixture.observer.totalClaims()).isEqualTo(before);
    assertThat(fixture.observer.isClaimed(TENANT, ISSUER, token)).isFalse();
    Instant retryNow = Instant.now();
    assertThat(fixture.observer.consume(TENANT, ISSUER, token, retryNow.plusSeconds(60), retryNow))
        .as("expired borrow did not poison the claim")
        .isTrue();
  }

  @ParameterizedTest
  @EnumSource(Profile.class)
  void bothRealDmlStatementsHaveFiniteConfiguredAndExpiryBudgets(Profile profile) {
    Fixture fixture = fixture(profile);
    GateSource configured = new GateSource(fixture.source);
    var configuredStore = new JdbcWorkloadIdentityReplayStore(
        configured, WorkloadIdentityReplayDatabase.DEFAULT_CAPACITY, Duration.ofSeconds(2));
    configured.closed.set(false);
    Instant now = Instant.now();
    assertThat(configuredStore.consume(TENANT, ISSUER, "configured-budget", now.plusSeconds(60), now))
        .isTrue();
    assertThat(configured.dmlTimeouts).containsExactly(2, 2);
    assertThat(configured.closed.get()).isTrue();

    GateSource expiring = new GateSource(fixture.source);
    var expiringStore = new JdbcWorkloadIdentityReplayStore(expiring);
    expiring.closed.set(false);
    now = Instant.now();
    assertThat(expiringStore.consume(TENANT, ISSUER, "expiry-budget", now.plus(VALIDITY), now))
        .isTrue();
    assertThat(expiring.dmlTimeouts).containsExactly(1, 1);
    assertThat(expiring.closed.get()).isTrue();
  }

  private Fixture fixture(Profile profile) {
    return fixtures.computeIfAbsent(profile, Fixture::new);
  }

  private static final class Fixture {
    private final JdbcDatabaseContainer<?> database;
    private final DataSource source;
    private final JdbcWorkloadIdentityReplayStore observer;

    Fixture(Profile profile) {
      database = switch (profile) {
        case POSTGRES_16_4 -> new PostgreSQLContainer<>("postgres:16.4-alpine");
        case MARIA_11_4_13 -> new MariaDBContainer<>("mariadb:11.4.13");
        case MARIA_10_6_25 -> new MariaDBContainer<>("mariadb:10.6.25");
      };
      try {
        database.withPassword(UUID.randomUUID().toString());
        database.start();
        source = new WorkloadIdentityReplayStoreContract.TestSource(
            database.getJdbcUrl(), database.getUsername(), database.getPassword());
        WorkloadIdentityReplayDatabase.initializeEmpty(source);
        observer = new JdbcWorkloadIdentityReplayStore(source);
      } catch (RuntimeException | Error failure) {
        database.stop();
        throw failure;
      }
    }
  }

  /** Delays successful real JDBC calls, rather than injecting an exception or faking a commit. */
  private static final class GateSource implements DataSource {
    private final DataSource delegate;
    private final List<Integer> dmlTimeouts = new ArrayList<>();
    private final AtomicBoolean delayed = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private Delay delay = Delay.NONE;
    private long expiryNanos;

    GateSource(DataSource delegate) {
      this.delegate = delegate;
    }

    Instant arm(Delay selected) {
      delay = selected;
      closed.set(false);
      expiryNanos = System.nanoTime() + VALIDITY.toNanos();
      return Instant.now();
    }

    private void delayPastExpiry(Delay stage) throws SQLException {
      if (delay != stage || !delayed.compareAndSet(false, true)) {
        return;
      }
      long remaining = expiryNanos - System.nanoTime();
      assertThat(remaining).as("JDBC stage reached while token was still valid").isPositive();
      try {
        TimeUnit.NANOSECONDS.sleep(remaining + Duration.ofMillis(100).toNanos());
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new SQLException("synthetic qualification interrupted", failure);
      }
    }

    @Override
    public Connection getConnection() throws SQLException {
      Connection connection = delegate.getConnection();
      try {
        delayPastExpiry(Delay.BORROW);
      } catch (SQLException | Error failure) {
        connection.close();
        throw failure;
      }
      AtomicBoolean committed = new AtomicBoolean();
      return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
          new Class<?>[] {Connection.class}, (proxy, method, args) -> {
            Object result = invoke(method, connection, args);
            if (method.getName().equals("commit")) {
              committed.set(true);
              delayPastExpiry(Delay.COMMIT);
            }
            if (method.getName().equals("close")) {
              closed.set(true);
              if (committed.get()) {
                delayPastExpiry(Delay.CLOSE);
              }
            }
            if (method.getName().equals("prepareStatement") && args[0] instanceof String sql
                && (sql.contains("INSERT INTO tp_workload_replay_claims")
                    || sql.contains("UPDATE tp_workload_replay_state"))) {
              PreparedStatement statement = (PreparedStatement) result;
              return Proxy.newProxyInstance(getClass().getClassLoader(),
                  new Class<?>[] {PreparedStatement.class}, (ignored, statementMethod, statementArgs) -> {
                    if (statementMethod.getName().equals("executeUpdate")) {
                      dmlTimeouts.add(statement.getQueryTimeout());
                    }
                    return invoke(statementMethod, statement, statementArgs);
                  });
            }
            return result;
          });
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
      try {
        return method.invoke(target, args);
      } catch (InvocationTargetException failure) {
        throw failure.getCause();
      }
    }

    @Override public Connection getConnection(String user, String password) throws SQLException {
      throw new SQLFeatureNotSupportedException();
    }
    @Override public PrintWriter getLogWriter() { return null; }
    @Override public void setLogWriter(PrintWriter writer) {}
    @Override public void setLoginTimeout(int seconds) {}
    @Override public int getLoginTimeout() { return 5; }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException {
      throw new SQLFeatureNotSupportedException();
    }
    @Override public <T> T unwrap(Class<T> type) throws SQLException {
      throw new SQLFeatureNotSupportedException();
    }
    @Override public boolean isWrapperFor(Class<?> type) { return false; }
  }
}
