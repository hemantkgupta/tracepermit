package io.tracepermit.agentcore.lifecyclecli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.adapters.remote.agentcore.AgentCoreRegistrationIngress;
import io.tracepermit.adapters.remote.github.GitHubBusinessOperationBinding;
import io.tracepermit.adapters.remote.github.GitHubPilotTargetBinding;
import io.tracepermit.agentcore.registration.DurableAgentCoreRegistration;
import io.tracepermit.application.BusinessOperationAdmissionSettings;
import io.tracepermit.application.BusinessOperationAuthorityProfile;
import io.tracepermit.application.BusinessOperationReviewSettings;
import io.tracepermit.application.BusinessOperationScope;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.persistence.operations.JdbcBusinessOperationRepository;
import io.tracepermit.persistence.operations.OperationAuthorityAdministration;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** Reuses the archive, real identity and restricted V7 database fixtures without changing them. */
final class InstalledLifecycleCommitLossFixture implements AutoCloseable {
  private static final String HASH = "sha256:" + "a".repeat(64);
  private static final String GROUP = "synthetic-reviewers";
  private static final ObjectNode POLICY = policy();
  final InstalledLifecycleArchive installed;
  private final ArchiveEvidence archiveEvidence;
  final LifecycleTestDatabase database;
  final LifecycleTestIdentity identity = new LifecycleTestIdentity();
  final KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
  final String operationId;
  final ObjectNode config;

  InstalledLifecycleCommitLossFixture(String image) throws Exception {
    var retained = ArchiveEvidence.extract();
    installed = retained.archive();
    archiveEvidence = retained.evidence();
    LifecycleTestDatabase created;
    try {
      created = new LifecycleTestDatabase(image);
    } catch (Throwable failure) {
      installed.close();
      throw failure;
    }
    database = created;
    try {
      var scope =
          new BusinessOperationScope(
              LifecycleTestIdentity.TENANT,
              LifecycleTestIdentity.WORKSPACE,
              identity.requesterPrincipal());
      var expected =
          new GitHubBusinessOperationBinding.ExpectedProfile(
              "github-draft-pr-v1", "test", 1,
              new GitHubBusinessOperationBinding.PolicyRelease(
                  "synthetic-lifecycle-policy", 1, CanonicalJson.sha256(POLICY)),
              Map.of("policy_bundle", HASH, "adapter_release", HASH),
              new GitHubPilotTargetBinding(
                  71, 101, 201, "tracepermit/agentcore-pr-pilot", "synthetic-change", "main",
                  "a".repeat(40), "b".repeat(40)));
      var enrollment =
          new AgentCoreRegistrationIngress.Enrollment(
              LifecycleTestIdentity.REGISTRATION_ISSUER,
              LifecycleTestIdentity.REGISTRATION_ISSUER + "#" + LifecycleTestIdentity.REGISTRATION_SUBJECT,
              LifecycleTestIdentity.REGISTRATION_CLIENT, scope, expected,
              "synthetic-retention", Duration.ofDays(30));
      var registration =
          DurableAgentCoreRegistration.create(
              identity.registrationVerifier(), enrollment, Clock.systemUTC(), database.replay,
              database.operations, 64, Duration.ofSeconds(2));
      ObjectNode request =
          JsonSupport.MAPPER.createObjectNode().put("schemaVersion", "1.0")
              .put("clientRequestKey", "installed-commit-loss")
              .put("title", "Synthetic installed COMMIT reply loss");
      var receipt = registration.register(identity.registrationToken(), null, bytes(request));
      assertThat(receipt.created()).isTrue();
      operationId = receipt.operationId();
      JsonNode initial =
          new JdbcBusinessOperationRepository(database.operations).find(scope, operationId).orElseThrow();
      var roster = JsonSupport.MAPPER.createArrayNode();
      roster.addObject().put("verifiedSubjectId", identity.reviewerVerifiedSubject())
          .put("clientId", LifecycleTestIdentity.REVIEWER_CLIENT)
          .put("tenantId", scope.tenantId()).put("workspaceId", scope.workspaceId())
          .put("groupId", GROUP).put("human", true);
      String membership = LifecycleMemberships.parse(roster).fingerprint();
      var authority =
          new BusinessOperationAuthorityProfile(
              scope, expected.adapterId(), 1, POLICY,
              CanonicalJson.sha256(initial.path("artifactDigests")),
              initial.path("resourceBindingHash").asText(),
              new BusinessOperationReviewSettings(GROUP, membership, Duration.ofMinutes(2)),
              new BusinessOperationAdmissionSettings(
                  "https://synthetic-permit-issuer.invalid", "synthetic-permit-key",
                  "synthetic-operation-admission", keys.getPublic(), Duration.ofSeconds(60)));
      OperationAuthorityAdministration.stageNew(database.operationAdmin, authority);
      OperationAuthorityAdministration.activateStaged(database.operationAdmin, authority);
      config = JsonSupport.MAPPER.createObjectNode();
      config.put("schemaVersion", "1.0").put("profile", LifecycleProtocol.PROFILE);
      config.set("operations", database.operationConfig());
      config.putObject("scope").put("tenantId", scope.tenantId())
          .put("workspaceId", scope.workspaceId())
          .put("requesterPrincipalId", scope.requesterPrincipalId());
      ObjectNode target = config.putObject("expectedTarget");
      target.put("adapterId", expected.adapterId()).put("environment", expected.environment())
          .put("recoveryGeneration", 1).put("policyId", expected.policyRelease().policyId())
          .put("policyVersion", 1).put("policyContentHash", expected.policyRelease().contentHash());
      expected.artifactDigests().forEach(target.putObject("artifactDigests")::put);
      var binding = expected.targetBinding();
      target.put("installationId", binding.installationId()).put("repositoryId", binding.repositoryId())
          .put("ownerId", binding.ownerId()).put("repository", binding.repository())
          .put("head", binding.head()).put("base", binding.base())
          .put("headSha", binding.headSha()).put("baseSha", binding.baseSha());
      ObjectNode profile = config.putObject("authority");
      profile.put("adapterId", expected.adapterId()).put("recoveryGeneration", 1).set("policy", POLICY.deepCopy());
      profile.put("artifactDigestsHash", CanonicalJson.sha256(initial.path("artifactDigests")))
          .put("resourceBindingHash", initial.path("resourceBindingHash").asText())
          .put("reviewerGroupId", GROUP).put("membershipSourceFingerprint", membership)
          .put("approvalLifetimeSeconds", 120).put("permitIssuer", "https://synthetic-permit-issuer.invalid")
          .put("permitKeyId", "synthetic-permit-key").put("permitAudience", "synthetic-operation-admission")
          .put("permitPublicKeySpki", Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()))
          .put("permitLifetimeSeconds", 60);
      config.set("requesterProvider", identity.requesterProvider());
      config.set("reviewerProvider", identity.reviewerProvider());
      config.putObject("requesterEnrollment")
          .put("verifiedSubjectId", identity.requesterVerifiedSubject())
          .put("clientId", LifecycleTestIdentity.REQUESTER_CLIENT).put("tenantId", scope.tenantId())
          .put("workspaceId", scope.workspaceId()).put("requesterPrincipalId", scope.requesterPrincipalId());
      config.set("reviewerMemberships", roster);
      assertThat(call("evaluate", command(), false).outcome()).isEqualTo("EVALUATED");
      assertThat(call("review", command().put("decision", "APPROVE"), true).outcome())
          .isEqualTo("REVIEW_APPROVED");
    } catch (Throwable failure) {
      close();
      throw failure;
    }
  }

  ObjectNode command() {
    return JsonSupport.MAPPER.createObjectNode().put("schemaVersion", "1.0")
        .put("profile", LifecycleProtocol.PROFILE).put("operationId", operationId);
  }

  byte[] delivery(ObjectNode configuration, String outcome) throws Exception {
    return frames(bytes(configuration), identity.requesterToken().getBytes(StandardCharsets.UTF_8),
        keys.getPrivate().getEncoded(), bytes(command().put("syntheticOutcome", outcome)));
  }

  LifecycleReceipt call(String name, ObjectNode command, boolean reviewer) throws Exception {
    String token = reviewer ? identity.reviewerToken() : identity.requesterToken();
    return installed.call(name, frames(bytes(config), token.getBytes(StandardCharsets.UTF_8), bytes(command)));
  }

  /** Independent restricted connection bypassing the gate, one repeatable-read observation. */
  Observation observe() {
    try (var connection = database.operations.getConnection()) {
      connection.setReadOnly(true);
      connection.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
      connection.setAutoCommit(false);
      try {
        var evidence = new ArrayList<String>();
        var events = new ArrayList<JsonNode>();
        for (String table : List.of("tp_operation_admissions", "tp_operation_admission_outbox",
            "tp_operation_lifecycle_facts", "tp_operation_delivery_events")) {
          String order = table.endsWith("facts") ? "sequence_number"
              : table.endsWith("events") ? "event_sequence" : "operation_id";
          try (var statement = connection.prepareStatement(
              "SELECT * FROM " + table + " WHERE operation_id = ? ORDER BY " + order)) {
            statement.setQueryTimeout(5);
            statement.setString(1, operationId);
            try (var rows = statement.executeQuery()) {
              int count = 0;
              while (rows.next()) {
                assertThat(++count).isLessThanOrEqualTo(1024);
                ObjectNode row = JsonSupport.MAPPER.createObjectNode();
                for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) {
                  row.put(rows.getMetaData().getColumnName(column), rows.getString(column));
                }
                evidence.add(table + ":" + CanonicalJson.sha256(row));
                if (table.endsWith("events")) {
                  JsonNode event = JsonSupport.MAPPER.readTree(rows.getString("event_json"));
                  assertThat(CanonicalJson.sha256(event)).isEqualTo(rows.getString("event_hash"));
                  assertThat(event.path("sequence").asInt()).isEqualTo(count);
                  events.add(event);
                }
              }
              if (table.equals("tp_operation_admissions") || table.equals("tp_operation_admission_outbox")) {
                assertThat(count).isBetween(0, 1);
              }
            }
          }
        }
        try (var statement = connection.prepareStatement(
            "SELECT snapshot_hash, snapshot_json FROM tp_operation_lifecycle_heads WHERE operation_id = ?")) {
          statement.setQueryTimeout(5);
          statement.setString(1, operationId);
          try (var rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
            JsonNode head = JsonSupport.MAPPER.readTree(rows.getString(2));
            String hash = rows.getString(1);
            assertThat(hash).isEqualTo(CanonicalJson.sha256(head));
            evidence.add(hash);
            assertThat(rows.next()).isFalse();
            return new Observation(List.copyOf(events), head.path("execution").path("status").asText(),
                List.copyOf(evidence));
          }
        }
      } finally {
        connection.rollback();
      }
    } catch (Exception failure) {
      throw new AssertionError("INDEPENDENT_COMMIT_OBSERVATION_FAILED");
    }
  }

  record Observation(List<JsonNode> events, String execution, List<String> rowHashes) {}

  private static byte[] bytes(JsonNode value) {
    return value.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] frames(byte[]... values) throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var output = new DataOutputStream(bytes)) {
      for (byte[] value : values) {
        output.writeInt(value.length);
        output.write(value);
      }
    }
    return bytes.toByteArray();
  }

  private static ObjectNode policy() {
    try {
      return (ObjectNode) JsonSupport.MAPPER.readTree("""
          {"schemaVersion":"1.0","id":"synthetic-lifecycle-policy","version":1,
           "name":"Synthetic lifecycle","description":"Synthetic only","enabled":true,
           "defaultDecision":{"outcome":"block","reasonCode":"SYNTHETIC_DENY"},
           "rules":[{"id":"exact-draft-pr","priority":1,"description":"Synthetic",
           "match":{"toolNames":["github.pull_request.create"]},
           "decision":{"outcome":"require_approval","reasonCode":"SYNTHETIC_REVIEW",
           "approvalGroup":"synthetic-reviewers"}}]}
          """);
    } catch (Exception failure) {
      throw new AssertionError("SYNTHETIC_POLICY_INVALID");
    }
  }

  /** Optional private evidence for a focused, single-fixture manual qualification invocation. */
  private record RetainedArchive(InstalledLifecycleArchive archive, ArchiveEvidence evidence) {}

  private record ArchiveEvidence(java.nio.file.Path directory, String archiveHash,
      String extractedInventory) {
    private static final String ROOT_VARIABLE = "TRACEPERMIT_COMMIT_LOSS_EVIDENCE_DIR";
    private static final String ARCHIVE_PROPERTY = "tracepermit.lifecycle.archive";

    static RetainedArchive extract() throws Exception {
      String configuredRoot = System.getenv(ROOT_VARIABLE);
      if (configuredRoot == null || configuredRoot.isBlank()) {
        return new RetainedArchive(new InstalledLifecycleArchive(), null);
      }
      java.nio.file.Path root = java.nio.file.Path.of(configuredRoot).toRealPath();
      if (!java.nio.file.Files.isDirectory(root)) throw new AssertionError("ARCHIVE_EVIDENCE_ROOT_INVALID");
      java.nio.file.Path source = java.nio.file.Path.of(System.getProperty(ARCHIVE_PROPERTY)).toRealPath();
      java.nio.file.Path evidence = java.nio.file.Files.createTempDirectory(root, "installed-archive-");
      java.nio.file.Files.setPosixFilePermissions(evidence,
          java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
      java.nio.file.Path retained = evidence.resolve("executed-archive.tar");
      java.nio.file.Files.copy(source, retained);
      java.nio.file.Files.setPosixFilePermissions(retained,
          java.nio.file.attribute.PosixFilePermissions.fromString("r--------"));
      String expected = sha256(retained);
      assertThat(expected).isEqualTo(sha256(source));
      java.nio.file.Files.writeString(evidence.resolve("archive-before-extraction.sha256"), expected + "\n");
      var inventory = new ProcessBuilder("/usr/bin/tar", "--list", "--file", retained.toString(),
          "--quoting-style=escape")
          .redirectOutput(evidence.resolve("archive-inventory.txt").toFile())
          .redirectError(evidence.resolve("archive-inventory.stderr").toFile()).start();
      try {
        if (!inventory.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
          throw new AssertionError("ARCHIVE_INVENTORY_TIMEOUT");
        }
        java.nio.file.Files.writeString(evidence.resolve("archive-inventory.exit"),
            Integer.toString(inventory.exitValue()) + "\n");
        assertThat(inventory.exitValue()).isZero();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw interrupted;
      } finally {
        if (inventory.isAlive()) {
          inventory.destroyForcibly();
          boolean restoreInterrupt = Thread.interrupted();
          try {
            if (!inventory.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
              throw new AssertionError("ARCHIVE_INVENTORY_CLEANUP_TIMEOUT");
            }
          } catch (InterruptedException interrupted) {
            restoreInterrupt = true;
            throw interrupted;
          } finally {
            if (restoreInterrupt) Thread.currentThread().interrupt();
          }
        }
      }
      InstalledLifecycleArchive installed;
      // Focused manual runs select one case at a time. Restore the original property immediately;
      // no installed worker receives this test JVM's environment or system properties.
      synchronized (InstalledLifecycleArchive.class) {
        String previous = System.getProperty(ARCHIVE_PROPERTY);
        try {
          System.setProperty(ARCHIVE_PROPERTY, retained.toString());
          installed = new InstalledLifecycleArchive();
        } finally {
          if (previous == null) System.clearProperty(ARCHIVE_PROPERTY);
          else System.setProperty(ARCHIVE_PROPERTY, previous);
        }
      }
      try {
        assertThat(sha256(retained)).isEqualTo(expected);
        String extracted = inventory(installed.home);
        java.nio.file.Files.writeString(evidence.resolve("extracted-before-execution.txt"), extracted);
        return new RetainedArchive(installed, new ArchiveEvidence(evidence, expected, extracted));
      } catch (Throwable failure) {
        installed.close();
        throw failure;
      }
    }

    void verifyAfterExecution(InstalledLifecycleArchive archive) throws Exception {
      assertThat(sha256(directory.resolve("executed-archive.tar"))).isEqualTo(archiveHash);
      String after = inventory(archive.home);
      java.nio.file.Files.writeString(directory.resolve("extracted-after-execution.txt"), after);
      assertThat(after).isEqualTo(extractedInventory);
      java.nio.file.Files.writeString(directory.resolve("archive-after-execution.sha256"), archiveHash + "\n");
    }

    private static String inventory(java.nio.file.Path home) throws Exception {
      var lines = new ArrayList<String>();
      try (var files = java.nio.file.Files.walk(home)) {
        for (var path : files.sorted().toList()) {
          if (java.nio.file.Files.isSymbolicLink(path)) {
            lines.add("symlink " + home.relativize(path) + " -> " + java.nio.file.Files.readSymbolicLink(path));
          } else if (java.nio.file.Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            lines.add(sha256(path) + " " + java.nio.file.Files.size(path) + " " + home.relativize(path));
          }
        }
      }
      return String.join("\n", lines) + "\n";
    }

    private static String sha256(java.nio.file.Path path) throws Exception {
      var digest = java.security.MessageDigest.getInstance("SHA-256");
      try (var input = new java.security.DigestInputStream(java.nio.file.Files.newInputStream(path), digest)) {
        input.transferTo(java.io.OutputStream.nullOutputStream());
      }
      return java.util.HexFormat.of().formatHex(digest.digest());
    }
  }

  @Override
  public void close() throws java.io.IOException {
    try {
      database.close();
    } finally {
      try {
        if (archiveEvidence != null) archiveEvidence.verifyAfterExecution(installed);
      } catch (Exception failure) {
        throw new java.io.IOException("ARCHIVE_EVIDENCE_VERIFICATION_FAILED", failure);
      } finally {
        installed.close();
      }
    }
  }
}
