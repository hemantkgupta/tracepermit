package io.tracepermit.adapters.remote.github.credentials;

import static io.tracepermit.adapters.remote.github.credentials.GitHubCredentialException.Code.*;
import static io.tracepermit.adapters.remote.github.credentials.GitHubCredentialException.reject;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tracepermit.adapters.remote.github.GitHubAppCredentialProvider;
import io.tracepermit.adapters.remote.github.GitHubAppCredentials;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

/** Unwired/no-cache operator credential foundation; no action, permit or target authority. */
public final class GitHubInstallationCredentialProvider implements GitHubAppCredentialProvider {
  private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private final GitHubInstallationConfiguration configuration;
  private final AppSigningKeySource keys;
  private final CredentialExchange.Factory exchanges;
  private final Clock clock;

  public static GitHubInstallationCredentialProvider production(GitHubInstallationConfiguration configuration, Path privateKeyFile) {
    if (configuration == null) throw reject(CONFIGURATION_REJECTED);
    return new GitHubInstallationCredentialProvider(configuration, new PrivateAppKeyFile(privateKeyFile),
        GitHubCredentialTransport.production(configuration.installationId()), Clock.systemUTC());
  }
  GitHubInstallationCredentialProvider(GitHubInstallationConfiguration configuration, AppSigningKeySource keys,
      CredentialExchange.Factory exchanges, Clock clock) {
    if (configuration == null || keys == null || exchanges == null || clock == null) throw reject(CONFIGURATION_REJECTED);
    this.configuration = configuration; this.keys = keys; this.exchanges = exchanges; this.clock = clock;
  }
  @Override public GitHubAppCredentials getInstallationCredentials(String repository) {
    if (!configuration.repository().equals(repository)) throw reject(REPOSITORY_REJECTED);
    var window = new TimeWindow();
    String jwt = sign(window.started);
    CredentialExchange exchange = null;
    GitHubAppCredentials result = null;
    GitHubCredentialException failure = null;
    try {
      checkJwtTime(window);
      exchange = Objects.requireNonNull(exchanges.open());
      checkJwtTime(window);
      verifyInstallation(parse(exchange.installation(jwt), 200));
      checkJwtTime(window);
      var restrictions = JSON.createObjectNode();
      restrictions.putArray("repository_ids").add(configuration.repositoryId());
      var permissions = restrictions.putObject("permissions");
      configuration.permissions().forEach(permissions::put);
      JsonNode token = parse(exchange.token(jwt, restrictions), 201);
      result = validateToken(token, window);
    } catch (Throwable problem) {
      if (problem instanceof InterruptedException) Thread.currentThread().interrupt();
      failure = problem instanceof GitHubCredentialException fixed ? fixed : reject(TRANSPORT_UNAVAILABLE);
    } finally {
      if (exchange != null) {
        try { exchange.close(); }
        catch (Throwable cleanupFailure) {
          if (cleanupFailure instanceof InterruptedException) Thread.currentThread().interrupt();
          failure = reject(CLEANUP_FAILED);
        }
      }
    }
    if (failure != null) throw failure;
    if (result == null || !result.expiresAt().isAfter(window.sample())) throw reject(TOKEN_EXPIRY_REJECTED);
    return result;
  }
  private Instant readClock(Instant previous) {
    try {
      Instant now = Objects.requireNonNull(clock.instant());
      if (now.getEpochSecond() < 60 || (previous != null && now.isBefore(previous))) throw new IllegalArgumentException();
      return now;
    } catch (Throwable problem) {
      if (problem instanceof InterruptedException) Thread.currentThread().interrupt();
      throw reject(CLOCK_REJECTED);
    }
  }
  private final class TimeWindow {
    private final Instant started = readClock(null);
    private Instant previous = started;
    Instant sample() { previous = readClock(previous); return previous; }
  }
  private void checkJwtTime(TimeWindow window) {
    if (window.sample().getEpochSecond() >= window.started.getEpochSecond() + 540) throw reject(CLOCK_REJECTED);
  }
  private String sign(Instant now) {
    java.security.PrivateKey key;
    try {
      key = keys.read();
      if (!(key instanceof RSAPrivateKey rsa) || rsa.getModulus().bitLength() < 2048 || rsa.getModulus().bitLength() > 4096) {
        throw reject(KEY_SOURCE_REJECTED);
      }
    } catch (Throwable problem) {
      if (problem instanceof InterruptedException) Thread.currentThread().interrupt();
      throw reject(KEY_SOURCE_REJECTED);
    }
    try {
      var encoder = Base64.getUrlEncoder().withoutPadding();
      byte[] header = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8);
      byte[] claims = JSON.writeValueAsBytes(JSON.createObjectNode().put("iss", Long.toString(configuration.appId()))
          .put("iat", now.getEpochSecond() - 60).put("exp", now.getEpochSecond() + 540));
      String input = encoder.encodeToString(header) + "." + encoder.encodeToString(claims);
      var signature = Signature.getInstance("SHA256withRSA"); signature.initSign(key);
      signature.update(input.getBytes(StandardCharsets.US_ASCII));
      return input + "." + encoder.encodeToString(signature.sign());
    } catch (Throwable problem) {
      if (problem instanceof InterruptedException) Thread.currentThread().interrupt();
      throw reject(SIGNING_REJECTED);
    }
  }
  private static JsonNode parse(CredentialExchange.Reply reply, int expected) {
    if (reply == null || reply.bytes() == null) throw reject(RESPONSE_INVALID);
    byte[] bytes = reply.bytes();
    try {
      if (reply.status() != expected || reply.paginated()) throw reject(RESPONSE_INVALID);
      if (bytes.length > 65_536) throw reject(RESPONSE_TOO_LARGE);
      JsonNode parsed = JSON.readTree(bytes);
      if (parsed == null || !parsed.isObject()) throw reject(RESPONSE_INVALID);
      return parsed;
    } catch (GitHubCredentialException fixed) { throw fixed; }
    catch (Throwable problem) { throw reject(RESPONSE_INVALID); }
    finally { Arrays.fill(bytes, (byte) 0); }
  }
  private void verifyInstallation(JsonNode node) {
    if (!id(node.get("id"), configuration.installationId()) || !id(node.get("app_id"), configuration.appId())
        || !id(node.get("target_id"), configuration.ownerId()) || !text(node.get("target_type"), "Organization")
        || !id(node.path("account").get("id"), configuration.ownerId())
        || !text(node.path("account").get("login"), configuration.repository().split("/")[0])
        || !text(node.path("account").get("type"), "Organization")
        || !(text(node.get("repository_selection"), "all") || text(node.get("repository_selection"), "selected"))
        || !node.has("suspended_at") || !node.path("suspended_at").isNull()
        || !permissions(node.get("permissions"))) throw reject(INSTALLATION_REJECTED);
  }
  private GitHubAppCredentials validateToken(JsonNode node, TimeWindow window) throws Exception {
    if (!text(node.get("repository_selection"), "selected") || !permissions(node.get("permissions"))) throw reject(TOKEN_ASSURANCE_REJECTED);
    var repositories = node.get("repositories");
    if (repositories == null || !repositories.isArray() || repositories.size() != 1) throw reject(TOKEN_ASSURANCE_REJECTED);
    var repo = repositories.get(0);
    if (!id(repo.get("id"), configuration.repositoryId()) || !text(repo.get("full_name"), configuration.repository())
        || !text(repo.get("name"), configuration.repository().split("/")[1])
        || !id(repo.path("owner").get("id"), configuration.ownerId())
        || !text(repo.path("owner").get("login"), configuration.repository().split("/")[0])
        || !bool(repo.get("private"), true) || !bool(repo.get("archived"), false)
        || !bool(repo.get("disabled"), false) || !bool(repo.get("fork"), false)) throw reject(TOKEN_ASSURANCE_REJECTED);
    var token = node.get("token"); var expiry = node.get("expires_at");
    if (token == null || !token.isTextual() || token.textValue().length() > 8192
        || !token.textValue().matches("[A-Za-z0-9_.-]+") || expiry == null || !expiry.isTextual()
        || expiry.textValue().length() > 40) throw reject(TOKEN_ASSURANCE_REJECTED);
    Instant expires;
    try { expires = Instant.parse(expiry.textValue()); }
    catch (Throwable problem) { throw reject(TOKEN_EXPIRY_REJECTED); }
    Instant now = window.sample();
    if (!expires.isAfter(now) || expires.isAfter(now.plus(Duration.ofHours(1)))) throw reject(TOKEN_EXPIRY_REJECTED);
    String fingerprint = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(token.textValue().getBytes(StandardCharsets.UTF_8)));
    return new GitHubAppCredentials(configuration.installationId(), fingerprint, token.textValue(), expires);
  }
  private boolean permissions(JsonNode node) {
    if (node == null || !node.isObject() || node.size() != configuration.permissions().size()) return false;
    return configuration.permissions().entrySet().stream().allMatch(entry -> text(node.get(entry.getKey()), entry.getValue()));
  }
  private static boolean id(JsonNode node, long expected) {
    return node != null && node.isIntegralNumber() && node.canConvertToLong() && node.longValue() == expected;
  }
  private static boolean text(JsonNode node, String expected) { return node != null && node.isTextual() && expected.equals(node.textValue()); }
  private static boolean bool(JsonNode node, boolean expected) { return node != null && node.isBoolean() && node.booleanValue() == expected; }
  @Override public String toString() { return "GitHubInstallationCredentialProvider[redacted]"; }
}
