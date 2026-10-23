package io.tracepermit.adapters.remote.github;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.JsonSupport;

/**
 * Secret-free result of a bounded GitHub draft pull request creation.
 */
public record GitHubDraftPullRequestResult(
    int number,
    long id,
    String htmlUrl,
    boolean draft,
    String state,
    String repository,
    String head,
    String base,
    String idempotencyKey,
    String credentialFingerprint,
    long installationId) {

  public ObjectNode toJsonNode() {
    ObjectNode node = JsonSupport.MAPPER.createObjectNode();
    node.put("schemaVersion", "1.0");
    node.put("mode", "pilot");
    node.put("operation", GitHubDraftPullRequestAction.OPERATION);
    node.put("repository", repository);
    node.put("number", number);
    node.put("id", id);
    node.put("htmlUrl", htmlUrl);
    node.put("draft", draft);
    node.put("state", state);
    node.put("head", head);
    node.put("base", base);
    node.put("idempotencyKey", idempotencyKey);
    node.put("credentialFingerprint", credentialFingerprint);
    node.put("installationId", installationId);
    return node;
  }
}
