package io.tracepermit.persistence.operations;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.JsonSupport;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Pure deadline arithmetic controls, separate from actual signed/locked three-engine tests. */
final class OperationDeliveryDeadlineTest {
  private static final Instant NOW = Instant.parse("2026-10-27T00:00:00Z");
  private static final List<String> HISTORY = List.of("evaluatorExpiresAt", "reviewerExpiresAt",
      "membershipExpiresAt", "ownerExpiresAt", "approvalDeadline");

  @ParameterizedTest
  @ValueSource(strings = {"permit", "admissionOwner", "freshOwner", "evaluatorExpiresAt",
      "reviewerExpiresAt", "membershipExpiresAt", "ownerExpiresAt", "approvalDeadline"})
  void everyOriginalAndFreshDeadlineCapsTheLeaseWithoutRenewal(String boundary) {
    for (int remaining : new int[] {7, 0, -1}) {
      ObjectNode admission = admission();
      ObjectNode caller = identity();
      String expiry = NOW.plusSeconds(remaining).toString();
      switch (boundary) {
        case "permit" -> ((ObjectNode) admission.path("claims")).put("expiresAt", expiry);
        case "admissionOwner" -> ((ObjectNode) admission.path("identity")).put("authorityExpiresAt", expiry);
        case "freshOwner" -> caller.put("authorityExpiresAt", expiry);
        default -> ((ObjectNode) admission.path("claims").path("identityBounds")).put(boundary, expiry);
      }
      Instant result = OperationDeliveryJournal.deadline(admission, caller, NOW);
      assertThat(result).isEqualTo(NOW.plusSeconds(remaining));
      assertThat(result.isAfter(NOW)).isEqualTo(remaining > 0);
    }
  }

  @Test void abundantOriginalAuthorityStillCannotExceedThirtySeconds() {
    assertThat(OperationDeliveryJournal.deadline(admission(), identity(), NOW)).isEqualTo(NOW.plusSeconds(30));
  }

  private static ObjectNode admission() {
    ObjectNode admission = JsonSupport.MAPPER.createObjectNode();
    ObjectNode claims = admission.putObject("claims").put("expiresAt", NOW.plusSeconds(90).toString());
    ObjectNode bounds = claims.putObject("identityBounds");
    HISTORY.forEach(name -> bounds.put(name, NOW.plusSeconds(90).toString()));
    admission.set("identity", identity());
    return admission;
  }

  private static ObjectNode identity() {
    return JsonSupport.MAPPER.createObjectNode().put("authorityExpiresAt", NOW.plusSeconds(90).toString());
  }
}
