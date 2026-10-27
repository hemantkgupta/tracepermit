package io.tracepermit.agentcore.lifecyclecli;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.application.BusinessOperationEvaluation;
import io.tracepermit.application.BusinessOperationExecution;
import io.tracepermit.application.BusinessOperationReview;
import io.tracepermit.application.BusinessOperationStorageException;
import io.tracepermit.application.BusinessOperationTransitionException;
import io.tracepermit.application.BusinessOperationTransitionPlanner;
import io.tracepermit.application.TrustedRequesterContext;
import io.tracepermit.persistence.operations.JdbcGitHubOperationAdmissionRepository;
import io.tracepermit.persistence.operations.JdbcGitHubOperationDeliveryRepository;
import io.tracepermit.persistence.operations.JdbcGitHubOperationInspector;
import io.tracepermit.persistence.operations.JdbcGitHubOperationPermitIssuer;
import io.tracepermit.persistence.operations.JdbcGitHubOperationReviewRepository;
import io.tracepermit.persistence.operations.SyntheticGitHubDeliveryTarget;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.EdECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.util.Arrays;
import java.util.Set;
import javax.sql.DataSource;

/** Closed binding from verified installed inputs to the exact durable synthetic lifecycle. */
final class LifecycleRuntime {
  private static final BusinessOperationTransitionPlanner PLANNER =
      new BusinessOperationTransitionPlanner();
  private static final byte[] KEY_PROOF =
      "tracepermit-operation-permit-key-proof-v1".getBytes(StandardCharsets.US_ASCII);
  private static final Set<BusinessOperationStorageException.Code> NOT_READY =
      Set.of(
          BusinessOperationStorageException.Code.OPERATION_SCHEMA_NOT_READY,
          BusinessOperationStorageException.Code.OPERATION_AUTHORITY_NOT_READY,
          BusinessOperationStorageException.Code.OPERATION_DATABASE_UNSUPPORTED,
          BusinessOperationStorageException.Code.OPERATION_WRITER_REQUIRED);
  private static final Set<BusinessOperationStorageException.Code> UNCERTAIN =
      Set.of(
          BusinessOperationStorageException.Code.OPERATION_COMMIT_UNKNOWN,
          BusinessOperationStorageException.Code.OPERATION_STORAGE_UNAVAILABLE,
          BusinessOperationStorageException.Code.OPERATION_DELIVERY_RESULT_UNKNOWN);

  private final LifecycleOperatorConfig configuration;
  private final DataSource source;
  private final LifecycleIdentityAdapter identities;

  LifecycleRuntime(LifecycleOperatorConfig configuration) {
    this.configuration = configuration;
    source = configuration.operations().source();
    identities = new LifecycleIdentityAdapter(configuration, Clock.systemUTC());
  }

  LifecycleReceipt execute(String command, LifecycleProtocol.Input input) {
    try {
      return switch (command) {
        case "inspect" -> inspect(command, input);
        case "evaluate" -> evaluate(command, input);
        case "review" -> review(command, input);
        case "deliver-synthetic" -> deliver(command, input);
        case "reconcile" -> reconcile(command, input);
        default -> LifecycleReceipt.failure(command, "USAGE");
      };
    } catch (BusinessOperationStorageException failure) {
      return LifecycleReceipt.failure(command, map(failure.code()));
    } catch (BusinessOperationTransitionException failure) {
      return LifecycleReceipt.failure(command, "REJECTED");
    } catch (LifecycleFailure failure) {
      return LifecycleReceipt.failure(command, failure.code);
    } catch (RuntimeException failure) {
      return LifecycleReceipt.failure(command, "UNKNOWN");
    }
  }

  private LifecycleReceipt inspect(String command, LifecycleProtocol.Input input) {
    identities.requester(input.bearer(), "operations.execute");
    var observed = inspector().inspect(configuration.scope(), input.command().operationId());
    return state(command, "OBSERVED", observed.operation(), observed.dispatchStarted());
  }

  private LifecycleReceipt evaluate(String command, LifecycleProtocol.Input input) {
    var evaluator = identities.evaluator(input.bearer());
    JsonNode current =
        inspector().inspect(configuration.scope(), input.command().operationId()).operation();
    var evaluation =
        new BusinessOperationEvaluation(
            configuration.scope(),
            input.command().operationId(),
            PLANNER.observe(configuration.scope(), current),
            evaluator);
    JsonNode result = reviews().evaluate(evaluation);
    return state(command, "EVALUATED", result, false);
  }

  private LifecycleReceipt review(String command, LifecycleProtocol.Input input) {
    LifecycleIdentityAdapter.ReviewAuthority authority = identities.reviewer(input.bearer());
    JsonNode current =
        inspector().inspect(configuration.scope(), input.command().operationId()).operation();
    var decision = BusinessOperationReview.Decision.valueOf(input.command().decision());
    var review =
        new BusinessOperationReview(
            configuration.scope(),
            input.command().operationId(),
            PLANNER.observe(configuration.scope(), current),
            authority.reviewer(),
            decision);
    JsonNode result = reviews().review(review, authority.membership());
    String outcome =
        decision == BusinessOperationReview.Decision.APPROVE
            ? "REVIEW_APPROVED"
            : "REVIEW_DENIED";
    return state(command, outcome, result, false);
  }

  private LifecycleReceipt deliver(String command, LifecycleProtocol.Input input) {
    TrustedRequesterContext owner = identities.requester(input.bearer(), "operations.execute");
    byte[] encoded = input.privateKey();
    PrivateKey privateKey;
    try {
      privateKey = privateKey(encoded);
    } finally {
      LifecycleProtocol.erase(encoded);
    }

    JsonNode approved =
        inspector().inspect(configuration.scope(), input.command().operationId()).operation();
    BusinessOperationExecution approvalOwner = execution(approved, owner);
    String permit =
        new JdbcGitHubOperationPermitIssuer(
                source, configuration.authority(), configuration.expected(), privateKey)
            .issue(approvalOwner)
            .serialized();
    JsonNode claimed =
        new JdbcGitHubOperationAdmissionRepository(
                source, configuration.authority(), configuration.expected())
            .admit(approvalOwner, permit);
    BusinessOperationExecution claimOwner = execution(claimed, owner);
    SyntheticGitHubDeliveryTarget.Outcome selected =
        SyntheticGitHubDeliveryTarget.Outcome.valueOf(input.command().syntheticOutcome());
    var target = new SyntheticGitHubDeliveryTarget(configuration.expected(), selected);
    var delivery =
        new JdbcGitHubOperationDeliveryRepository(
            source, configuration.authority(), configuration.expected(), target);
    var lease = delivery.claim(claimOwner, permit);
    String result = delivery.dispatch(lease, claimOwner, permit);
    String outcome =
        switch (result) {
          case "succeeded" -> "DELIVERY_SUCCEEDED";
          case "failed" -> "DELIVERY_REJECTED";
          case "unknown" -> "DELIVERY_UNKNOWN";
          default -> throw new LifecycleFailure("UNKNOWN");
        };
    return LifecycleReceipt.state(
        command,
        outcome,
        input.command().operationId(),
        claimed.path("authorization").path("status").asText(),
        result,
        true,
        configuration);
  }

  private LifecycleReceipt reconcile(String command, LifecycleProtocol.Input input) {
    identities.requester(input.bearer(), "operations.execute");
    var target =
        new SyntheticGitHubDeliveryTarget(
            configuration.expected(), SyntheticGitHubDeliveryTarget.Outcome.UNKNOWN);
    var result =
        new JdbcGitHubOperationDeliveryRepository(
                source, configuration.authority(), configuration.expected(), target)
            .reconcile(configuration.scope(), input.command().operationId());
    JsonNode current =
        inspector().inspect(configuration.scope(), input.command().operationId()).operation();
    return LifecycleReceipt.state(
        command,
        "RECONCILED",
        input.command().operationId(),
        current.path("authorization").path("status").asText(),
        result.durableState(),
        result.dispatchStarted(),
        configuration);
  }

  private BusinessOperationExecution execution(
      JsonNode snapshot, TrustedRequesterContext owner) {
    return new BusinessOperationExecution(
        configuration.scope(),
        snapshot.path("operationId").asText(),
        PLANNER.observe(configuration.scope(), snapshot),
        owner,
        configuration.scope().workspaceId());
  }

  private JdbcGitHubOperationInspector inspector() {
    return new JdbcGitHubOperationInspector(source, configuration.authority());
  }

  private JdbcGitHubOperationReviewRepository reviews() {
    return new JdbcGitHubOperationReviewRepository(
        source, configuration.authority(), configuration.expected());
  }

  private LifecycleReceipt state(
      String command, String outcome, JsonNode operation, boolean dispatchStarted) {
    return LifecycleReceipt.state(
        command,
        outcome,
        operation.path("operationId").asText(),
        operation.path("authorization").path("status").asText(),
        operation.path("execution").path("status").asText(),
        dispatchStarted,
        configuration);
  }

  private PrivateKey privateKey(byte[] encoded) {
    try {
      if (encoded == null) throw new IllegalArgumentException();
      PrivateKey candidate =
          KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(encoded));
      if (!(candidate instanceof EdECPrivateKey ed)
          || !"Ed25519".equals(ed.getParams().getName())
          || !Arrays.equals(encoded, candidate.getEncoded())) {
        throw new IllegalArgumentException();
      }
      Signature proof = Signature.getInstance("Ed25519");
      proof.initSign(candidate);
      proof.update(KEY_PROOF);
      byte[] signature = proof.sign();
      try {
        proof.initVerify(configuration.authority().admissionSettings().publicKey());
        proof.update(KEY_PROOF);
        if (!proof.verify(signature)) throw new IllegalArgumentException();
      } finally {
        Arrays.fill(signature, (byte) 0);
      }
      return candidate;
    } catch (RuntimeException failure) {
      throw new LifecycleFailure("REJECTED");
    } catch (Exception failure) {
      throw new LifecycleFailure("REJECTED");
    }
  }

  private static String map(BusinessOperationStorageException.Code code) {
    if (NOT_READY.contains(code)) return "NOT_READY";
    if (UNCERTAIN.contains(code)) return "UNKNOWN";
    return "REJECTED";
  }
}
