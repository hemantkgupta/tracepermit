package io.tracepermit.agentcore.cli;

import static io.tracepermit.agentcore.cli.SyntheticIdentity.bytes;
import static io.tracepermit.agentcore.cli.SyntheticIdentity.config;
import static io.tracepermit.agentcore.cli.SyntheticIdentity.frames;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class InstalledHttpRuntimeTest {
  @Test
  void readsTheExistingConfigThenClosedSessionAndOnlyAfterEof() {
    var session =
        Protocol.JSON
            .createObjectNode()
            .put("schemaVersion", "1.0")
            .put("profile", InstalledHttpRuntime.PROFILE)
            .put("requestedPort", 0)
            .put("sessionMillis", 1_000);

    var plan =
        InstalledHttpRuntime.readHttpPlan(
            new ByteArrayInputStream(frames(bytes(config()), bytes(session))));

    assertThat(plan.sessionDuration()).isEqualTo(java.time.Duration.ofSeconds(1));
    assertThat(plan).hasToString("InstalledHttpPlan[redacted]");
  }

  @Test
  void rejectsMissingExtraMalformedAndNonClosedSessionFieldsWithFixedDiagnostics() {
    var valid =
        Protocol.JSON
            .createObjectNode()
            .put("schemaVersion", "1.0")
            .put("profile", InstalledHttpRuntime.PROFILE)
            .put("requestedPort", 1024)
            .put("sessionMillis", 30_000);

    for (byte[] input :
        java.util.List.of(
            frames(bytes(config())),
            frames(bytes(config()), bytes(valid), new byte[] {1}),
            frames(bytes(config()), bytes(valid.deepCopy().put("extra", true))),
            frames(bytes(config()), bytes(valid.deepCopy().put("requestedPort", 1023))),
            frames(bytes(config()), bytes(valid.deepCopy().put("sessionMillis", 999))),
            frames(bytes(config()), bytes(valid.deepCopy().put("sessionMillis", 30_001))),
            frames(bytes(config()), "not-json".getBytes(StandardCharsets.UTF_8)))) {
      assertThatThrownBy(
              () -> InstalledHttpRuntime.readHttpPlan(new ByteArrayInputStream(input)))
          .isInstanceOf(InstalledHttpRuntime.Failure.class)
          .satisfies(failure -> assertThat(failure.getMessage()).isIn("INPUT_INVALID", "CONFIG_INVALID"))
          .hasNoCause();
    }
  }

  @Test
  void publicFailureAndSessionFacadeDoNotExposeConfiguration() {
    var failure =
        (InstalledHttpRuntime.Failure)
            org.assertj.core.api.Assertions.catchThrowable(
                () -> InstalledHttpRuntime.readHttpPlan(new ByteArrayInputStream(new byte[0])));
    assertThat(failure.code()).isEqualTo("INPUT_INVALID");
    assertThat(failure.getSuppressed()).isEmpty();
    assertThat(failure.initCause(new IllegalStateException("secret"))).isSameAs(failure);
    assertThat(failure).hasToString(
        "io.tracepermit.agentcore.cli.InstalledHttpRuntime$Failure: INPUT_INVALID");
  }
}
