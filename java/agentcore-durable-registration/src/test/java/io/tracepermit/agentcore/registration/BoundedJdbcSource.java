package io.tracepermit.agentcore.registration;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;

/** Test-only real JDBC forwarding. Configuration/credentials never enter diagnostics. */
final class BoundedJdbcSource implements DataSource, AutoCloseable {
  static final String CANARY = "SYNTHETIC_PRIVATE_JDBC_DIAGNOSTIC";
  enum Fault {
    NONE,
    FIRST_WRITE,
    BLOCK_FIRST_WRITE,
    SECOND_FACT,
    AFTER_COMMIT,
    CLOSE_COMMIT,
    LATE_COMMIT,
    LATE_CLOSE,
    UNCHECKED_BORROW
  }
  final String url;
  final String user;
  final String password;
  volatile Fault fault = Fault.NONE;
  volatile CountDownLatch counterEntered;
  volatile CountDownLatch writeEntered;
  volatile CountDownLatch releaseWrite;
  final AtomicInteger opens = new AtomicInteger();
  final AtomicInteger closes = new AtomicInteger();
  final AtomicInteger writes = new AtomicInteger();
  final AtomicInteger commits = new AtomicInteger();
  final AtomicInteger denied = new AtomicInteger();
  final AtomicInteger poolCloses = new AtomicInteger();
  final AtomicBoolean delayReached = new AtomicBoolean();
  final Set<String> tablesWritten = ConcurrentHashMap.newKeySet();
  volatile long deadlineNanos;

  BoundedJdbcSource(String url, String user, String password) {
    this.url = url; this.user = user; this.password = password;
  }

  BoundedJdbcSource fresh() { return new BoundedJdbcSource(url, user, password); }

  void reset() {
    fault = Fault.NONE; counterEntered = null; writeEntered = null; releaseWrite = null;
    opens.set(0); closes.set(0);
    writes.set(0); commits.set(0); denied.set(0); delayReached.set(false); tablesWritten.clear();
  }

  @Override
  public Connection getConnection() throws SQLException {
    if (fault == Fault.UNCHECKED_BORROW) throw new IllegalStateException(CANARY);
    Connection delegate = DriverManager.getConnection(url, user, password);
    opens.incrementAndGet();
    try (var setup = delegate.createStatement()) {
      setup.setQueryTimeout(5);
      setup.execute(url.startsWith("jdbc:postgresql:")
          ? "SET lock_timeout = '5s'" : "SET SESSION innodb_lock_wait_timeout = 5");
    } catch (SQLException failure) {
      delegate.close(); closes.incrementAndGet(); throw failure;
    }
    Fault selected = fault;
    AtomicBoolean wrote = new AtomicBoolean(), committed = new AtomicBoolean(), closed = new AtomicBoolean();
    AtomicInteger facts = new AtomicInteger();
    return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class},
        (proxy, method, args) -> {
          if (method.getName().equals("commit")) {
            delegate.commit();
            if (wrote.get()) {
              commits.incrementAndGet(); committed.set(true);
              if (selected == Fault.AFTER_COMMIT) throw diagnostic();
              if (selected == Fault.LATE_COMMIT) delayPastDeadline();
            }
            return null;
          }
          if (method.getName().equals("close")) {
            delegate.close();
            if (closed.compareAndSet(false, true)) {
              closes.incrementAndGet();
              if (committed.get() && selected == Fault.CLOSE_COMMIT) throw diagnostic();
              if (committed.get() && selected == Fault.LATE_CLOSE) delayPastDeadline();
            }
            return null;
          }
          Object result = invoke(method, delegate, args);
          if (method.getName().equals("prepareStatement") && args[0] instanceof String sql) {
            PreparedStatement statement = (PreparedStatement) result;
            return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {PreparedStatement.class},
                (ignored, action, values) -> {
                  boolean dml = action.getName().equals("executeUpdate")
                      && (sql.stripLeading().startsWith("INSERT INTO ") || sql.stripLeading().startsWith("UPDATE "));
                  if (dml && sql.contains("UPDATE tp_workload_replay_state") && counterEntered != null) {
                    counterEntered.countDown();
                  }
                  Object value;
                  try { value = invoke(action, statement, values); }
                  catch (SQLException failure) {
                    if ("42501".equals(failure.getSQLState()) || failure.getErrorCode() == 1142
                        || failure.getErrorCode() == 1143) denied.incrementAndGet();
                    throw failure;
                  }
                  if (dml) {
                    wrote.set(true); int writeNumber = writes.incrementAndGet();
                    tablesWritten.add(sql.stripLeading().split("\\s+")[sql.stripLeading().startsWith("INSERT") ? 2 : 1]);
                    if (writeNumber == 1 && selected == Fault.FIRST_WRITE) throw diagnostic();
                    if (writeNumber == 1 && selected == Fault.BLOCK_FIRST_WRITE) blockFirstWrite();
                    if (sql.contains("INSERT INTO tp_business_operation_facts") && facts.incrementAndGet() == 2
                        && selected == Fault.SECOND_FACT) throw diagnostic();
                  }
                  return value;
                });
          }
          return result;
        });
  }

  private void delayPastDeadline() throws SQLException {
    delayReached.set(true);
    long remaining = deadlineNanos - System.nanoTime();
    if (remaining <= 0) throw new AssertionError("SYNTHETIC_STAGE_NOT_REACHED_BEFORE_EXPIRY");
    try { TimeUnit.NANOSECONDS.sleep(remaining + Duration.ofMillis(150).toNanos()); }
    catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt(); throw diagnostic();
    }
  }

  private void blockFirstWrite() throws SQLException {
    CountDownLatch entered = writeEntered;
    CountDownLatch release = releaseWrite;
    if (entered == null || release == null) throw new AssertionError("SYNTHETIC_BLOCK_GATE_MISSING");
    entered.countDown();
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (release.getCount() != 0) {
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) throw new AssertionError("SYNTHETIC_BLOCK_GATE_TIMEOUT");
      try {
        release.await(remaining, TimeUnit.NANOSECONDS);
      } catch (InterruptedException ignored) {
        // Deliberate TEST dependency: ignore transport cancellation until the owner releases it.
      }
    }
  }

  private static SQLException diagnostic() { return new SQLException(CANARY, "08006"); }
  private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
    try { return method.invoke(target, args); }
    catch (InvocationTargetException wrapped) { throw wrapped.getCause(); }
  }
  @Override public Connection getConnection(String u, String p) throws SQLException { throw new SQLFeatureNotSupportedException(); }
  @Override public PrintWriter getLogWriter() { return null; }
  @Override public void setLogWriter(PrintWriter value) {}
  @Override public void setLoginTimeout(int seconds) {}
  @Override public int getLoginTimeout() { return 5; }
  @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
  @Override public <T> T unwrap(Class<T> type) throws SQLException { throw new SQLFeatureNotSupportedException(); }
  @Override public boolean isWrapperFor(Class<?> type) { return false; }
  @Override public void close() { poolCloses.incrementAndGet(); }
}
