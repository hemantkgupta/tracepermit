package io.tracepermit.adapters.http;

import io.tracepermit.identity.AuthenticationException;
import java.util.function.Consumer;

/** Fixed, credential-free operator diagnostics for rejected browser login attempts. */
final class BrowserLoginDiagnostics {
  enum Stage {
    CALLBACK_BINDING,
    CODE_EXCHANGE,
    TOKEN_RESPONSE,
    ACCESS_TOKEN,
    API_SCOPE,
    SENDER_CONSTRAINT,
    MEMBERSHIP,
    HUMAN_IDENTITY,
    ID_TOKEN,
    SESSION_CAPACITY
  }

  private final Consumer<String> sink;

  BrowserLoginDiagnostics(Consumer<String> sink) {
    this.sink = sink;
  }

  static BrowserLoginDiagnostics silent() {
    return new BrowserLoginDiagnostics(line -> {});
  }

  void rejected(Stage stage) {
    rejected(stage, null);
  }

  void rejected(Stage stage, Throwable failure) {
    try {
      String reason = stage == Stage.ACCESS_TOKEN && failure instanceof AuthenticationException authentication
          ? allowlisted(authentication) : "REJECTED";
      sink.accept("BROWSER_LOGIN_DIAGNOSTIC stage=" + stage.name() + " reason=" + reason);
    } catch (RuntimeException ignored) {
      // Diagnostics are never an authentication input and cannot alter the rejection path.
    }
  }

  private static String allowlisted(AuthenticationException failure) {
    String code = failure.code();
    if ("MISSING_BEARER_TOKEN".equals(code)) return "MISSING_BEARER_TOKEN";
    if ("MALFORMED_ACCESS_TOKEN".equals(code)) return "MALFORMED_ACCESS_TOKEN";
    if ("EXPIRED_ACCESS_TOKEN".equals(code)) return "EXPIRED_ACCESS_TOKEN";
    if ("OIDC_CONFIGURATION_UNAVAILABLE".equals(code)) return "OIDC_CONFIGURATION_UNAVAILABLE";
    if ("INVALID_ACCESS_TOKEN".equals(code)) {
      return switch (failure.diagnosticCode()) {
        case "HEADER_PROFILE", "CLIENT_MAPPING", "TENANT_MAPPING", "ISSUER_OR_AUDIENCE",
            "ISSUED_AT", "NOT_BEFORE", "TOKEN_LIFETIME", "AUDIENCE_SHAPE", "SCOPE_SHAPE",
            "SCOPE_ALLOWLIST", "IDENTIFIER_SHAPE", "SUBJECT_SHAPE", "REQUIRED_CLAIM",
            "CLAIM_SHAPE", "NUMERIC_DATE", "UNSPECIFIED_CLAIMS" -> failure.diagnosticCode();
        default -> "REJECTED";
      };
    }
    return "REJECTED";
  }
}
