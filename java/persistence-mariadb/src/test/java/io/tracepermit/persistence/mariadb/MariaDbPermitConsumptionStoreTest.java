package io.tracepermit.persistence.mariadb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tracepermit.execution.TenantPartition;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mariadb.jdbc.MariaDbDataSource;
import org.testcontainers.containers.MariaDBContainer;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class MariaDbPermitConsumptionStoreTest {
  private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");
  private static final String ACTION_HASH = "sha256:" + "a".repeat(64);
  private static final TenantPartition ACME = new TenantPartition("acme");
  private static final TenantPartition OTHER = new TenantPartition("other");

  private final List<QualifiedDatabase> databases = List.of(
      new QualifiedDatabase("11.4.13", new MariaDBContainer<>("mariadb:11.4.13")));

  @BeforeAll
  void startDatabases() throws SQLException {
    for (QualifiedDatabase database : databases) {
      database.container().start();
      database.source(source(database.container()));
      MariaDbDatabaseLifecycle.initializeEmpty(database.source());
    }
  }

  @AfterAll
  void stopDatabases() {
    for (QualifiedDatabase database : databases) {
      database.container().stop();
    }
  }

  Stream<QualifiedDatabase> qualifiedDatabases() {
    return databases.stream();
  }

  @ParameterizedTest(name = "{0}: exact-once tenant-bound consumption")
  @MethodSource("qualifiedDatabases")
  void exactOnceAndTenantIsolation(QualifiedDatabase database) throws SQLException {
    clear(database.source());
    var store = new MariaDbPermitConsumptionStore(database.source(), ACME);
    String permitId = id("exact");
    Instant expiry = NOW.plusSeconds(60);

    assertThat(store.isConsumed(permitId)).isFalse();
    assertThat(store.consume(permitId, expiry, ACTION_HASH, NOW)).isTrue();
    assertThat(store.consume(permitId, expiry, ACTION_HASH, NOW.plusSeconds(1))).isFalse();
    assertThat(store.isConsumed(permitId)).isTrue();

    assertThat(store.isConsumed(OTHER, permitId)).isFalse();
    assertThat(store.consume(OTHER, permitId, expiry, ACTION_HASH, NOW)).isTrue();
    assertThat(store.isConsumed(OTHER, permitId)).isTrue();
  }

  @ParameterizedTest(name = "{0}: 32 concurrent claims")
  @MethodSource("qualifiedDatabases")
  void concurrentClaimsAcrossStoresHaveOneWinner(QualifiedDatabase database) throws Exception {
    clear(database.source());
    String permitId = id("concurrent");
    List<MariaDbPermitConsumptionStore> stores = IntStream.range(0, 4)
        .mapToObj(index -> new MariaDbPermitConsumptionStore(database.source(), ACME))
        .toList();
    CountDownLatch start = new CountDownLatch(1);
    List<Callable<Boolean>> tasks = IntStream.range(0, 32)
        .mapToObj(index -> (Callable<Boolean>) () -> {
          start.await();
          return stores.get(index % stores.size())
              .consume(permitId, NOW.plusSeconds(60), ACTION_HASH, NOW);
        })
        .toList();

    try (var executor = Executors.newFixedThreadPool(16)) {
      var futures = tasks.stream().map(executor::submit).toList();
      start.countDown();
      int winners = 0;
      for (var future : futures) {
        if (future.get()) {
          winners++;
        }
      }
      assertThat(winners).isOne();
    }
    assertThat(stores.getFirst().isConsumed(permitId)).isTrue();
  }

  @ParameterizedTest(name = "{0}: validation and restart")
  @MethodSource("qualifiedDatabases")
  void exactEngineProfileAndRestartAreValidated(QualifiedDatabase database) throws SQLException {
    clear(database.source());
    MariaDbEngineProfile profile = MariaDbDatabaseLifecycle.validateExisting(database.source());
    assertThat(profile.normalizedVersion()).isEqualTo(database.version());
    assertThat(profile.defaultStorageEngine()).isEqualToIgnoringCase("InnoDB");

    String permitId = id("restart");
    var first = new MariaDbPermitConsumptionStore(database.source(), ACME);
    assertThat(first.consume(permitId, NOW.plusSeconds(60), ACTION_HASH, NOW)).isTrue();
    var restarted = MariaDbPermitConsumptionStore.openExisting(database.source());
    assertThat(restarted.isConsumed("acme", permitId)).isTrue();
    assertThat(restarted.consume("acme", permitId, NOW.plusSeconds(60), ACTION_HASH, NOW))
        .isFalse();
  }

  @ParameterizedTest(name = "{0}: invalid input is fail-closed")
  @MethodSource("qualifiedDatabases")
  void expiryAndInvalidBindingsFailWithoutMutation(QualifiedDatabase database) throws SQLException {
    clear(database.source());
    var store = new MariaDbPermitConsumptionStore(database.source(), ACME);
    String permitId = id("invalid");

    assertThat(store.consume(permitId, NOW, ACTION_HASH, NOW)).isFalse();
    assertThatThrownBy(() -> store.consume(permitId, NOW.plusSeconds(1), null, NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.consume("bad tenant", permitId, NOW.plusSeconds(1), ACTION_HASH, NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.consume("bad permit!", NOW.plusSeconds(1), ACTION_HASH, NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(store.isConsumed(permitId)).isFalse();
  }

  @ParameterizedTest(name = "{0}: non-empty schema is never adopted")
  @MethodSource("qualifiedDatabases")
  void nonEmptyDatabaseCannotBeInitializedOrSilentlyAdopted(QualifiedDatabase database)
      throws SQLException {
    assertThatThrownBy(() -> MariaDbDatabaseLifecycle.initializeEmpty(database.source()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("DATABASE_INITIALIZATION_REQUIRES_EMPTY_DATABASE");

    try (Connection connection = database.source().getConnection();
         Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE unexpected_object (id INTEGER PRIMARY KEY) ENGINE=InnoDB");
    }
    try {
      assertThatThrownBy(() -> MariaDbDatabaseLifecycle.validateExisting(database.source()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("DATABASE_SCHEMA_NOT_READY");
    } finally {
      try (Connection connection = database.source().getConnection();
           Statement statement = connection.createStatement()) {
        statement.execute("DROP TABLE unexpected_object");
      }
    }
    MariaDbDatabaseLifecycle.validateExisting(database.source());
  }

  @ParameterizedTest(name = "{0}: commit failure and acknowledgement uncertainty")
  @MethodSource("qualifiedDatabases")
  void commitFailureRollsBackAndLostAcknowledgementRemainsUncertain(QualifiedDatabase database)
      throws SQLException {
    clear(database.source());
    FaultSource source = faultSource(database.source());
    var store = new MariaDbPermitConsumptionStore(source.dataSource(), ACME);

    String rolledBack = id("rollback");
    source.fault().set(Fault.BEFORE_COMMIT);
    assertThatThrownBy(() -> store.consume(rolledBack, NOW.plusSeconds(60), ACTION_HASH, NOW))
        .isInstanceOf(MariaDbStateException.class);
    assertThat(new MariaDbPermitConsumptionStore(database.source(), ACME).isConsumed(rolledBack))
        .isFalse();

    String uncertain = id("uncertain");
    source.fault().set(Fault.AFTER_COMMIT);
    assertThatThrownBy(() -> store.consume(uncertain, NOW.plusSeconds(60), ACTION_HASH, NOW))
        .isInstanceOf(MariaDbStateException.class);
    var independent = new MariaDbPermitConsumptionStore(database.source(), ACME);
    assertThat(independent.isConsumed(uncertain)).isTrue();
    assertThat(independent.consume(uncertain, NOW.plusSeconds(60), ACTION_HASH, NOW)).isFalse();
  }

  @ParameterizedTest(name = "{0}: acquisition and close failures never succeed")
  @MethodSource("qualifiedDatabases")
  void acquisitionAndCloseFailuresNeverReportSuccess(QualifiedDatabase database) throws SQLException {
    clear(database.source());
    FaultSource source = faultSource(database.source());
    var store = new MariaDbPermitConsumptionStore(source.dataSource(), ACME);

    source.fault().set(Fault.ACQUIRE);
    assertThatThrownBy(() -> store.consume(id("acquire"), NOW.plusSeconds(60), ACTION_HASH, NOW))
        .isInstanceOf(MariaDbStateException.class);

    String uncertain = id("close");
    source.fault().set(Fault.CLOSE);
    assertThatThrownBy(() -> store.consume(uncertain, NOW.plusSeconds(60), ACTION_HASH, NOW))
        .isInstanceOf(MariaDbStateException.class);
    assertThat(new MariaDbPermitConsumptionStore(database.source(), ACME).isConsumed(uncertain))
        .isTrue();
  }

  private static void clear(DataSource source) throws SQLException {
    try (Connection connection = source.getConnection();
         Statement statement = connection.createStatement()) {
      statement.execute("TRUNCATE TABLE tp_consumed_execution_permits");
    }
  }

  private static MariaDbDataSource source(MariaDBContainer<?> container) throws SQLException {
    var source = new MariaDbDataSource();
    source.setUrl(container.getJdbcUrl());
    source.setUser(container.getUsername());
    source.setPassword(container.getPassword());
    return source;
  }

  private static String id(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }

  private enum Fault {
    NONE,
    BEFORE_COMMIT,
    AFTER_COMMIT,
    ACQUIRE,
    CLOSE
  }

  private record FaultSource(DataSource dataSource, AtomicReference<Fault> fault) {}

  private static FaultSource faultSource(DataSource delegate) {
    var mode = new AtomicReference<>(Fault.NONE);
    DataSource wrapped = (DataSource) Proxy.newProxyInstance(
        DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
        (proxy, method, args) -> {
          if (method.getName().equals("getConnection")) {
            if (mode.get() == Fault.ACQUIRE) {
              throw new SQLException("synthetic acquisition failure");
            }
            return wrapConnection(delegate.getConnection(), mode);
          }
          try {
            return method.invoke(delegate, args);
          } catch (InvocationTargetException failure) {
            throw failure.getCause();
          }
        });
    return new FaultSource(wrapped, mode);
  }

  private static Connection wrapConnection(Connection delegate, AtomicReference<Fault> mode) {
    return (Connection) Proxy.newProxyInstance(
        Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
        (proxy, method, args) -> {
          if (method.getName().equals("commit")) {
            if (mode.get() == Fault.BEFORE_COMMIT) {
              throw new SQLException("synthetic commit failure");
            }
            Object result = invoke(delegate, method, args);
            if (mode.get() == Fault.AFTER_COMMIT) {
              throw new SQLException("synthetic lost commit acknowledgement");
            }
            return result;
          }
          if (method.getName().equals("close") && mode.get() == Fault.CLOSE) {
            invoke(delegate, method, args);
            throw new SQLException("synthetic close failure");
          }
          return invoke(delegate, method, args);
        });
  }

  private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args)
      throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException failure) {
      throw failure.getCause();
    }
  }

  private static final class QualifiedDatabase {
    private final String version;
    private final MariaDBContainer<?> container;
    private DataSource source;

    private QualifiedDatabase(String version, MariaDBContainer<?> container) {
      this.version = version;
      this.container = container;
    }

    String version() {
      return version;
    }

    MariaDBContainer<?> container() {
      return container;
    }

    DataSource source() {
      return source;
    }

    void source(DataSource value) {
      source = value;
    }

    @Override
    public String toString() {
      return "MariaDB " + version;
    }
  }
}
