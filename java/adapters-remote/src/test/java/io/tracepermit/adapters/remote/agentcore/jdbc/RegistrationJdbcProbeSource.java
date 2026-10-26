package io.tracepermit.adapters.remote.agentcore.jdbc;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;

/** Forwards every query/write/commit/rollback/close to real JDBC; never a repository substitute. */
final class RegistrationJdbcProbeSource implements DataSource {
  static final String CANARY = "SYNTHETIC_SENSITIVE_JDBC_DIAGNOSTIC";

  enum Fault { NONE, SECOND_FACT, HEAD_INSERT, BEFORE_COMMIT, AFTER_COMMIT, CLOSE_AFTER_COMMIT, SECOND_FACT_AND_CLOSE }

  private final String url;
  private final String user;
  private final String password;
  volatile Fault fault = Fault.NONE;
  final AtomicInteger opens = new AtomicInteger();
  final AtomicInteger insertAttempts = new AtomicInteger();
  final AtomicInteger commitAttempts = new AtomicInteger();
  final AtomicInteger actualCommits = new AtomicInteger();
  final AtomicInteger rollbacks = new AtomicInteger();
  final AtomicInteger closes = new AtomicInteger();
  final Set<String> insertedTables = ConcurrentHashMap.newKeySet();

  RegistrationJdbcProbeSource(String url, String user, String password) {
    this.url = url;
    this.user = user;
    this.password = password;
  }

  RegistrationJdbcProbeSource reconstructed() {
    return new RegistrationJdbcProbeSource(url, user, password);
  }

  void resetProbe() {
    opens.set(0); insertAttempts.set(0); commitAttempts.set(0); actualCommits.set(0);
    rollbacks.set(0); closes.set(0); insertedTables.clear(); fault = Fault.NONE;
  }

  @Override
  public Connection getConnection() throws SQLException {
    Connection delegate = DriverManager.getConnection(url, user, password);
    opens.incrementAndGet();
    Fault selected = fault;
    AtomicBoolean committed = new AtomicBoolean();
    AtomicInteger facts = new AtomicInteger();
    return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class},
        (proxy, method, arguments) -> {
          if (method.getName().equals("commit")) {
            commitAttempts.incrementAndGet();
            if (selected == Fault.BEFORE_COMMIT) throw diagnostic();
            delegate.commit(); actualCommits.incrementAndGet(); committed.set(true);
            if (selected == Fault.AFTER_COMMIT) throw diagnostic();
            return null;
          }
          if (method.getName().equals("rollback")) {
            rollbacks.incrementAndGet();
          }
          if (method.getName().equals("close")) {
            delegate.close(); closes.incrementAndGet();
            if (selected == Fault.SECOND_FACT_AND_CLOSE
                || committed.get() && selected == Fault.CLOSE_AFTER_COMMIT) throw diagnostic();
            return null;
          }
          Object result = invoke(method, delegate, arguments);
          if (method.getName().equals("prepareStatement") && arguments[0] instanceof String sql) {
            PreparedStatement statement = (PreparedStatement) result;
            return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {PreparedStatement.class},
                (ignored, statementMethod, statementArguments) -> {
                  boolean insert = statementMethod.getName().equals("executeUpdate")
                      && sql.stripLeading().startsWith("INSERT INTO ");
                  if (insert) {
                    insertAttempts.incrementAndGet();
                    insertedTables.add(sql.stripLeading().substring("INSERT INTO ".length()).split("[\\s(]", 2)[0]);
                  }
                  Object value = invoke(statementMethod, statement, statementArguments);
                  // Fail after actual uncommitted writes, then require real repository rollback.
                  if (insert && sql.contains("INSERT INTO tp_business_operation_facts")
                      && facts.incrementAndGet() == 2
                      && (selected == Fault.SECOND_FACT || selected == Fault.SECOND_FACT_AND_CLOSE)) throw diagnostic();
                  if (insert && sql.contains("INSERT INTO tp_operation_lifecycle_heads")
                      && selected == Fault.HEAD_INSERT) throw diagnostic();
                  return value;
                });
          }
          return result;
        });
  }

  private static Object invoke(Method method, Object target, Object[] arguments) throws Throwable {
    try {
      return method.invoke(target, arguments);
    } catch (InvocationTargetException failure) {
      throw failure.getCause();
    }
  }

  private static SQLException diagnostic() {
    return new SQLException(CANARY, "08006", 0);
  }

  @Override public Connection getConnection(String name, String supplied) throws SQLException { throw new SQLFeatureNotSupportedException(); }
  @Override public PrintWriter getLogWriter() { return null; }
  @Override public void setLogWriter(PrintWriter writer) { }
  @Override public void setLoginTimeout(int seconds) { }
  @Override public int getLoginTimeout() { return 5; }
  @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
  @Override public <T> T unwrap(Class<T> type) throws SQLException { throw new SQLFeatureNotSupportedException(); }
  @Override public boolean isWrapperFor(Class<?> type) { return false; }
}
