package io.tracepermit.adapters.remote.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.adapters.remote.github.credentials.GitHubInstallationConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPairGenerator;

final class PilotTestFiles {
  static final ObjectMapper JSON = new ObjectMapper();
  static final String CANARY = "SYNTHETIC_DIAGNOSTIC_MUST_NOT_RENDER";
  final Path directory;
  final Path key;
  final Path config;
  final ObjectNode json;
  PilotTestFiles(Path root) throws Exception {
    // Canonicalize only the trusted fixture root, e.g. macOS /var -> /private/var.
    // Explicit operator paths and negative-control links must remain unresolved.
    directory = Files.createDirectory(root.toRealPath().resolve("private configuration with spaces"));
    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    key = directory.resolve("synthetic key.der"); config = directory.resolve("operator.json");
    var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
    privateFile(key, generator.generateKeyPair().getPrivate().getEncoded());
    json = JSON.createObjectNode().put("schemaVersion", "tracepermit-github-pilot-diagnostic-v1")
        .put("appId", 11).put("installationId", 22).put("repositoryId", 33).put("ownerId", 44)
        .put("repository", GitHubDraftPullRequestAction.ALLOWED_REPOSITORY).put("privateKeyFile", key.toString())
        .put("head", "synthetic/topic").put("base", "main").put("headSha", "a".repeat(40))
        .put("baseSha", "b".repeat(40)).put("draft", true);
    var permissions = json.putObject("permissions");
    GitHubInstallationConfiguration.requiredPermissions().forEach(permissions::put); write();
  }
  void write() throws Exception { privateFile(config, JSON.writeValueAsBytes(json)); }
  static void privateFile(Path file, byte[] bytes) throws Exception {
    Files.write(file, bytes); Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
  }
}
