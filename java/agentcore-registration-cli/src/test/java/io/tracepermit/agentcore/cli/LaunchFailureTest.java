package io.tracepermit.agentcore.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.security.Permission;
import java.sql.SQLFeatureNotSupportedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class LaunchFailureTest {
  private static final class Exit extends SecurityException {
    private static final long serialVersionUID = 1L;
    final int status;
    Exit(int status) { super("FIXED_EXIT"); this.status = status; }
  }

  @SuppressWarnings("removal")
  @ParameterizedTest @ValueSource(strings = {"execute", "check-config", "register-once"})
  void commandUsageAndActualProcessStartFailureHaveFixedNonzeroReceipt(String command) {
    var original = System.getSecurityManager(); PrintStream saved = System.out;
    var output = new ByteArrayOutputStream();
    System.setOut(new PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8));
    System.setSecurityManager(new SecurityManager() {
      @Override public void checkPermission(Permission permission) { }
      @Override public void checkExec(String executable) { throw new SecurityException("SYNTHETIC_PROCESS_CREATION_DENIED"); }
      @Override public void checkExit(int status) { throw new Exit(status); }
    });
    try {
      assertThatThrownBy(() -> RegistrationCli.main(new String[]{command})).isInstanceOfSatisfying(Exit.class,
          failure -> assertThat(failure.status).isNotZero());
      var receipt = Receipt.decode(output.toByteArray(), 1);
      assertThat(receipt.outcome()).isIn(command.equals("execute") ? new String[]{"USAGE"} : new String[]{"INPUT_INVALID", "UNKNOWN"});
      assertThat(java.util.Arrays.equals(output.toByteArray(), receipt.bytes())).isTrue();
    } finally { System.setSecurityManager(original); System.setOut(saved); }
  }

  @Test void datasourceCannotOverrideCredentialsOrExposeDiagnosticSink() throws Exception {
    var parsed = OperatorConfig.parse(SyntheticIdentity.config()); var source = new LoopbackSource(parsed.replay);
    assertThat(source.getLogWriter()).isNull(); source.setLogWriter(new java.io.PrintWriter(new java.io.StringWriter()));
    assertThat(source.getLogWriter()).isNull(); source.setLoginTimeout(0); assertThat(source.getLoginTimeout()).isEqualTo(5);
    assertThat(source.isWrapperFor(javax.sql.DataSource.class)).isFalse();
    assertThatThrownBy(() -> source.unwrap(javax.sql.DataSource.class)).isInstanceOf(SQLFeatureNotSupportedException.class);
    assertThatThrownBy(source::getParentLogger).isInstanceOf(SQLFeatureNotSupportedException.class);
    assertThatThrownBy(() -> source.getConnection("override", "override")).isInstanceOf(SQLFeatureNotSupportedException.class);
    assertThat(source.toString()).isEqualTo("LocalLoopbackSource[redacted]");
    assertThat(parsed.toString()).isEqualTo("LocalSyntheticOperatorConfig[redacted]");
    assertThat(parsed.replay.toString()).isEqualTo("LoopbackDatabase[redacted]");
  }
}
