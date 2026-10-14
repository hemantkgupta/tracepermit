package io.tracepermit.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.identity.AuthenticatedConnector;
import io.tracepermit.identity.BearerChallenge;
import io.tracepermit.identity.CachingOidcJwkResolver;
import io.tracepermit.identity.ConnectorOrigin;
import io.tracepermit.identity.ConnectorScope;
import io.tracepermit.identity.OAuthProtectedResourceMetadata;
import io.tracepermit.identity.OidcAccessTokenVerifier;
import io.tracepermit.identity.OidcProviderConfiguration;
import io.tracepermit.identity.StaticOidcProviderRegistry;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

final class OidcAuthenticationConformanceTest {
  private static final Path ROOT = Path.of(System.getProperty("tracepermit.repositoryRoot"));
  private static final JsonNode VECTOR =
      JsonSupport.read(ROOT.resolve("contracts/vectors/oauth-access-token-v1.json"));

  @Test
  void javaVerifierMatchesThePublicOnlyVector() {
    JsonNode providerVector = VECTOR.path("provider");
    OidcProviderConfiguration provider = provider(providerVector);
    Clock clock =
        Clock.fixed(Instant.parse(VECTOR.path("verifiedAt").asText()), ZoneOffset.UTC);
    OidcAccessTokenVerifier verifier =
        new OidcAccessTokenVerifier(
            new StaticOidcProviderRegistry(List.of(provider)),
            new CachingOidcJwkResolver(ignored -> VECTOR.path("publicJwks").toString()),
            clock);

    String token = VECTOR.path("expectedCompactAccessToken").asText();
    AuthenticatedConnector connector = verifier.authenticate("Bearer " + token);
    JsonNode expected = VECTOR.path("expectedIdentity");
    assertThat(connector.subjectId()).isEqualTo(expected.path("subjectId").asText());
    assertThat(connector.displayName()).isEqualTo(expected.path("displayName").asText());
    assertThat(connector.organizationId()).isEqualTo(expected.path("organizationId").asText());
    assertThat(connector.origin().value()).isEqualTo(expected.path("origin").asText());
    assertThat(connector.clientId()).isEqualTo(expected.path("clientId").asText());
    assertThat(connector.clientName()).isEqualTo(expected.path("clientName").asText());
    assertThat(connector.issuer()).isEqualTo(expected.path("issuer").asText());
    assertThat(connector.authenticationProfile())
        .isEqualTo(expected.path("authenticationProfile").asText());
    assertThat(connector.verificationKeyId())
        .isEqualTo(expected.path("verificationKeyId").asText());
    assertThat(connector.credentialFingerprint().value())
        .isEqualTo(expected.path("credentialFingerprint").asText());
    assertThat(connector.scopes())
        .extracting(ConnectorScope::value)
        .containsExactlyElementsOf(stringList(expected.path("scopes")));
    assertThat(connector.expiresAt())
        .isEqualTo(Instant.parse(expected.path("expiresAt").asText()));
    assertThat(connector.toString()).doesNotContain(token);
  }

  @Test
  void javaMetadataAndChallengesMatchThePortableContract() {
    OidcProviderConfiguration provider = provider(VECTOR.path("provider"));
    OAuthProtectedResourceMetadata metadata = OAuthProtectedResourceMetadata.from(provider);

    assertThat(metadata.toJson()).isEqualTo(VECTOR.path("protectedResourceMetadata"));
    List<ConnectorScope> scopes =
        stringList(VECTOR.path("protectedResourceMetadata").path("scopes_supported")).stream()
            .map(ConnectorScope::of)
            .toList();
    assertThat(BearerChallenge.missingToken(provider.resourceMetadataUri(), scopes))
        .isEqualTo(VECTOR.path("bearerChallenges").path("missing").asText());
    assertThat(BearerChallenge.invalidToken(provider.resourceMetadataUri(), scopes))
        .isEqualTo(VECTOR.path("bearerChallenges").path("invalid").asText());
  }

  private static OidcProviderConfiguration provider(JsonNode value) {
    Map<String, String> clientNames = stringMap(value.path("clientNames"));
    Map<String, String> tenantMappings = stringMap(value.path("tenantMappings"));
    return new OidcProviderConfiguration(
        URI.create(value.path("issuer").asText()),
        value.path("audience").asText(),
        URI.create(value.path("jwksUri").asText()),
        URI.create(value.path("resourceMetadataUri").asText()),
        stringList(VECTOR.path("protectedResourceMetadata").path("authorization_servers")).stream()
            .map(URI::create)
            .toList(),
        VECTOR.path("protectedResourceMetadata").path("resource_name").asText(),
        clientNames,
        value.path("tenantClaim").asText(),
        tenantMappings,
        Set.copyOf(
            stringList(VECTOR.path("protectedResourceMetadata").path("scopes_supported")).stream()
                .map(ConnectorScope::of)
                .toList()),
        ConnectorOrigin.CHATGPT_WEB,
        Duration.ofSeconds(value.path("maximumTokenLifetimeSeconds").asLong()),
        Duration.ofSeconds(value.path("jwksCacheLifetimeSeconds").asLong()),
        Duration.ofSeconds(value.path("forcedRefreshCooldownSeconds").asLong()));
  }

  private static List<String> stringList(JsonNode value) {
    return StreamSupport.stream(value.spliterator(), false).map(JsonNode::asText).toList();
  }

  private static Map<String, String> stringMap(JsonNode value) {
    Map<String, String> result = new LinkedHashMap<>();
    value.fields().forEachRemaining(entry -> result.put(entry.getKey(), entry.getValue().asText()));
    return Map.copyOf(result);
  }
}
