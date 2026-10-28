package io.tracepermit.adapters.remote.github;

import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import java.util.Map;
import java.util.TreeMap;

/** Exact organization-owned non-production repository and immutable ref preconditions. */
public record GitHubOrganizationTargetBinding(
    long appId,
    long installationId,
    long repositoryId,
    long ownerId,
    String repository,
    String head,
    String base,
    String headSha,
    String baseSha,
    Map<String, String> permissions)
    implements GitHubTargetBinding {
  private static final long MAXIMUM_EXACT_ID = 9_007_199_254_740_991L;
  private static final Map<String, String> REQUIRED_PERMISSIONS =
      Map.of("contents", "read", "metadata", "read", "pull_requests", "write");

  public GitHubOrganizationTargetBinding {
    require(
        exactId(appId)
            && exactId(installationId)
            && exactId(repositoryId)
            && exactId(ownerId));
    require(
        repository != null
            && repository.matches(
                "[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9][A-Za-z0-9._-]{0,99}"));
    require(validRef(head) && validRef(base) && !base.equals(head));
    require(headSha != null && headSha.matches("[a-f0-9]{40}"));
    require(baseSha != null && baseSha.matches("[a-f0-9]{40}"));
    permissions = copyPermissions(permissions);
    require(REQUIRED_PERMISSIONS.equals(permissions));
  }

  private static boolean exactId(long value) {
    return value > 0 && value <= MAXIMUM_EXACT_ID;
  }

  private static boolean validRef(String ref) {
    if (ref == null
        || !ref.matches("[A-Za-z0-9_][A-Za-z0-9._/-]{0,254}")
        || ref.endsWith("/")
        || ref.endsWith(".")
        || ref.contains("..")
        || ref.contains("//")) {
      return false;
    }
    for (String component : ref.split("/")) {
      if (component.startsWith(".") || component.endsWith(".lock")) return false;
    }
    return true;
  }

  @Override
  public String fingerprint() {
    var material =
        JsonSupport.MAPPER
            .createObjectNode()
            .put("profile", "tracepermit-github-organization-target-v1")
            .put("appId", appId)
            .put("installationId", installationId)
            .put("repositoryId", repositoryId)
            .put("ownerId", ownerId)
            .put("repository", repository)
            .put("head", head)
            .put("base", base)
            .put("headSha", headSha)
            .put("baseSha", baseSha);
    var permissionPins = material.putObject("permissions");
    new TreeMap<>(permissions).forEach(permissionPins::put);
    return CanonicalJson.sha256(material);
  }

  public static Map<String, String> requiredPermissions() {
    return REQUIRED_PERMISSIONS;
  }

  private static Map<String, String> copyPermissions(Map<String, String> value) {
    try {
      require(value != null);
      return Map.copyOf(value);
    } catch (RuntimeException rejected) {
      throw invalid();
    }
  }

  private static void require(boolean condition) {
    if (!condition) throw invalid();
  }

  private static GitHubPullRequestException invalid() {
    return new GitHubPullRequestException(
        "TARGET_BINDING_INVALID",
        "TARGET_BINDING_INVALID",
        "GitHub target binding was rejected.");
  }

  @Override
  public String toString() {
    return "GitHubOrganizationTargetBinding[redacted]";
  }
}
