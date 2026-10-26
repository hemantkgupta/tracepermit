package io.tracepermit.adapters.remote.github.credentials;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

/** Generated signing key and synthetic exchange only; absent from the archive. */
public final class PilotCredentialFixture {
  private static final ObjectMapper JSON = new ObjectMapper();
  public final List<String> calls = new ArrayList<>();
  public UnaryOperator<ObjectNode> changeInstallation = node -> node;
  public UnaryOperator<ObjectNode> changeToken = node -> node;
  public Runnable cleanup = () -> {};
  public final GitHubInstallationCredentialProvider provider;
  private final GitHubInstallationConfiguration config;
  private final Clock clock;
  private final String token = "synthetic-pilot-" + UUID.randomUUID();

  public PilotCredentialFixture(GitHubInstallationConfiguration config, Clock clock) throws Exception {
    this.config = config; this.clock = clock;
    var keys = KeyPairGenerator.getInstance("RSA"); keys.initialize(2048);
    var key = keys.generateKeyPair().getPrivate();
    provider = new GitHubInstallationCredentialProvider(config, () -> { calls.add("KEY"); return key; }, () -> {
      calls.add("OPEN");
      return new CredentialExchange() {
        @Override public Reply installation(String jwt) {
          calls.add("GET_INSTALLATION"); return reply(200, changeInstallation.apply(PilotCredentialFixture.this.installation()));
        }
        @Override public Reply token(String jwt, JsonNode restrictions) {
          calls.add("POST_TOKEN");
          var expected = JSON.createObjectNode(); expected.putArray("repository_ids").add(config.repositoryId());
          var permissions = expected.putObject("permissions"); config.permissions().forEach(permissions::put);
          if (!expected.equals(restrictions)) throw new AssertionError("RESTRICTIONS_CHANGED");
          return reply(201, changeToken.apply(PilotCredentialFixture.this.token()));
        }
        @Override public void close() { calls.add("CLOSE"); cleanup.run(); }
      };
    }, clock);
  }
  public boolean authorizes(String value) { return ("Bearer " + token).equals(value); }
  public boolean leaks(String value) { return value.contains(token); }
  private ObjectNode installation() {
    var node = JSON.createObjectNode().put("id", config.installationId()).put("app_id", config.appId())
        .put("target_id", config.ownerId()).put("target_type", "Organization").put("repository_selection", "selected")
        .putNull("suspended_at");
    node.putObject("account").put("id", config.ownerId()).put("login", config.repository().split("/")[0])
        .put("type", "Organization");
    var permissions = node.putObject("permissions"); config.permissions().forEach(permissions::put); return node;
  }
  private ObjectNode token() {
    var node = JSON.createObjectNode().put("token", token).put("expires_at", clock.instant().plusSeconds(60).toString())
        .put("repository_selection", "selected");
    var permissions = node.putObject("permissions"); config.permissions().forEach(permissions::put);
    node.putArray("repositories").add(repository()); return node;
  }
  public ObjectNode repository() {
    var node = JSON.createObjectNode().put("id", config.repositoryId()).put("full_name", config.repository())
        .put("name", config.repository().split("/")[1]).put("private", true).put("archived", false)
        .put("disabled", false).put("fork", false);
    node.putObject("owner").put("id", config.ownerId()).put("login", config.repository().split("/")[0]); return node;
  }
  private static CredentialExchange.Reply reply(int status, JsonNode node) {
    try { return new CredentialExchange.Reply(status, false, JSON.writeValueAsBytes(node)); }
    catch (Exception failure) { throw new AssertionError("FIXTURE_JSON_FAILED"); }
  }
}
