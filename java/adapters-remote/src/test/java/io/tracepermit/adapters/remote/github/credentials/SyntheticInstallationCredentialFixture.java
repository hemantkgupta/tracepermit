package io.tracepermit.adapters.remote.github.credentials;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.JsonSupport;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

/** Test-only factory crossing the package seam; never included in a shipped runtime. */
public final class SyntheticInstallationCredentialFixture {
  private final GitHubInstallationConfiguration config;
  private final Clock clock;
  private final String token = "synthetic-composition-" + UUID.randomUUID();
  private final List<String> calls = new ArrayList<>();
  private UnaryOperator<ObjectNode> installationResponse = value -> value;
  private Runnable cleanup = () -> {};
  private final GitHubInstallationCredentialProvider provider;

  public SyntheticInstallationCredentialFixture(GitHubInstallationConfiguration config, Clock clock) throws Exception {
    this.config = config; this.clock = clock;
    var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
    var key = generator.generateKeyPair().getPrivate();
    provider = new GitHubInstallationCredentialProvider(config, () -> { calls.add("KEY"); return key; }, () -> {
      calls.add("OPEN");
      return new CredentialExchange() {
        @Override public Reply installation(String jwt) {
          calls.add("GET_INSTALLATION");
          return reply(200, installationResponse.apply(SyntheticInstallationCredentialFixture.this.installation()));
        }
        @Override public Reply token(String jwt, JsonNode restrictions) {
          calls.add("POST_TOKEN");
          var expected = JsonSupport.MAPPER.createObjectNode();
          expected.putArray("repository_ids").add(config.repositoryId());
          var permissions = expected.putObject("permissions"); config.permissions().forEach(permissions::put);
          if (!expected.equals(restrictions)) throw new AssertionError("SYNTHETIC_RESTRICTIONS_CHANGED");
          return reply(201, tokenResponse());
        }
        @Override public void close() { calls.add("CLOSE"); cleanup.run(); }
      };
    }, clock);
  }
  public GitHubInstallationCredentialProvider provider() { return provider; }
  public List<String> calls() { return List.copyOf(calls); }
  public boolean authorizes(String header) { return ("Bearer " + token).equals(header); }
  public boolean containsSecret(String value) { return value.contains(token); }
  public void changeInstallationResponse(UnaryOperator<ObjectNode> change) { installationResponse = change; }
  public void onCleanup(Runnable close) { cleanup = close; }

  private ObjectNode installation() {
    var value = JsonSupport.MAPPER.createObjectNode().put("id", config.installationId()).put("app_id", config.appId())
        .put("target_id", config.ownerId()).put("target_type", "Organization").put("repository_selection", "selected").putNull("suspended_at");
    value.putObject("account").put("id", config.ownerId()).put("login", config.repository().split("/")[0]).put("type", "Organization");
    var permissions = value.putObject("permissions"); config.permissions().forEach(permissions::put); return value;
  }
  private ObjectNode tokenResponse() {
    var value = JsonSupport.MAPPER.createObjectNode().put("token", token).put("expires_at", clock.instant().plusSeconds(120).toString())
        .put("repository_selection", "selected");
    var permissions = value.putObject("permissions"); config.permissions().forEach(permissions::put);
    value.putArray("repositories").addObject().put("id", config.repositoryId()).put("name", config.repository().split("/")[1])
        .put("full_name", config.repository()).put("private", true).put("fork", false).put("archived", false).put("disabled", false)
        .putObject("owner").put("id", config.ownerId()).put("login", config.repository().split("/")[0]);
    return value;
  }
  private static CredentialExchange.Reply reply(int status, JsonNode node) {
    return new CredentialExchange.Reply(status, false, node.toString().getBytes(StandardCharsets.UTF_8));
  }
  @Override public String toString() { return "SyntheticInstallationCredentialFixture[redacted]"; }
}
