package io.tracepermit.agentcore.organizationcli;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.adapters.remote.agentcore.AgentCoreRegistrationIngress;
import io.tracepermit.adapters.remote.agentcore.OktaOperationAuthority;
import io.tracepermit.adapters.remote.agentcore.OktaOperationAuthorityException;
import io.tracepermit.agentcore.registration.DurableAgentCoreRegistration;
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

/**
 * Closed binding from verified installed input to organization registration and synthetic
 * lifecycle.
 */
final class OrganizationOperatorRuntime {
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

  private final OrganizationOperatorConfig configuration;
  private final DataSource source;
  private final Clock clock;
  private final OktaOperationAuthority identities;

  OrganizationOperatorRuntime(OrganizationOperatorConfig configuration) {
    this.configuration = configuration;
    source = configuration.operations().source();
    clock = Clock.systemUTC();
    identities = configuration.authority(clock);
  }

  OrganizationOperatorReceipt execute(String command, OrganizationOperatorProtocol.Input input) {
    try {
      return switch (command) {
        case "register" -> register(input);
        case "inspect" -> inspect(command, input);
        case "evaluate" -> evaluate(command, input);
        case "review" -> review(command, input);
        case "deliver-synthetic" -> deliver(command, input);
        case "reconcile" -> reconcile(command, input);
        default -> OrganizationOperatorReceipt.failure(command, "USAGE");
      };
    } catch (AgentCoreRegistrationIngress.IngressException failure) {
      return OrganizationOperatorReceipt.failure(command, registrationFailure(failure.code()));
    } catch (DurableAgentCoreRegistration.InitializationException failure) {
      return OrganizationOperatorReceipt.failure(command, "NOT_READY");
    } catch (BusinessOperationStorageException failure) {
      return OrganizationOperatorReceipt.failure(command, storageFailure(failure.code()));
    } catch (BusinessOperationTransitionException | OktaOperationAuthorityException failure) {
      return OrganizationOperatorReceipt.failure(command, "REJECTED");
    } catch (OrganizationFailure failure) {
      return OrganizationOperatorReceipt.failure(command, failure.code);
    } catch (RuntimeException failure) {
      return OrganizationOperatorReceipt.failure(command, "UNKNOWN");
    }
  }

  private OrganizationOperatorReceipt register(OrganizationOperatorProtocol.Input input) {
    // Establish exact current requester authority before any store validation or JDBC access.
    identities.requester(input.authorization(), "operations.register");
    byte[] body = input.registration();
    try {
      var registration =
          DurableAgentCoreRegistration.organization(
              configuration.requesterVerifier(clock),
              configuration.registrationEnrollment(),
              clock,
              configuration.replay().source(),
              source,
              configuration.replayCapacity(),
              configuration.replayDmlBudget());
      AgentCoreRegistrationIngress.Receipt receipt =
          registration.register(input.authorization(), null, body);
      return OrganizationOperatorReceipt.registration(receipt.created(), receipt.operationId());
    } finally {
      OrganizationOperatorProtocol.erase(body);
    }
  }

  private OrganizationOperatorReceipt inspect(
      String command, OrganizationOperatorProtocol.Input input) {
    identities.requester(input.authorization(), "operations.execute");
    var observed = inspector().inspect(configuration.scope(), input.command().operationId());
    return state(command, "OBSERVED", observed.operation(), observed.dispatchStarted());
  }

  private OrganizationOperatorReceipt evaluate(
      String command, OrganizationOperatorProtocol.Input input) {
    var evaluator = identities.evaluator(input.authorization());
    JsonNode current =
        inspector().inspect(configuration.scope(), input.command().operationId()).operation();
    JsonNode result =
        reviews()
            .evaluate(
                new BusinessOperationEvaluation(
                    configuration.scope(),
                    input.command().operationId(),
                    PLANNER.observe(configuration.scope(), current),
                    evaluator));
    return state(command, "EVALUATED", result, false);
  }

  private OrganizationOperatorReceipt review(
      String command, OrganizationOperatorProtocol.Input input) {
    OktaOperationAuthority.ReviewAuthority authority =
        identities.reviewer(input.authorization());
    JsonNode current =
        inspector().inspect(configuration.scope(), input.command().operationId()).operation();
    var decision = BusinessOperationReview.Decision.valueOf(input.command().decision());
    JsonNode result =
        reviews()
            .review(
                new BusinessOperationReview(
                    configuration.scope(),
                    input.command().operationId(),
                    PLANNER.observe(configuration.scope(), current),
                    authority.reviewer(),
                    decision),
                authority.membership());
    return state(
        command,
        decision == BusinessOperationReview.Decision.APPROVE
            ? "REVIEW_APPROVED"
            : "REVIEW_DENIED",
        result,
        false);
  }

  private OrganizationOperatorReceipt deliver(
      String command, OrganizationOperatorProtocol.Input input) {
    TrustedRequesterContext owner =
        identities.requester(input.authorization(), "operations.execute");
    byte[] encoded = input.privateKey();
    PrivateKey privateKey;
    try {
      privateKey = privateKey(encoded);
    } finally {
      OrganizationOperatorProtocol.erase(encoded);
    }

    JsonNode approved =
        inspector().inspect(configuration.scope(), input.command().operationId()).operation();
    BusinessOperationExecution approvalOwner = execution(approved, owner);
    String permit =
        new JdbcGitHubOperationPermitIssuer(
                source,
                configuration.authorityProfile(),
                configuration.expectedProfile(),
                privateKey)
            .issue(approvalOwner)
            .serialized();
    JsonNode claimed =
        new JdbcGitHubOperationAdmissionRepository(
                source, configuration.authorityProfile(), configuration.expectedProfile())
            .admit(approvalOwner, permit);
    BusinessOperationExecution claimOwner = execution(claimed, owner);
    var target =
        new SyntheticGitHubDeliveryTarget(
            configuration.expectedProfile(),
            SyntheticGitHubDeliveryTarget.Outcome.valueOf(input.command().syntheticOutcome()));
    var delivery =
        new JdbcGitHubOperationDeliveryRepository(
            source, configuration.authorityProfile(), configuration.expectedProfile(), target);
    var lease = delivery.claim(claimOwner, permit);
    String result = delivery.dispatch(lease, claimOwner, permit);
    String outcome =
        switch (result) {
          case "succeeded" -> "DELIVERY_SUCCEEDED";
          case "failed" -> "DELIVERY_REJECTED";
          case "unknown" -> "DELIVERY_UNKNOWN";
          default -> throw new OrganizationFailure("UNKNOWN");
        };
    return OrganizationOperatorReceipt.state(
        command,
        outcome,
        input.command().operationId(),
        claimed.path("authorization").path("status").asText(),
        result,
        true,
        configuration);
  }

  private OrganizationOperatorReceipt reconcile(
      String command, OrganizationOperatorProtocol.Input input) {
    identities.requester(input.authorization(), "operations.execute");
    var target =
        new SyntheticGitHubDeliveryTarget(
            configuration.expectedProfile(), SyntheticGitHubDeliveryTarget.Outcome.UNKNOWN);
    var result =
        new JdbcGitHubOperationDeliveryRepository(
                source, configuration.authorityProfile(), configuration.expectedProfile(), target)
            .reconcile(configuration.scope(), input.command().operationId());
    JsonNode current =
        inspector().inspect(configuration.scope(), input.command().operationId()).operation();
    return OrganizationOperatorReceipt.state(
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
    return new JdbcGitHubOperationInspector(source, configuration.authorityProfile());
  }

  private JdbcGitHubOperationReviewRepository reviews() {
    return new JdbcGitHubOperationReviewRepository(
        source, configuration.authorityProfile(), configuration.expectedProfile());
  }

  private OrganizationOperatorReceipt state(
      String command, String outcome, JsonNode operation, boolean dispatchStarted) {
    return OrganizationOperatorReceipt.state(
        command,
        outcome,
        operation.path("operationId").asText(),
        operation.path("authorization").path("status").asText(),
        operation.path("execution").path("status").asText(),
        dispatchStarted,
        configuration);
  }

  PrivateKey privateKey(byte[] encoded) {
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
        proof.initVerify(configuration.authorityProfile().admissionSettings().publicKey());
        proof.update(KEY_PROOF);
        if (!proof.verify(signature)) throw new IllegalArgumentException();
      } finally {
        Arrays.fill(signature, (byte) 0);
      }
      return candidate;
    } catch (Exception failure) {
      throw new OrganizationFailure("REJECTED");
    }
  }

  private static String registrationFailure(String code) {
    return switch (code) {
      case "AGENTCORE_CONFIGURATION_INVALID", "AGENTCORE_STORAGE_UNAVAILABLE" -> "NOT_READY";
      case "AGENTCORE_REGISTRATION_UNKNOWN" -> "UNKNOWN";
      default -> "REJECTED";
    };
  }

  private static String storageFailure(BusinessOperationStorageException.Code code) {
    if (NOT_READY.contains(code)) return "NOT_READY";
    if (UNCERTAIN.contains(code)) return "UNKNOWN";
    return "REJECTED";
  }
}
