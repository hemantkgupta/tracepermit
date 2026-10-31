package io.tracepermit.agentcore.organizationcli;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.adapters.remote.agentcore.Auth0OperationAuthorityException;
import io.tracepermit.application.BusinessOperationStorageException;
import io.tracepermit.application.BusinessOperationTransitionException;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.persistence.operations.JdbcGitHubOperationInspector;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exact Auth0 review facade. No identity override, signer, delivery or target callback. */
public final class Auth0OrganizationReview {
  public static final String PROFILE = "tracepermit-auth0-operation-review-v1";
  private Auth0OrganizationReview() {}

  /** Offline preparation only; private configuration is operator trust, not proof of identity. */
  public static Plan prepare(byte[] configuration, byte[] command) {
    try {
      require(configuration != null && configuration.length > 0 && configuration.length <= 65_536);
      require(command != null && command.length > 0 && command.length <= 1_024);
      JsonNode root = OrganizationOperatorProtocol.object(configuration);
      var config = OrganizationOperatorConfig.parse(root);
      require(config.usesAuth0());
      JsonNode request = OrganizationOperatorProtocol.object(command);
      OrganizationOperatorProtocol.fields(request,
          Set.of("schemaVersion", "profile", "operationId", "expectedSnapshotHash", "decision",
              "authorityProfileFingerprint", "expectedProfileFingerprint"), Set.of());
      require("1.0".equals(text(request, "schemaVersion", 3))
          && PROFILE.equals(text(request, "profile", 64)));
      String id = text(request, "operationId", 36);
      require(UUID.fromString(id).toString().equals(id));
      String hash = text(request, "expectedSnapshotHash", 71);
      require(hash.matches("sha256:[a-f0-9]{64}"));
      String decision = text(request, "decision", 7);
      require(Set.of("APPROVE", "DENY").contains(decision));
      require(config.authorityProfile().fingerprint()
          .equals(text(request, "authorityProfileFingerprint", 71)));
      require(config.expectedProfile().fingerprint()
          .equals(text(request, "expectedProfileFingerprint", 71)));
      // Retain only public reviewer binding material, not another copy of database credentials.
      return new Plan(config, id, hash, decision, root.path("reviewerProvider").deepCopy(),
          root.path("reviewerMemberships").deepCopy());
    } catch (RuntimeException failure) { throw rejected(); }
  }

  public static final class Plan {
    private final OrganizationOperatorConfig config;
    private final String operationId;
    private final String snapshotHash;
    private final String decision;
    private final JsonNode provider;
    private final JsonNode memberships;
    private final AtomicBoolean attempted = new AtomicBoolean();

    private Plan(OrganizationOperatorConfig config, String id, String hash, String decision,
        JsonNode provider, JsonNode memberships) {
      this.config = config; operationId = id; snapshotHash = hash; this.decision = decision;
      this.provider = provider; this.memberships = memberships;
    }

    /** Public pins must agree before opening a browser. This method grants no authority. */
    public void requireReviewerBinding(String issuer, String audience, String client, String subject,
        String tenant, String keyId, byte[] publicKeySpki) {
      try {
        require(provider.path("issuer").asText().equals(issuer)
            && provider.path("audience").asText().equals(audience)
            && provider.path("clientId").asText().equals(client)
            && provider.path("tenantValue").asText().equals(tenant)
            && provider.path("tenantId").asText().equals(tenant)
            && provider.path("keyId").asText().equals(keyId)
            && "https://tracepermit.org/claims/tenant_id".equals(provider.path("tenantClaim").asText())
            && "https://tracepermit.org/claims/roles".equals(provider.path("roleClaim").asText())
            && Arrays.equals(java.util.Base64.getDecoder().decode(provider.path("publicKeySpki").asText()), publicKeySpki));
        boolean enrolled = false;
        for (JsonNode entry : memberships) {
          enrolled |= entry.path("verifiedSubjectId").asText().equals(issuer + "#" + subject)
              && entry.path("clientId").asText().equals(client)
              && entry.path("tenantId").asText().equals(tenant);
        }
        require(enrolled);
      } catch (RuntimeException failure) { throw rejected(); }
    }

    /** Verified exact reviewer can view only this pinned pending operation; no JDBC mutation. */
    public Preview preview(byte[] bearer) {
      try {
        String authorization = authorization(bearer);
        config.authority(java.time.Clock.systemUTC()).reviewer(authorization);
        JsonNode current = new JdbcGitHubOperationInspector(config.operations().source(),
            config.authorityProfile()).inspect(config.scope(), operationId).operation();
        require(snapshotHash.equals(CanonicalJson.sha256(current))
            && "awaiting_approval".equals(current.path("authorization").path("status").asText())
            && "not_started".equals(current.path("execution").path("status").asText()));
        var display = OrganizationOperatorProtocol.JSON.createObjectNode()
            .put("operationId", operationId).put("decision", decision)
            .put("snapshotHash", snapshotHash)
            .put("authorityProfileFingerprint", config.authorityProfile().fingerprint())
            .put("expectedProfileFingerprint", config.expectedProfile().fingerprint());
        display.set("canonicalAction", current.path("canonicalAction").deepCopy());
        return new Preview(display.toString(), decision + " " + operationId + " " + snapshotHash);
      } catch (RuntimeException failure) { throw rejected(); }
    }

    /** One attempt only. Reverify bearer and exact snapshot; reuse the existing locked transaction. */
    public Result review(byte[] bearer) {
      if (!attempted.compareAndSet(false, true)) return failure("REJECTED");
      try {
        var receipt = new OrganizationOperatorRuntime(config)
            .reviewPinned(authorization(bearer), operationId, decision, snapshotHash);
        return new Result(receipt);
      } catch (Auth0OperationAuthorityException | BusinessOperationTransitionException
          | OrganizationFailure failure) {
        return failure("REJECTED");
      } catch (BusinessOperationStorageException failure) {
        return failure(switch (failure.code()) {
          case OPERATION_COMMIT_UNKNOWN, OPERATION_STORAGE_UNAVAILABLE,
              OPERATION_DELIVERY_RESULT_UNKNOWN -> "UNKNOWN";
          case OPERATION_SCHEMA_NOT_READY, OPERATION_AUTHORITY_NOT_READY,
              OPERATION_DATABASE_UNSUPPORTED, OPERATION_WRITER_REQUIRED -> "NOT_READY";
          default -> "REJECTED";
        });
      } catch (RuntimeException failure) { return failure("UNKNOWN"); }
    }

    private Result failure(String outcome) {
      return new Result(OrganizationOperatorReceipt.failure("review", outcome));
    }

    @Override public String toString() { return "Auth0OrganizationReview.Plan[redacted]"; }
  }

  public static final class Preview {
    private final String display;
    private final String confirmation;
    private Preview(String display, String confirmation) {
      require(display.length() <= 65_536);
      this.display = display; this.confirmation = confirmation;
    }
    /** JSON-escaped controls and escaped non-ASCII, intended only for the controlling terminal. */
    public String display() {
      StringBuilder safe = new StringBuilder();
      for (char c : display.toCharArray()) {
        if (c >= 127) safe.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int)c));
        else safe.append(c);
      }
      require(safe.length() <= 393_216);
      return safe.toString();
    }
    public String confirmation() { return confirmation; }
    public boolean confirms(String answer) { return confirmation.equals(answer); }
    @Override public String toString() { return "Auth0OrganizationReview.Preview[private display]"; }
  }

  public static final class Result {
    private final OrganizationOperatorReceipt receipt;
    private Result(OrganizationOperatorReceipt receipt) { this.receipt = receipt; }
    public int exitCode() { return receipt.exitCode(); }
    public byte[] bytes() { return receipt.bytes(); }
    @Override public String toString() { return "Auth0OrganizationReview.Result[redacted]"; }
  }

  private static String authorization(byte[] bearer) {
    require(bearer != null && bearer.length > 0 && bearer.length <= 16_384);
    String token = new String(bearer, StandardCharsets.US_ASCII);
    require(token.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"));
    return "Bearer " + token;
  }
  private static String text(JsonNode n, String field, int max) {
    return OrganizationOperatorProtocol.text(n, field, max);
  }
  private static void require(boolean value) { if (!value) throw rejected(); }
  private static IllegalArgumentException rejected() {
    return new IllegalArgumentException("AUTH0_REVIEW_REJECTED");
  }
}
