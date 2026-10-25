package io.tracepermit.application;

import static io.tracepermit.application.BusinessOperationStorageException.Code.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.contracts.BusinessOperationValidator;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.policy.PolicyDecision;
import io.tracepermit.policy.PolicyEvaluator;
import io.tracepermit.policy.PolicyValidator;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Immutable operator-owned static review profile, NOT runtime-loaded request configuration, current
 * database authority or a dispatch capability. A transaction owner must obtain this from trusted
 * operator configuration, never derive the expected pins from the operation it is checking. No
 * credential, callback, signer, repository or target port is accepted here.
 */
public final class BusinessOperationAuthorityProfile {
  private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
  private final BusinessOperationScope scope;
  private final String adapterId;
  private final long recoveryGeneration;
  private final JsonNode policy;
  private final String policyHash;
  private final String artifactDigestsHash;
  private final String resourceBindingHash;
  private final String fingerprint;

  public BusinessOperationAuthorityProfile(BusinessOperationScope scope, String adapterId,
      long recoveryGeneration, JsonNode operatorPolicy, String artifactDigestsHash,
      String resourceBindingHash) {
    try {
      if (scope == null || adapterId == null
          || !adapterId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
          || recoveryGeneration < 1 || recoveryGeneration > MAX_SAFE_INTEGER
          || !hash(artifactDigestsHash) || !hash(resourceBindingHash) || operatorPolicy == null
          || canonicalBytes(operatorPolicy) > 65_536) {
        throw error(OPERATION_PROFILE_INVALID);
      }
      this.policy = PolicyValidator.validate(operatorPolicy).deepCopy();
      if (!this.policy.path("enabled").asBoolean()
          || canonicalBytes(this.policy) > 65_536) {
        throw error(OPERATION_PROFILE_INVALID);
      }
      this.scope = scope;
      this.adapterId = adapterId;
      this.recoveryGeneration = recoveryGeneration;
      this.policyHash = CanonicalJson.sha256(this.policy);
      this.artifactDigestsHash = artifactDigestsHash;
      this.resourceBindingHash = resourceBindingHash;
      var pins = JsonSupport.MAPPER.createObjectNode();
      pins.put("profile", "tracepermit-operation-review-v1");
      pins.put("scopeHash", scope.fingerprint());
      pins.put("adapterId", adapterId);
      pins.put("recoveryGeneration", recoveryGeneration);
      pins.put("policyHash", policyHash);
      pins.put("artifactDigestsHash", artifactDigestsHash);
      pins.put("resourceBindingHash", resourceBindingHash);
      this.fingerprint = CanonicalJson.sha256(pins);
    } catch (RuntimeException failure) {
      // Discard validator/canonicalizer messages: operator JSON can contain private values.
      throw error(OPERATION_PROFILE_INVALID);
    }
  }

  public String fingerprint() {
    return fingerprint;
  }

  public BusinessOperationScope scope() {
    return scope;
  }

  public long recoveryGeneration() {
    return recoveryGeneration;
  }

  /**
   * Re-evaluates the canonical action against the copied operator policy. The returned immutable
   * decision is data, not proof; a store must call its OWN profile rather than accept caller results.
   * This first review-only profile refuses allow/sanitize instead of manufacturing approval.
   */
  public PolicyDecision evaluate(JsonNode snapshot) {
    JsonNode operation;
    try {
      operation = BusinessOperationValidator.validate(snapshot);
      if (canonicalBytes(operation) > 262_144) {
        throw error(OPERATION_PROFILE_MISMATCH);
      }
    } catch (RuntimeException failure) {
      throw error(OPERATION_PROFILE_MISMATCH);
    }
    var release = operation.path("policyRelease");
    if (!scope.trustedContext().equals(operation.path("trustedContext"))
        || !adapterId.equals(operation.path("adapterId").asText())
        || recoveryGeneration != operation.path("recoveryGeneration").asLong()
        || !policy.path("id").asText().equals(release.path("policyId").asText())
        || policy.path("version").asInt() != release.path("version").asLong()
        || !policyHash.equals(release.path("contentHash").asText())
        || !artifactDigestsHash.equals(CanonicalJson.sha256(operation.path("artifactDigests")))
        || !resourceBindingHash.equals(operation.path("resourceBindingHash").asText())) {
      throw error(OPERATION_PROFILE_MISMATCH);
    }
    PolicyDecision decision = new PolicyEvaluator().evaluate(operation.path("canonicalAction"), policy).decision();
    if (!"block".equals(decision.outcome()) && !"require_approval".equals(decision.outcome())) {
      throw error(OPERATION_POLICY_OUTCOME_UNSUPPORTED);
    }
    return decision;
  }

  private static int canonicalBytes(JsonNode value) {
    try {
      // getBytes(UTF_8) silently replaces lone surrogates with '?'. Reject instead before
      // forming exact content pins; leave frozen canonical v1 and all valid bytes unchanged.
      return StandardCharsets.UTF_8.newEncoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .encode(CharBuffer.wrap(CanonicalJson.canonicalize(value))).remaining();
    } catch (CharacterCodingException rejected) {
      throw error(OPERATION_PROFILE_INVALID);
    }
  }

  private static boolean hash(String value) {
    return value != null && value.matches("sha256:[a-f0-9]{64}");
  }

  @Override
  public String toString() {
    return "BusinessOperationAuthorityProfile[redacted; static pins, not durable authority]";
  }

  private static BusinessOperationStorageException error(BusinessOperationStorageException.Code code) {
    return new BusinessOperationStorageException(code);
  }
}
