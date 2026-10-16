package io.tracepermit.policy;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.contracts.CanonicalJson;

import java.time.Instant;
import java.util.Objects;

public record PolicyVersionRecord(
    String policyId,
    int version,
    String name,
    String description,
    PolicyState state,
    String contentHash,
    JsonNode document,
    Instant stagedAt,
    Instant activatedAt,
    Instant deprecatedAt,
    Instant rolledBackAt,
    Integer rollbackTargetVersion) {

  public PolicyVersionRecord {
    Objects.requireNonNull(policyId, "policyId must not be null");
    Objects.requireNonNull(name, "name must not be null");
    Objects.requireNonNull(description, "description must not be null");
    Objects.requireNonNull(state, "state must not be null");
    Objects.requireNonNull(contentHash, "contentHash must not be null");
    Objects.requireNonNull(document, "document must not be null");
    Objects.requireNonNull(stagedAt, "stagedAt must not be null");
    document = document.deepCopy();
  }

  public static PolicyVersionRecord staged(JsonNode policy, Instant now) {
    JsonNode validated = PolicyValidator.validate(policy);
    return new PolicyVersionRecord(
        validated.path("id").asText(),
        validated.path("version").asInt(),
        validated.path("name").asText(),
        validated.path("description").asText(),
        PolicyState.STAGED,
        CanonicalJson.sha256(validated),
        validated,
        now,
        null,
        null,
        null,
        null);
  }

  public PolicyVersionRecord activate(Instant now) {
    return new PolicyVersionRecord(
        policyId,
        version,
        name,
        description,
        PolicyState.ACTIVE,
        contentHash,
        document,
        stagedAt,
        now,
        null,
        null,
        rollbackTargetVersion);
  }

  public PolicyVersionRecord deprecate(Instant now) {
    return new PolicyVersionRecord(
        policyId,
        version,
        name,
        description,
        PolicyState.DEPRECATED,
        contentHash,
        document,
        stagedAt,
        activatedAt,
        now,
        rolledBackAt,
        rollbackTargetVersion);
  }

  public PolicyVersionRecord rollback(Instant now, int targetVersion) {
    return new PolicyVersionRecord(
        policyId,
        version,
        name,
        description,
        PolicyState.ROLLED_BACK,
        contentHash,
        document,
        stagedAt,
        activatedAt,
        deprecatedAt,
        now,
        targetVersion);
  }

  public boolean isActive() {
    return state == PolicyState.ACTIVE;
  }

  public boolean isStaged() {
    return state == PolicyState.STAGED;
  }

  public boolean isRolledBack() {
    return state == PolicyState.ROLLED_BACK;
  }
}
