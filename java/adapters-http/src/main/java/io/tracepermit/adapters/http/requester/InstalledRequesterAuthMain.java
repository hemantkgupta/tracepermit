package io.tracepermit.adapters.http.requester;

import io.tracepermit.adapters.remote.SsrfSafeHttpsJwkSetSource;
import io.tracepermit.identity.BrowserIdTokenVerifier;
import io.tracepermit.identity.CachingOidcJwkResolver;
import io.tracepermit.identity.OidcAccessTokenVerifier;
import io.tracepermit.identity.StaticOidcProviderRegistry;
import java.awt.Desktop;
import java.io.PrintStream;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Installed source-free requester login and one-shot Codex launch. */
public final class InstalledRequesterAuthMain {
  private InstalledRequesterAuthMain() {}

  public static void main(String[] args) {
    int result = execute(args, System.getenv(), System.out, System.err, Clock.systemUTC());
    if (result != 0) System.exit(result);
  }

  static int execute(String[] args, Map<String, String> environment, PrintStream out,
      PrintStream err, Clock clock) {
    try {
      if (args.length != 1 || !Set.of("--help", "--check", "--login", "--codex", "--cleanup")
          .contains(args[0])) {
        throw new InstalledRequesterFailure("REQUESTER_MODE_REQUIRED");
      }
      if ("--help".equals(args[0])) {
        out.println("Usage: tracepermit-requester-auth --help|--check|--login|--codex|--cleanup");
        out.println("Configuration: TRACEPERMIT_REQUESTER_CONFIG (owner-only absolute file)");
        return 0;
      }
      String configured = environment.get("TRACEPERMIT_REQUESTER_CONFIG");
      if (configured == null || configured.isBlank()) {
        throw new InstalledRequesterFailure("REQUESTER_CONFIGURATION_INVALID");
      }
      InstalledRequesterConfiguration configuration =
          InstalledRequesterConfiguration.load(Path.of(configured));
      RequesterCredentialStore credentials =
          new RequesterCredentialStore(configuration.credentialFile(), clock);
      switch (args[0]) {
        case "--check" -> out.println(
            "REQUESTER_CONFIGURATION_VALID: offline only; no DNS, listener, browser, provider, backend, credential, or child used.");
        case "--cleanup" -> {
          credentials.cleanup();
          out.println("REQUESTER_CREDENTIAL_CLEANED: local file removed if present; provider token not revoked.");
        }
        case "--login" -> {
          login(configuration, credentials, clock);
          out.println("REQUESTER_CREDENTIAL_STORED: finite requester credential ready for one explicit Codex launch.");
        }
        case "--codex" -> {
          int child = codex(configuration, credentials, clock);
          return child;
        }
        default -> throw new InstalledRequesterFailure("REQUESTER_MODE_REQUIRED");
      }
      return 0;
    } catch (InstalledRequesterFailure failure) {
      err.println("REQUESTER_AUTH_FAILED: " + failure.code());
      return 2;
    } catch (RuntimeException failure) {
      err.println("REQUESTER_AUTH_FAILED: REQUESTER_OPERATION_FAILED");
      return 2;
    }
  }

  private static void login(InstalledRequesterConfiguration configuration,
      RequesterCredentialStore credentials, Clock clock) {
    var provider = configuration.provider();
    var jwks = new SsrfSafeHttpsJwkSetSource(Set.of(configuration.jwksUri()),
        Duration.ofSeconds(3), Duration.ofSeconds(5));
    var token = new SsrfSafeHttpsJwkSetSource(Set.of(configuration.tokenUri()),
        Duration.ofSeconds(3), Duration.ofSeconds(5));
    try (var resolver = new CachingOidcJwkResolver(jwks)) {
      var access = new OidcAccessTokenVerifier(
          new StaticOidcProviderRegistry(List.of(provider)), resolver, clock);
      var verifier = new RequesterTokenVerifier(configuration, access,
          new BrowserIdTokenVerifier(provider, resolver, clock, configuration.organization()), clock);
      var service = new RequesterAuthorizationService(configuration, verifier,
          new RequesterCallbackListener(configuration.callbackUri()),
          authorizationUri -> {
            if (!Desktop.isDesktopSupported()
                || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
              throw new InstalledRequesterFailure("PROVIDER_PROFILE_UNSUPPORTED");
            }
            Desktop.getDesktop().browse(authorizationUri);
          }, form -> token.postForm(configuration.tokenUri(), form, null),
          RequesterQualificationClient.production(configuration.qualificationUri()),
          credentials, new SecureRandom(), clock);
      service.login();
    }
  }

  private static int codex(InstalledRequesterConfiguration configuration,
      RequesterCredentialStore credentials, Clock clock) {
    RequesterCredentialStore.Credential credential = credentials.read();
    var provider = configuration.provider();
    var jwks = new SsrfSafeHttpsJwkSetSource(Set.of(configuration.jwksUri()),
        Duration.ofSeconds(3), Duration.ofSeconds(5));
    try (var resolver = new CachingOidcJwkResolver(jwks)) {
      var access = new OidcAccessTokenVerifier(
          new StaticOidcProviderRegistry(List.of(provider)), resolver, clock);
      var verifier = new RequesterTokenVerifier(configuration, access,
          new BrowserIdTokenVerifier(provider, resolver, clock, configuration.organization()), clock);
      RequesterTokenVerifier.VerifiedAccess verified = verifier.verifyAccess(credential.accessToken());
      if (!verified.expiresAt().equals(credential.expiresAt())) {
        throw new InstalledRequesterFailure("CREDENTIAL_PATH_UNSAFE");
      }
    }
    // Consumption precedes child creation. A launch failure never republishes authority.
    credentials.consume(credential);
    return new CodexLauncher(configuration).launch(credential.accessToken());
  }
}
