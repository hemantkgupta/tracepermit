package io.tracepermit.adapters.remote.github;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tracepermit.adapters.remote.github.credentials.GitHubInstallationConfiguration;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Set;

/** Local operator pins, never verified identity, current policy or execution authority. */
final class PilotConfiguration {
  private static final Set<String> FIELDS = Set.of("schemaVersion", "appId", "installationId",
      "repositoryId", "ownerId", "repository", "permissions", "privateKeyFile", "head", "base",
      "headSha", "baseSha", "draft");
  private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4)
          .maxStringLength(4096).maxNumberLength(16).build()).build())
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private final GitHubInstallationConfiguration installation;
  private final GitHubPilotTargetBinding target;
  private final Path key;

  private PilotConfiguration(GitHubInstallationConfiguration installation, GitHubPilotTargetBinding target, Path key) {
    this.installation = installation; this.target = target; this.key = key;
  }

  static PilotConfiguration read(Path file) throws Exception {
    byte[] bytes = PilotPrivateFiles.read(file, 16_384);
    try {
      String decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
      JsonNode root = JSON.readTree(decoded);
      if (root == null || !root.isObject() || root.size() != FIELDS.size()) throw new IllegalArgumentException();
      var names = root.fieldNames();
      while (names.hasNext()) if (!FIELDS.contains(names.next())) throw new IllegalArgumentException();
      unicode(root);
      if (!"tracepermit-github-pilot-diagnostic-v1".equals(text(root, "schemaVersion"))
          || !root.path("draft").isBoolean() || !root.path("draft").booleanValue()) throw new IllegalArgumentException();
      JsonNode permissions = root.get("permissions");
      if (permissions == null || !permissions.isObject()) throw new IllegalArgumentException();
      var map = new HashMap<String, String>();
      var fields = permissions.fields();
      while (fields.hasNext()) {
        var entry = fields.next();
        if (!entry.getValue().isTextual()) throw new IllegalArgumentException();
        map.put(entry.getKey(), entry.getValue().textValue());
      }
      var installation = new GitHubInstallationConfiguration(id(root, "appId"), id(root, "installationId"),
          id(root, "repositoryId"), id(root, "ownerId"), text(root, "repository"), map);
      var target = new GitHubPilotTargetBinding(installation.installationId(), installation.repositoryId(),
          installation.ownerId(), installation.repository(), text(root, "head"), text(root, "base"),
          text(root, "headSha"), text(root, "baseSha"));
      Path key = Path.of(text(root, "privateKeyFile"));
      // Structural inspection only: no signature, provider construction, DNS or transport.
      byte[] encoded = PilotPrivateFiles.read(key, 16_384);
      try {
        var rsa = (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
        if (rsa.getModulus().bitLength() < 2048 || rsa.getModulus().bitLength() > 4096) throw new IllegalArgumentException();
      } finally { Arrays.fill(encoded, (byte) 0); }
      return new PilotConfiguration(installation, target, key);
    } finally { Arrays.fill(bytes, (byte) 0); }
  }

  private static long id(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) throw new IllegalArgumentException();
    return value.longValue();
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual()) throw new IllegalArgumentException();
    return value.textValue();
  }

  private static void unicode(JsonNode node) {
    if (node.isTextual()) {
      String value = node.textValue();
      for (int i = 0; i < value.length(); i++) {
        char c = value.charAt(i);
        if (Character.isHighSurrogate(c)) {
          if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) throw new IllegalArgumentException();
        } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException();
      }
    } else {
      for (JsonNode child : node) unicode(child);
    }
  }

  GitHubInstallationConfiguration installation() { return installation; }
  GitHubPilotTargetBinding target() { return target; }
  Path key() { return key; }
  @Override public String toString() { return "PilotConfiguration[redacted]"; }
}
