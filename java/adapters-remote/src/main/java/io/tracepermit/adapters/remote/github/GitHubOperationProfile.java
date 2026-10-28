package io.tracepermit.adapters.remote.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.application.BusinessOperationScope;
import java.util.Map;

/** Closed target-profile contract shared by registration and the durable operation lifecycle. */
public sealed interface GitHubOperationProfile
    permits GitHubBusinessOperationBinding.ExpectedProfile,
        GitHubOrganizationOperationBinding.ExpectedProfile {
  String adapterId();

  String environment();

  long recoveryGeneration();

  GitHubBusinessOperationBinding.PolicyRelease policyRelease();

  Map<String, String> artifactDigests();

  GitHubTargetBinding targetBinding();

  ObjectNode resourceBinding();

  String fingerprint();

  BoundGitHubOperation bind(BusinessOperationScope trustedScope, JsonNode storedOperation);
}
