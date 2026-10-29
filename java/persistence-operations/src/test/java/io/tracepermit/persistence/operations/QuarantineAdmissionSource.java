package io.tracepermit.persistence.operations;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;

/** Test-only JDBC acknowledgements, not a wire-level COMMIT-loss simulator. */
final class QuarantineAdmissionSource implements DataSource {
  enum Fault { NONE, QUARANTINE_COMMIT, QUARANTINE_CLOSE, ADMISSION_COMMIT, ADMISSION_CLOSE }

  private final String url;
  private final String user;
  private final String password;
  volatile Fault fault = Fault.NONE;
  volatile boolean unavailable;
  volatile CountDownLatch quarantineAttempt;
  final AtomicInteger quarantinePid = new AtomicInteger();
  final AtomicInteger open = new AtomicInteger();
  final AtomicInteger quarantineWrites = new AtomicInteger();
  final AtomicInteger admissionWrites = new AtomicInteger();
  final AtomicInteger commits = new AtomicInteger();
  final AtomicInteger injected = new AtomicInteger();
  volatile String deniedState;

  QuarantineAdmissionSource(String url, String user, String password) {
    this.url = url + (url.contains("?") ? "&" : "?") + "connectTimeout=5&socketTimeout=15";
    this.user = user;
    this.password = password;
  }

  void resetObservations() {
    fault = Fault.NONE;
    unavailable = false;
    quarantineAttempt = null;
    quarantinePid.set(0);
    quarantineWrites.set(0);
    admissionWrites.set(0);
    commits.set(0);
    injected.set(0);
    deniedState = null;
  }

  @Override public Connection getConnection() throws SQLException {
    if (unavailable) throw new SQLException("SYNTHETIC_SOURCE_UNAVAILABLE");
    Connection delegate;
    try { delegate = DriverManager.getConnection(url, user, password); }
    catch (SQLException failure) { throw new SQLException("SYNTHETIC_CONNECTION_FAILED", failure.getSQLState()); }
    open.incrementAndGet();
    boolean[] selected = {false, false};
    boolean[] committed = {false};
    boolean[] closed = {false};
    return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
      if (method.getName().equals("commit")) {
        delegate.commit();
        committed[0] = true;
        commits.incrementAndGet();
        if (selected[0] && fault == Fault.QUARANTINE_COMMIT || selected[1] && fault == Fault.ADMISSION_COMMIT) {
          injected.incrementAndGet();
          throw new SQLException("SYNTHETIC_AFTER_REAL_COMMIT");
        }
        return null;
      }
      if (method.getName().equals("close")) {
        delegate.close();
        if (!closed[0]) { closed[0] = true; open.decrementAndGet(); }
        if (committed[0] && (selected[0] && fault == Fault.QUARANTINE_CLOSE || selected[1] && fault == Fault.ADMISSION_CLOSE)) {
          injected.incrementAndGet();
          throw new SQLException("SYNTHETIC_AFTER_REAL_CLOSE");
        }
        return null;
      }
      Object result = invoke(method, delegate, args);
      if (method.getName().equals("prepareStatement") && args[0] instanceof String sql) {
        boolean quarantine = sql.contains("SET control_state = 'quarantined'");
        boolean admission = sql.contains("INSERT INTO tp_operation_admissions");
        return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {PreparedStatement.class}, (ignored, call, values) -> {
          boolean update = call.getName().equals("executeUpdate");
          if (update && quarantine) {
            quarantineWrites.incrementAndGet();
            if (quarantineAttempt != null) {
              try (var query = delegate.prepareStatement("SELECT pg_backend_pid()")) {
                query.setQueryTimeout(3);
                try (var rows = query.executeQuery()) {
                  if (!rows.next()) throw new SQLException("SYNTHETIC_PID_MISSING");
                  quarantinePid.set(rows.getInt(1));
                }
              }
              quarantineAttempt.countDown();
            }
          }
          if (update && admission) admissionWrites.incrementAndGet();
          Object value;
          try { value = invoke(call, result, values); }
          catch (SQLException failure) {
            if (update && quarantine) deniedState = failure.getSQLState();
            throw failure;
          }
          if (update && Integer.valueOf(1).equals(value)) {
            if (quarantine) selected[0] = true;
            if (admission) selected[1] = true;
          }
          return value;
        });
      }
      return result;
    });
  }

  private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
    try { return method.invoke(target, args); }
    catch (InvocationTargetException failure) { throw failure.getCause(); }
  }

  @Override public Connection getConnection(String username, String secret) throws SQLException { throw new SQLFeatureNotSupportedException(); }
  @Override public PrintWriter getLogWriter() { return null; }
  @Override public void setLogWriter(PrintWriter writer) {}
  @Override public void setLoginTimeout(int seconds) {}
  @Override public int getLoginTimeout() { return 5; }
  @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
  @Override public <T> T unwrap(Class<T> type) throws SQLException { throw new SQLFeatureNotSupportedException(); }
  @Override public boolean isWrapperFor(Class<?> type) { return false; }
}
