package io.tracepermit.adapters.remote.github;

/** Trusted operator input, never constructed from a public action or provider response. */
public record GitHubPilotTargetBinding(
    long installationId, long repositoryId, long ownerId, String repository,
    String head, String base, String headSha, String baseSha) {
  // Frozen canonical JSON v1 converts numbers through double; identifiers must remain exact.
  private static final long MAXIMUM_EXACT_ID = 9_007_199_254_740_991L;

  public GitHubPilotTargetBinding {
    require(exactId(installationId) && exactId(repositoryId) && exactId(ownerId));
    require(GitHubDraftPullRequestAction.ALLOWED_REPOSITORY.equals(repository));
    require(GitHubDraftPullRequestAction.ALLOWED_BASE_BRANCH.equals(base));
    require(validRef(head) && !base.equals(head));
    require(headSha != null && headSha.matches("[a-f0-9]{40}"));
    require(baseSha != null && baseSha.matches("[a-f0-9]{40}"));
  }

  private static boolean exactId(long value) { return value > 0 && value <= MAXIMUM_EXACT_ID; }

  static boolean validRef(String ref) {
    if (ref == null || !ref.matches("[A-Za-z0-9_][A-Za-z0-9._/-]{0,254}")
        || ref.endsWith("/") || ref.endsWith(".") || ref.contains("..") || ref.contains("//")) return false;
    for (String component : ref.split("/")) {
      if (component.startsWith(".") || component.endsWith(".lock")) return false;
    }
    return true;
  }

  void requireAction(GitHubDraftPullRequestAction action) {
    require(repository.equals(action.repository()) && head.equals(action.head()) && base.equals(action.base()));
  }

  String fingerprint() {
    return io.tracepermit.contracts.CanonicalJson.sha256(io.tracepermit.contracts.JsonSupport.MAPPER.createObjectNode()
        .put("installationId", installationId).put("repositoryId", repositoryId).put("ownerId", ownerId)
        .put("repository", repository).put("head", head).put("base", base).put("headSha", headSha).put("baseSha", baseSha));
  }

  static void require(boolean condition) {
    if (!condition) throw new GitHubPullRequestException(
        "TARGET_BINDING_INVALID", "TARGET_BINDING_INVALID", "GitHub target binding was rejected.");
  }

  @Override public String toString() { return "GitHubPilotTargetBinding[redacted]"; }
}
