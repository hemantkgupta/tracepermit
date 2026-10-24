package io.tracepermit.adapters.remote.github;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.time.Clock;
import java.util.Map;

/** GET-only observations; there is deliberately no assertion of atomic ref compare-and-swap. */
final class GitHubTargetPreflight {
  private GitHubTargetPreflight() {}

  static void verify(GitHubApiTransport.Session session, GitHubPilotTargetBinding binding,
      GitHubAppCredentials credentials, Clock clock) throws IOException {
    var installation = session.request(new String[]{"installation", "repositories"},
        Map.of("per_page", "100", "page", "1"), null, credentials, binding.installationId(), clock);
    require(installation.status() == 200 && !installation.pagination());
    JsonNode access = installation.json();
    require(positiveLong(access.get("total_count")) == 1
        && access.path("repositories").isArray() && access.path("repositories").size() == 1);
    repository(access.path("repositories").get(0), binding);
    var repo = session.request(repoPath(binding), Map.of(), null, credentials, binding.installationId(), clock);
    require(repo.status() == 200 && !repo.pagination());
    repository(repo.json(), binding);
    ref(session, binding, credentials, clock, binding.head(), binding.headSha());
    ref(session, binding, credentials, clock, binding.base(), binding.baseSha());
  }

  static String[] repoPath(GitHubPilotTargetBinding binding, String... suffix) {
    String[] parts = binding.repository().split("/");
    String[] result = new String[3 + suffix.length];
    result[0] = "repos"; result[1] = parts[0]; result[2] = parts[1];
    System.arraycopy(suffix, 0, result, 3, suffix.length);
    return result;
  }

  private static void ref(GitHubApiTransport.Session session, GitHubPilotTargetBinding binding,
      GitHubAppCredentials credentials, Clock clock, String name, String sha) throws IOException {
    String[] segments = ("git/ref/heads/" + name).split("/");
    var response = session.request(repoPath(binding, segments), Map.of(), null, credentials, binding.installationId(), clock);
    require(response.status() == 200 && !response.pagination());
    JsonNode ref = response.json();
    require(("refs/heads/" + name).equals(text(ref, "ref"))
        && "commit".equals(text(ref.path("object"), "type"))
        && sha.equals(text(ref.path("object"), "sha")));
  }

  static void repository(JsonNode repo, GitHubPilotTargetBinding binding) {
    require(repo != null && repo.isObject() && positiveLong(repo.get("id")) == binding.repositoryId()
        && binding.repository().equals(text(repo, "full_name"))
        && binding.repository().split("/")[1].equals(text(repo, "name"))
        && positiveLong(repo.path("owner").get("id")) == binding.ownerId()
        && binding.repository().split("/")[0].equals(text(repo.path("owner"), "login"))
        && isBoolean(repo, "private", true) && isBoolean(repo, "archived", false)
        && isBoolean(repo, "disabled", false) && isBoolean(repo, "fork", false));
  }

  static GitHubDraftPullRequestResult confirmation(JsonNode pr, GitHubPilotTargetBinding binding,
      String key, GitHubAppCredentials credential) {
    long number = positiveLong(pr.get("number"));
    long id = positiveLong(pr.get("id"));
    require(number <= Integer.MAX_VALUE && isBoolean(pr, "draft", true) && "open".equals(text(pr, "state"))
        && (!pr.has("merged") || isBoolean(pr, "merged", false))
        && (!pr.has("merged_at") || pr.path("merged_at").isNull()));
    String html = "https://github.com/" + binding.repository() + "/pull/" + number;
    require(html.equals(text(pr, "html_url")));
    side(pr.path("head"), binding.head(), binding.headSha(), binding);
    side(pr.path("base"), binding.base(), binding.baseSha(), binding);
    return new GitHubDraftPullRequestResult((int) number, id, html, true, "open", binding.repository(),
        binding.head(), binding.base(), key, credential.credentialFingerprint(), binding.installationId());
  }

  private static void side(JsonNode side, String ref, String sha, GitHubPilotTargetBinding binding) {
    require(ref.equals(text(side, "ref")) && sha.equals(text(side, "sha")));
    repository(side.path("repo"), binding);
  }

  static long positiveLong(JsonNode node) {
    require(node != null && node.isIntegralNumber() && node.canConvertToLong() && node.longValue() > 0);
    return node.longValue();
  }
  static String text(JsonNode node, String field) {
    require(node != null && node.path(field).isTextual()); return node.path(field).textValue();
  }
  /** Never coerce scalars/containers; explicit null is compatible only with an empty body. */
  static boolean bodyMatches(JsonNode body, String expected) {
    return body != null && ((body.isTextual() && expected.equals(body.textValue()))
        || (body.isNull() && expected.isEmpty()));
  }
  private static boolean isBoolean(JsonNode node, String field, boolean value) {
    return node.path(field).isBoolean() && node.path(field).booleanValue() == value;
  }
  private static void require(boolean condition) {
    if (!condition) throw GitHubApiTransport.rejected("TARGET_PREFLIGHT_OR_IDENTITY_REJECTED");
  }
}
