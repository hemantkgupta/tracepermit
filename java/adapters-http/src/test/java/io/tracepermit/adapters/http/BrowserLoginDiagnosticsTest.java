package io.tracepermit.adapters.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.tracepermit.identity.AuthenticationException;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

final class BrowserLoginDiagnosticsTest {
  @Test void stagesProduceOnlyFixedBoundedLines() {
    var lines = new ArrayList<String>();
    var diagnostics = new BrowserLoginDiagnostics(lines::add);
    for (var stage : BrowserLoginDiagnostics.Stage.values()) diagnostics.rejected(stage);
    assertThat(lines).containsExactly(
        "BROWSER_LOGIN_DIAGNOSTIC stage=CALLBACK_BINDING reason=REJECTED",
        "BROWSER_LOGIN_DIAGNOSTIC stage=CODE_EXCHANGE reason=REJECTED",
        "BROWSER_LOGIN_DIAGNOSTIC stage=TOKEN_RESPONSE reason=REJECTED",
        "BROWSER_LOGIN_DIAGNOSTIC stage=ACCESS_TOKEN reason=REJECTED",
        "BROWSER_LOGIN_DIAGNOSTIC stage=API_SCOPE reason=REJECTED",
        "BROWSER_LOGIN_DIAGNOSTIC stage=SENDER_CONSTRAINT reason=REJECTED",
        "BROWSER_LOGIN_DIAGNOSTIC stage=MEMBERSHIP reason=REJECTED",
        "BROWSER_LOGIN_DIAGNOSTIC stage=HUMAN_IDENTITY reason=REJECTED",
        "BROWSER_LOGIN_DIAGNOSTIC stage=ID_TOKEN reason=REJECTED",
        "BROWSER_LOGIN_DIAGNOSTIC stage=SESSION_CAPACITY reason=REJECTED");
  }

  @Test void knownAuthenticationReasonsAreExplicitlyAllowlisted() {
    var lines = new ArrayList<String>();
    var diagnostics = new BrowserLoginDiagnostics(lines::add);
    diagnostics.rejected(BrowserLoginDiagnostics.Stage.ACCESS_TOKEN,
        new AuthenticationException("INVALID_ACCESS_TOKEN", "secret", "HEADER_PROFILE"));
    diagnostics.rejected(BrowserLoginDiagnostics.Stage.ACCESS_TOKEN,
        new AuthenticationException("EXPIRED_ACCESS_TOKEN", "secret"));
    diagnostics.rejected(BrowserLoginDiagnostics.Stage.ACCESS_TOKEN,
        new AuthenticationException("OIDC_CONFIGURATION_UNAVAILABLE", "secret"));
    assertThat(lines).containsExactly(
        "BROWSER_LOGIN_DIAGNOSTIC stage=ACCESS_TOKEN reason=HEADER_PROFILE",
        "BROWSER_LOGIN_DIAGNOSTIC stage=ACCESS_TOKEN reason=EXPIRED_ACCESS_TOKEN",
        "BROWSER_LOGIN_DIAGNOSTIC stage=ACCESS_TOKEN reason=OIDC_CONFIGURATION_UNAVAILABLE");
  }

  @Test void unknownCodesMessagesAndCausesCannotReachTheSink() {
    String canary = "CANARY_UNKNOWN_DIAGNOSTIC";
    var lines = new ArrayList<String>();
    var diagnostics = new BrowserLoginDiagnostics(lines::add);
    var failure = new AuthenticationException(canary, canary, canary);
    failure.addSuppressed(new IllegalStateException(canary));
    diagnostics.rejected(BrowserLoginDiagnostics.Stage.ACCESS_TOKEN, failure);
    diagnostics.rejected(BrowserLoginDiagnostics.Stage.CODE_EXCHANGE,
        new IllegalStateException(canary, new RuntimeException(canary)));
    diagnostics.rejected(BrowserLoginDiagnostics.Stage.CODE_EXCHANGE,
        new AuthenticationException("EXPIRED_ACCESS_TOKEN", canary));
    diagnostics.rejected(BrowserLoginDiagnostics.Stage.ACCESS_TOKEN,
        new AuthenticationException(null, canary));
    assertThat(lines).containsExactly(
        "BROWSER_LOGIN_DIAGNOSTIC stage=ACCESS_TOKEN reason=REJECTED",
        "BROWSER_LOGIN_DIAGNOSTIC stage=CODE_EXCHANGE reason=REJECTED",
        "BROWSER_LOGIN_DIAGNOSTIC stage=CODE_EXCHANGE reason=REJECTED",
        "BROWSER_LOGIN_DIAGNOSTIC stage=ACCESS_TOKEN reason=REJECTED");
    assertThat(lines).allMatch(line -> !line.contains(canary));
  }

  @Test void aBrokenSinkCannotEscapeOrChangeTheCallerControlFlow() {
    var diagnostics = new BrowserLoginDiagnostics(line -> { throw new IllegalStateException("sink failed"); });
    assertThatCode(() -> diagnostics.rejected(BrowserLoginDiagnostics.Stage.ID_TOKEN,
        new IllegalStateException("credential"))).doesNotThrowAnyException();
    assertThatCode(() -> BrowserLoginDiagnostics.silent().rejected(
        BrowserLoginDiagnostics.Stage.ID_TOKEN)).doesNotThrowAnyException();
  }
}
