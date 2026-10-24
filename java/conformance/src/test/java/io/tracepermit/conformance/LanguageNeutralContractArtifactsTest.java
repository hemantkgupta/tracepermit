package io.tracepermit.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.contracts.JsonSupport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

final class LanguageNeutralContractArtifactsTest {
  private static final Path ROOT = Path.of(System.getProperty("tracepermit.repositoryRoot"));
  private static final Set<String> EXPECTED_JSON =
      Set.of(
          "errors/error-catalog-v1.json",
          "json-schema/approval-decision-v1.schema.json",
          "json-schema/approval-decision-v2.schema.json",
          "json-schema/approval-request-v1.schema.json",
          "json-schema/approval-request-v2.schema.json",
          "json-schema/approval-review-export-v1.schema.json",
          "json-schema/approved-execution-permit-claims-v2.schema.json",
          "json-schema/audit-evidence-record-v1.schema.json",
          "json-schema/business-operation-v1.schema.json",
          "json-schema/delegation-credential-header-v1.schema.json",
          "json-schema/delegation-grant-v1.schema.json",
          "json-schema/evaluation-v1.schema.json",
          "json-schema/evidence-anchor-receipt-v1.schema.json",
          "json-schema/execution-attempt-v1.schema.json",
          "json-schema/execution-permit-claims-v1.schema.json",
          "json-schema/intercepted-action-v1.schema.json",
          "json-schema/oauth-protected-resource-metadata-v1.schema.json",
          "json-schema/policy-v1.schema.json",
          "json-schema/security-readiness-v1.schema.json",
          "json-schema/signed-delegation-grant-claims-v1.schema.json",
          "json-schema/source-attestation-claims-v1.schema.json",
          "json-schema/workload-identity-claims-v1.schema.json",
          "json-schema/workload-identity-header-v1.schema.json",
          "protocols/mcp-v1.json",
          "protocols/openapi-v1.json",
          "vectors/canonicalization-v1.json",
          "vectors/approval-review-export-v1.json",
          "vectors/business-operation-v1.json",
          "vectors/development-access-token-v1.json",
          "vectors/execution-permit-v1.json",
          "vectors/github-business-operation-binding-v1.json",
          "vectors/oauth-access-token-v1.json",
          "vectors/policy-v1.json",
          "vectors/scheduled-workload-delegation-v1.json",
          "vectors/source-attestation-v1.json",
          "vectors/validation-v1.json");

  @Test
  void canonicalArtifactInventoryIsCompleteStrictJsonAndSecretFree() throws IOException {
    Path contracts = ROOT.resolve("contracts");
    Set<String> actual = new TreeSet<>();
    try (var files = Files.walk(contracts)) {
      files.filter(path -> path.getFileName().toString().endsWith(".json"))
          .map(contracts::relativize)
          .map(Path::toString)
          .map(value -> value.replace('\\', '/'))
          .forEach(actual::add);
    }
    assertThat(actual).containsExactlyInAnyOrderElementsOf(EXPECTED_JSON);

    for (String relative : actual) {
      Path path = contracts.resolve(relative);
      String text = Files.readString(path, StandardCharsets.UTF_8);
      JsonNode parsed = JsonSupport.MAPPER.readTree(text);
      assertThat(parsed).as(relative).isNotNull();
      assertThat(text)
          .as(relative)
          .doesNotContain("BEGIN PRIVATE KEY", "BEGIN RSA PRIVATE KEY", "BEGIN OPENSSH PRIVATE KEY");
      if (relative.startsWith("json-schema/")) {
        assertThat(parsed.path("$schema").asText())
            .as(relative)
            .isEqualTo("https://json-schema.org/draft/2020-12/schema");
        assertThat(parsed.path("$id").asText())
            .as(relative)
            .startsWith("https://schemas.tracepermit.invalid/");
        assertThat(parsed.path("additionalProperties").asBoolean(true)).as(relative).isFalse();
      }
    }
  }
}
