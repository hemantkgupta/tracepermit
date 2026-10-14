package io.tracepermit.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.identity.InMemoryCredentialRevocationChecker;
import io.tracepermit.identity.InMemoryWorkloadIdentityReplayStore;
import io.tracepermit.identity.ScheduledWorkloadVerifier;
import io.tracepermit.identity.StaticWorkloadTrustRegistry;
import io.tracepermit.identity.TrustedScheduledActionContext;
import io.tracepermit.identity.VerifiedScheduledWorkload;
import io.tracepermit.identity.WorkloadTrustRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

final class ScheduledWorkloadConformanceTest {
  private static final Path ROOT = Path.of(System.getProperty("tracepermit.repositoryRoot"));
  private static final JsonNode VECTOR =
      JsonSupport.read(ROOT.resolve("contracts/vectors/scheduled-workload-delegation-v1.json"));

  @Test
  void javaVerifiesTheWorkloadAndDelegationVector() {
    JsonNode claims = VECTOR.path("claims");
    JsonNode workload = claims.path("workload");
    JsonNode root = claims.path("rootDelegation");
    JsonNode child = claims.path("childDelegation");
    JsonNode publicKeys = VECTOR.path("publicKeysSpkiBase64");
    ScheduledWorkloadVerifier verifier =
        new ScheduledWorkloadVerifier(
            new StaticWorkloadTrustRegistry(
                List.of(
                    trusted(
                        workload,
                        workload.path("schedulerId").asText(),
                        publicKeys.path("scheduler").asText(),
                        WorkloadTrustRegistry.KeyUsage.WORKLOAD_IDENTITY),
                    trusted(
                        root,
                        root.path("actorId").asText(),
                        publicKeys.path("workload").asText(),
                        WorkloadTrustRegistry.KeyUsage.DELEGATION),
                    trusted(
                        child,
                        child.path("actorId").asText(),
                        publicKeys.path("agent").asText(),
                        WorkloadTrustRegistry.KeyUsage.DELEGATION))),
            new InMemoryCredentialRevocationChecker(),
            new InMemoryWorkloadIdentityReplayStore(),
            Clock.fixed(Instant.parse(VECTOR.path("verifiedAt").asText()), ZoneOffset.UTC));

    VerifiedScheduledWorkload verified =
        verifier.verify(
            VECTOR.path("expectedCompactCredentials").path("workload").asText(),
            List.of(
                VECTOR.path("expectedCompactCredentials").path("childDelegation").asText(),
                VECTOR.path("expectedCompactCredentials").path("rootDelegation").asText()),
            VECTOR.path("action"),
            new TrustedScheduledActionContext(
                workload.path("tenantId").asText(),
                workload.path("schedulerId").asText(),
                workload.path("scheduleId").asText(),
                workload.path("runId").asText(),
                workload.path("eventId").asText(),
                workload.path("traceId").asText(),
                workload.path("audience").asText(),
                child.path("scopes").get(0).asText(),
                child.path("resources").get(0).asText(),
                true,
                Instant.parse(VECTOR.path("verifiedAt").asText())));

    assertThat(verified.subjectId()).isEqualTo(workload.path("subjectId").asText());
    assertThat(verified.delegations())
        .extracting(VerifiedScheduledWorkload.VerifiedDelegationGrant::tokenId)
        .containsExactly(root.path("tokenId").asText(), child.path("tokenId").asText());
    assertThat(verified.toString())
        .doesNotContain(VECTOR.path("expectedCompactCredentials").path("workload").asText());
  }

  private static WorkloadTrustRegistry.TrustedWorkloadKey trusted(
      JsonNode claims,
      String principalId,
      String publicKey,
      WorkloadTrustRegistry.KeyUsage usage) {
    return new WorkloadTrustRegistry.TrustedWorkloadKey(
        claims.path("issuer").asText(),
        claims.path("keyId").asText(),
        claims.path("tenantId").asText(),
        principalId,
        Set.of(usage),
        WorkloadTrustRegistry.KeyState.ACTIVE,
        publicKey(publicKey));
  }

  private static PublicKey publicKey(String value) {
    try {
      return KeyFactory.getInstance("Ed25519")
          .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(value)));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
