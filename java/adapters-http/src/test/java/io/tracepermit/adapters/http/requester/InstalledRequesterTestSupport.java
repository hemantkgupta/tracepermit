package io.tracepermit.adapters.http.requester;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

final class InstalledRequesterTestSupport {
  static final Instant NOW = Instant.parse("2026-10-22T08:00:00Z");
  static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  static Fixture fixture(Path root) throws IOException {
    root = root.toRealPath();
    Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
    Path executable = root.resolve("codex-fixture");
    Files.writeString(executable, "#!/bin/sh\nexit 0\n", StandardCharsets.US_ASCII);
    Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
    Path credential = root.resolve("requester-credential.json");
    Path config = root.resolve("requester.json");
    String json = json(credential, executable, 41837, 8085);
    Files.writeString(config, json, StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("rw-------"));
    return new Fixture(root, config, credential, executable, json);
  }

  static String json(Path credential, Path executable, int callbackPort, int servicePort) {
    return """
        {
          "schemaVersion": "1.0",
          "profile": "installed-requester-public-sign-in",
          "oidc": {
            "issuer": "https://idp.example.invalid/",
            "authorizationUri": "https://idp.example.invalid/authorize",
            "tokenUri": "https://idp.example.invalid/oauth/token",
            "jwksUri": "https://idp.example.invalid/.well-known/jwks.json",
            "audience": "https://approval-api.example.invalid",
            "clientId": "native-client.example.invalid",
            "organization": "org_exampleinvalid",
            "expectedSubject": "provider|requester.example.invalid"
          },
          "local": {
            "callbackUri": "http://127.0.0.1:%d/requester/callback",
            "qualificationUri": "http://127.0.0.1:%d/api/v1/requester-qualification",
            "mcpUri": "http://127.0.0.1:%d/mcp",
            "credentialFile": "%s",
            "codexExecutable": "%s"
          }
        }
        """.formatted(callbackPort, servicePort, servicePort,
        credential.toString(), executable.toString());
  }

  record Fixture(Path root, Path config, Path credential, Path executable, String json) {
    InstalledRequesterConfiguration load() {
      return InstalledRequesterConfiguration.load(config);
    }

    void writeConfig(String value) throws IOException {
      Files.writeString(config, value, StandardCharsets.UTF_8);
      Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("rw-------"));
    }
  }

  private InstalledRequesterTestSupport() {}
}
