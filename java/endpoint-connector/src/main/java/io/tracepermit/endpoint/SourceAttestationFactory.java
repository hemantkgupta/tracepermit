package io.tracepermit.endpoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.identity.SourceAttestationSigner;
import io.tracepermit.identity.SourceAttestationVerifier;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

public final class SourceAttestationFactory {
  public static final String AUDIENCE = "tracepermit-gateway";
  public static final String REQUEST_BINDING_PROFILE = "tracepermit-mcp-method-params-v1";

  private final EndpointKeyProvider keyProvider;
  private final Clock clock;
  private final TokenIdGenerator tokenIds;
  private final Duration lifetime;

  public SourceAttestationFactory(
      EndpointKeyProvider keyProvider,
      Clock clock,
      TokenIdGenerator tokenIds,
      Duration lifetime) {
    this.keyProvider = Objects.requireNonNull(keyProvider);
    this.clock = Objects.requireNonNull(clock);
    this.tokenIds = Objects.requireNonNull(tokenIds);
    this.lifetime = Objects.requireNonNull(lifetime);
    if (lifetime.isZero()
        || lifetime.isNegative()
        || lifetime.compareTo(Duration.ofSeconds(60)) > 0) {
      throw new IllegalArgumentException("Source-attestation lifetime must be 1–60 seconds.");
    }
  }

  public String create(
      JsonNode request, String bearerToken, ProcessObservation observation) {
    Objects.requireNonNull(request);
    Objects.requireNonNull(observation);
    EndpointKeyProvider.KeyMetadata metadata = keyProvider.metadata();
    if (!MessageDigest.isEqual(
        metadata.deviceId().getBytes(StandardCharsets.UTF_8),
        observation.deviceId().getBytes(StandardCharsets.UTF_8))) {
      throw new ConnectorException(
          "ENDPOINT_DEVICE_MISMATCH",
          "Endpoint observation does not match the configured signing key.");
    }
    String tokenId = tokenIds.nextTokenId();
    try {
      UUID.fromString(tokenId);
    } catch (IllegalArgumentException exception) {
      throw new ConnectorException(
          "TOKEN_ID_UNAVAILABLE", "A valid source token ID could not be generated.", exception);
    }

    Instant issuedAt = clock.instant();
    ObjectNode claims = JsonSupport.MAPPER.createObjectNode();
    claims.put("version", "1.0");
    claims.put("type", "tracepermit-source-attestation+jws");
    claims.put("canonicalizationProfile", CanonicalJson.PROFILE);
    claims.put("requestBindingProfile", REQUEST_BINDING_PROFILE);
    claims.put("issuer", metadata.issuer());
    claims.put("keyId", metadata.keyId());
    claims.put("audience", AUDIENCE);
    claims.put("tokenId", tokenId);
    claims.put("issuedAt", issuedAt.toString());
    claims.put("expiresAt", issuedAt.plus(lifetime).toString());
    claims.put("connectorCredentialFingerprint", fingerprint(bearerToken));
    claims.put("requestHash", SourceAttestationVerifier.hashRequest(request));
    ObjectNode device = claims.putObject("device");
    device.put("deviceId", observation.deviceId());
    device.put("platform", observation.platform());
    device.put("assurance", observation.assurance());
    ObjectNode process = claims.putObject("process");
    process.put("executableName", observation.executableName());
    process.put("executableHash", observation.executableHash());
    process.put("parentExecutableName", observation.parentExecutableName());
    process.put("startedAt", observation.startedAt().toString());
    ObjectNode trigger = claims.putObject("triggerEvidence");
    trigger.put(
        "requestedMode", observation.terminalAttached() ? "interactive" : "unknown");
    trigger.put("terminalAttached", observation.terminalAttached());

    try {
      return SourceAttestationSigner.sign(claims, keyProvider::sign);
    } catch (ConnectorException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new ConnectorException(
          "SOURCE_SIGNING_UNAVAILABLE",
          "The source attestation could not be signed.",
          exception);
    }
  }

  public static String fingerprint(String bearerToken) {
    if (bearerToken == null
        || bearerToken.isBlank()
        || bearerToken.length() > 16 * 1024
        || bearerToken.indexOf('\r') >= 0
        || bearerToken.indexOf('\n') >= 0) {
      throw new ConnectorException(
          "CONNECTOR_CREDENTIAL_UNAVAILABLE",
          "The connector credential is unavailable.");
    }
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(bearerToken.getBytes(StandardCharsets.UTF_8));
      return "sha256:" + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable.", exception);
    }
  }
}
