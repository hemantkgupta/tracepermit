package io.tracepermit.contracts.redaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class EvidenceRedactorTest {

  @Test
  @DisplayName("Default enterprise profile masks case-insensitive credential fields")
  void masksCaseInsensitiveCredentials() throws Exception {
    JsonNode input = JsonSupport.MAPPER.readTree("""
        {
          "username": "admin",
          "password": "super-secret-password-123",
          "PASSWORD": "another-password",
          "apiKey": "sk-live-999999999",
          "clientSecret": "sec-abc-123",
          "nested": {
            "token": "tok_live_12345",
            "safeField": "public_data"
          }
        }
        """);

    JsonNode redacted = EvidenceRedactor.enterpriseDefault().redact(input);

    assertThat(redacted.path("username").asText()).isEqualTo("admin");
    assertThat(redacted.path("password").asText()).isEqualTo("[REDACTED:credentials]");
    assertThat(redacted.path("PASSWORD").asText()).isEqualTo("[REDACTED:credentials]");
    assertThat(redacted.path("apiKey").asText()).isEqualTo("[REDACTED:credentials]");
    assertThat(redacted.path("clientSecret").asText()).isEqualTo("[REDACTED:credentials]");
    assertThat(redacted.path("nested").path("token").asText()).isEqualTo("[REDACTED:credentials]");
    assertThat(redacted.path("nested").path("safeField").asText()).isEqualTo("public_data");

    // Immutability: original remains unaltered
    assertThat(input.path("password").asText()).isEqualTo("super-secret-password-123");
  }

  @Test
  @DisplayName("Default enterprise profile masks sensitive value patterns regardless of field name")
  void masksSensitivePatterns() throws Exception {
    JsonNode input = JsonSupport.MAPPER.readTree("""
        {
          "header": "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abc",
          "jwt": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0In0.xyz",
          "userSsn": "123-45-6789",
          "card": "4111-2222-3333-4444",
          "certKey": "-----BEGIN RSA PRIVATE KEY-----\\nMIIE...\\n-----END RSA PRIVATE KEY-----",
          "regularText": "Safe string value"
        }
        """);

    JsonNode redacted = EvidenceRedactor.enterpriseDefault().redact(input);

    assertThat(redacted.path("header").asText()).isEqualTo("[REDACTED:bearer_token]");
    assertThat(redacted.path("jwt").asText()).isEqualTo("[REDACTED:jwt_token]");
    assertThat(redacted.path("userSsn").asText()).isEqualTo("[REDACTED:ssn]");
    assertThat(redacted.path("card").asText()).isEqualTo("[REDACTED:credit_card]");
    assertThat(redacted.path("certKey").asText()).isEqualTo("[REDACTED:private_key]");
    assertThat(redacted.path("regularText").asText()).isEqualTo("Safe string value");
  }

  @Test
  @DisplayName("Irreversible hash strategy produces deterministic SHA-256 fingerprints (DATA-003)")
  void irreversibleHashStrategy() throws Exception {
    JsonNode doc1 = JsonSupport.MAPPER.readTree("""
        {
          "password": "exact-secret-value",
          "apiKey": "exact-api-key"
        }
        """);
    JsonNode doc2 = JsonSupport.MAPPER.readTree("""
        {
          "password": "exact-secret-value",
          "apiKey": "different-api-key"
        }
        """);

    EvidenceRedactor redactor = EvidenceRedactor.irreversibleHash();
    JsonNode result1 = redactor.redact(doc1);
    JsonNode result2 = redactor.redact(doc2);

    // Hashes must begin with sha256: and have 64 hex characters
    assertThat(result1.path("password").asText()).matches("^sha256:[a-f0-9]{64}$");
    assertThat(result1.path("apiKey").asText()).matches("^sha256:[a-f0-9]{64}$");

    // Identical secret produces identical hash (preserving audit equality check)
    assertThat(result1.path("password").asText()).isEqualTo(result2.path("password").asText());

    // Different secret produces different hash
    assertThat(result1.path("apiKey").asText()).isNotEqualTo(result2.path("apiKey").asText());

    // Never contains plaintext
    assertThat(result1.toString()).doesNotContain("exact-secret-value", "exact-api-key");
  }

  @Test
  @DisplayName("Strict drop strategy removes matched fields completely")
  void strictDropStrategy() throws Exception {
    JsonNode input = JsonSupport.MAPPER.readTree("""
        {
          "id": "1001",
          "password": "secret-to-drop",
          "token": "tok-to-drop",
          "safe": "visible"
        }
        """);

    JsonNode result = EvidenceRedactor.strictDrop().redact(input);

    assertThat(result.has("id")).isTrue();
    assertThat(result.has("safe")).isTrue();
    assertThat(result.has("password")).isFalse();
    assertThat(result.has("token")).isFalse();
  }

  @Test
  @DisplayName("redactActionArguments targets action.arguments while preserving canonical action envelope")
  void redactActionArgumentsPreservesEnvelope() throws Exception {
    JsonNode canonical = JsonSupport.MAPPER.readTree("""
        {
          "eventId": "018f47a2-f5ce-7a4b-8f54-80e5d3eaf091",
          "traceId": "8f3a2c1e4b5d67890123456789abcdef",
          "identity": {
            "subject": { "id": "user:alice@example.com", "type": "human" }
          },
          "action": {
            "toolName": "aws.ec2.update_security_group",
            "arguments": {
              "securityGroupId": "sg-12345",
              "password": "db-cluster-admin-secret",
              "apiToken": "tok_prod_9999",
              "inboundPort": 443
            }
          }
        }
        """);

    String preRedactionHash = CanonicalJson.sha256(canonical);

    JsonNode redacted = EvidenceRedactor.enterpriseDefault().redactActionArguments(canonical);

    // Envelope preserved
    assertThat(redacted.path("eventId").asText()).isEqualTo("018f47a2-f5ce-7a4b-8f54-80e5d3eaf091");
    assertThat(redacted.path("traceId").asText()).isEqualTo("8f3a2c1e4b5d67890123456789abcdef");
    assertThat(redacted.path("action").path("toolName").asText()).isEqualTo("aws.ec2.update_security_group");
    assertThat(redacted.path("action").path("arguments").path("securityGroupId").asText()).isEqualTo("sg-12345");
    assertThat(redacted.path("action").path("arguments").path("inboundPort").asInt()).isEqualTo(443);

    // Sensitive arguments redacted
    assertThat(redacted.path("action").path("arguments").path("password").asText()).isEqualTo("[REDACTED:credentials]");
    assertThat(redacted.path("action").path("arguments").path("apiToken").asText()).isEqualTo("[REDACTED:credentials]");

    // Original hash remains verifiable against pre-redacted canonical action
    assertThat(CanonicalJson.sha256(canonical)).isEqualTo(preRedactionHash);
  }

  @Test
  @DisplayName("Path-based rules match exact field hierarchies")
  void pathBasedMatching() throws Exception {
    RedactionRule customRule = RedactionRule.forFieldPaths(
        "payment_token",
        RedactionStrategy.MASK,
        "payment.details.vaultToken",
        "/customer/billing/cvv");

    RedactionProfile profile = new RedactionProfile("custom-path-profile", List.of(customRule), 32);
    EvidenceRedactor redactor = new EvidenceRedactor(profile);

    JsonNode input = JsonSupport.MAPPER.readTree("""
        {
          "payment": {
            "details": {
              "vaultToken": "vault_987654321",
              "currency": "USD"
            }
          },
          "customer": {
            "billing": {
              "cvv": "999",
              "zip": "94105"
            }
          },
          "other": {
            "vaultToken": "safe_in_other_path"
          }
        }
        """);

    JsonNode result = redactor.redact(input);

    assertThat(result.path("payment").path("details").path("vaultToken").asText())
        .isEqualTo("[REDACTED:payment_token]");
    assertThat(result.path("payment").path("details").path("currency").asText())
        .isEqualTo("USD");

    assertThat(result.path("customer").path("billing").path("cvv").asText())
        .isEqualTo("[REDACTED:payment_token]");
    assertThat(result.path("customer").path("billing").path("zip").asText())
        .isEqualTo("94105");

    // Path mismatch in 'other' is not redacted
    assertThat(result.path("other").path("vaultToken").asText())
        .isEqualTo("safe_in_other_path");
  }

  @Test
  @DisplayName("Redacts sensitive items inside arrays and nested structures")
  void handlesArraysAndNestedStructures() throws Exception {
    JsonNode input = JsonSupport.MAPPER.readTree("""
        {
          "users": [
            { "name": "Alice", "password": "pass-alice-123" },
            { "name": "Bob", "tokens": ["tok-1", "tok-2", "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig"] }
          ]
        }
        """);

    JsonNode result = EvidenceRedactor.enterpriseDefault().redact(input);

    assertThat(result.path("users").get(0).path("password").asText()).isEqualTo("[REDACTED:credentials]");
    assertThat(result.path("users").get(1).path("tokens").get(2).asText()).isEqualTo("[REDACTED:bearer_token]");
  }

  @Test
  @DisplayName("redactText scrubs tokens and credentials from freeform error/log messages")
  void redactText() {
    EvidenceRedactor redactor = EvidenceRedactor.enterpriseDefault();

    String errorMsg = "HTTP 401 Unauthorized for Bearer secret-auth-token-1234567890; SSN 123-45-6789 reported";
    String sanitized = redactor.redactText(errorMsg);

    assertThat(sanitized).doesNotContain("secret-auth-token-1234567890");
    assertThat(sanitized).doesNotContain("123-45-6789");
    assertThat(sanitized).contains("[REDACTED:bearer_token]");
    assertThat(sanitized).contains("[REDACTED:ssn]");
  }

  @Test
  @DisplayName("Deeply nested payloads exceeding max recursion depth fail closed")
  void exceedsMaxDepthFailsClosed() {
    EvidenceRedactor redactor = new EvidenceRedactor(
        new RedactionProfile("shallow", List.of(), 5));

    ObjectNode root = JsonSupport.MAPPER.createObjectNode();
    ObjectNode current = root;
    for (int i = 0; i < 10; i++) {
      ObjectNode child = JsonSupport.MAPPER.createObjectNode();
      current.set("next", child);
      current = child;
    }

    assertThatThrownBy(() -> redactor.redact(root))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("depth limit of 5");
  }

  @Test
  @DisplayName("Handles null and empty inputs safely")
  void handlesNullAndEmpty() {
    EvidenceRedactor redactor = EvidenceRedactor.enterpriseDefault();

    assertThat(redactor.redact(null).isNull()).isTrue();
    assertThat(redactor.redactText(null)).isNull();
    assertThat(redactor.redactText("")).isEqualTo("");
    assertThat(redactor.redactActionArguments(null).isNull()).isTrue();
  }
}
