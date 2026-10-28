package io.tracepermit.adapters.remote.github;

import com.fasterxml.jackson.databind.JsonNode;

/** Strictly bound stored data. This is data, never approval, admission or dispatch authority. */
public sealed interface BoundGitHubOperation
    permits GitHubBusinessOperationBinding.BoundOperationData,
        GitHubOrganizationOperationBinding.BoundOperationData {
  JsonNode snapshot();

  JsonNode canonicalAction();

  String canonicalActionHash();

  String operationId();

  long recoveryGeneration();

  String targetIdempotencyKeyHash();

  GitHubTargetBinding targetBinding();

  String profileFingerprint();

  String bindingDigest();
}
