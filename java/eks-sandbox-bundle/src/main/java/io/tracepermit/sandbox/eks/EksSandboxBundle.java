package io.tracepermit.sandbox.eks;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Offline, fixed P1 manifests. Configuration is operator input, not proof of deployed isolation. */
public final class EksSandboxBundle {
  public static final String PROFILE = "tracepermit-eks-synthetic-sandbox-v1";
  static final int CONFIG_BYTES = 65_536;
  static final int BUNDLE_BYTES = 262_144;
  static final Set<String> FIELDS = Set.of(
      "schemaVersion", "profile", "stage", "accountId", "region", "clusterName", "clusterArn",
      "kubernetesMinor", "compute", "architecture", "ipFamily", "cni", "cniVersion", "policyMode",
      "agentNamespace", "trustedNamespace", "agentNodePool", "trustedNodePool", "runtimeImage",
      "databaseImage", "databasePvc", "databasePvcUid", "sourceCommit", "archiveSha256", "rehearsalId", "command",
      "controlIPv4", "kubernetesApiIPv4", "protectedTargetIPv4");
  static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .streamReadConstraints(StreamReadConstraints.builder()
          .maxNestingDepth(32).maxStringLength(4_096).maxNumberLength(10).build()).build())
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private EksSandboxBundle() {}

  public record Configuration(Map<String, String> values) {
    public Configuration {
      values = Map.copyOf(values);
      validate(values);
    }

    String value(String key) { return values.get(key); }
  }

  public static Configuration parse(byte[] bytes) throws IOException {
    require(bytes.length > 0 && bytes.length <= CONFIG_BYTES);
    JsonNode root = JSON.readTree(bytes);
    require(root != null && root.isObject());
    var names = new HashSet<String>();
    root.fieldNames().forEachRemaining(names::add);
    require(names.equals(FIELDS));
    var values = new TreeMap<String, String>();
    for (String name : FIELDS) {
      require(root.get(name).isTextual());
      values.put(name, root.get(name).textValue());
    }
    return new Configuration(values);
  }

  private static void validate(Map<String, String> v) {
    require(v.keySet().equals(FIELDS));
    for (String value : v.values()) require(value != null && !value.isBlank() && value.length() <= 512);
    exact(v, "schemaVersion", "1.0");
    exact(v, "profile", PROFILE);
    exact(v, "stage", "P1_SYNTHETIC_ONLY");
    exact(v, "compute", "ec2-linux");
    require(Set.of("amd64", "arm64").contains(v.get("architecture")));
    exact(v, "ipFamily", "IPv4");
    exact(v, "cni", "amazon-vpc-cni");
    exact(v, "policyMode", "strict");
    pattern(v, "accountId", "[0-9]{12}");
    require(!v.get("accountId").equals("000000000000"));
    pattern(v, "region", "[a-z]{2}-[a-z]+-[1-9]");
    pattern(v, "clusterName", "[a-z][a-z0-9-]{2,49}");
    exact(v, "clusterArn", "arn:aws:eks:" + v.get("region") + ":" + v.get("accountId")
        + ":cluster/" + v.get("clusterName"));
    pattern(v, "kubernetesMinor", "1\\.(33|34|35|36)");
    pattern(v, "cniVersion", "v[0-9]+\\.[0-9]+\\.[0-9]+-eksbuild\\.[0-9]+");
    for (String field : Set.of("agentNamespace", "trustedNamespace")) {
      pattern(v, field, "tp-sbx-[a-z0-9](?:[a-z0-9-]{0,44}[a-z0-9])?");
    }
    for (String field : Set.of("agentNodePool", "trustedNodePool", "databasePvc", "rehearsalId")) {
      pattern(v, field, "[a-z0-9](?:[a-z0-9-]{0,40}[a-z0-9])?");
    }
    require(!v.get("agentNamespace").equals(v.get("trustedNamespace")));
    require(!v.get("agentNodePool").equals(v.get("trustedNodePool")));
    require(java.util.UUID.fromString(v.get("databasePvcUid")).toString().equals(v.get("databasePvcUid")));
    require(!v.get("databasePvcUid").equals("00000000-0000-0000-0000-000000000000"));
    for (String field : Set.of("runtimeImage", "databaseImage")) {
      pattern(v, field, "[a-z0-9]+(?:[._-][a-z0-9]+)*(?::[0-9]{1,5})?"
          + "(?:/[a-z0-9]+(?:[._-][a-z0-9]+)*)+(?::[A-Za-z0-9_.-]+)?@sha256:[0-9a-f]{64}");
      require(!v.get(field).endsWith("0".repeat(64)));
    }
    pattern(v, "sourceCommit", "[0-9a-f]{40}");
    pattern(v, "archiveSha256", "[0-9a-f]{64}");
    require(!v.get("sourceCommit").equals("0".repeat(40)));
    require(!v.get("archiveSha256").equals("0".repeat(64)));
    require(Set.of("check-config", "inspect", "evaluate", "review", "deliver-synthetic", "reconcile")
        .contains(v.get("command")));
    for (String field : Set.of("controlIPv4", "kubernetesApiIPv4", "protectedTargetIPv4")) {
      byte[] address = ipv4(v.get(field));
      int first = Byte.toUnsignedInt(address[0]);
      require(first > 0 && first != 127 && first < 224 && first != 169);
    }
    require(privateAddress(ipv4(v.get("controlIPv4"))));
    require(privateAddress(ipv4(v.get("kubernetesApiIPv4"))));
    require(Set.of(v.get("controlIPv4"), v.get("kubernetesApiIPv4"), v.get("protectedTargetIPv4")).size() == 3);
  }

  private static boolean privateAddress(byte[] ip) {
    int first = Byte.toUnsignedInt(ip[0]);
    int second = Byte.toUnsignedInt(ip[1]);
    return first == 10 || first == 172 && second >= 16 && second <= 31
        || first == 192 && second == 168;
  }

  static byte[] ipv4(String text) {
    String[] parts = text.split("\\.", -1);
    require(parts.length == 4);
    byte[] address = new byte[4];
    for (int i = 0; i < parts.length; i++) {
      require(parts[i].matches("0|[1-9][0-9]{0,2}"));
      int value = Integer.parseInt(parts[i]);
      require(value <= 255);
      address[i] = (byte) value;
    }
    return address;
  }

  public static JsonNode render(Configuration configuration) throws IOException {
    var values = new TreeMap<>(configuration.values());
    values.put("policyVersion", "v" + configuration.value("kubernetesMinor"));
    values.put("jobName", "tp-lifecycle-" + configuration.value("rehearsalId"));
    values.put("probeName", "tp-probe-" + configuration.value("rehearsalId"));
    values.put("configurationFingerprint", fingerprint(configuration));
    values.put("controlCidr", configuration.value("controlIPv4") + "/32");
    try (var stream = EksSandboxBundle.class.getResourceAsStream("/bundle.template.json")) {
      if (stream == null) throw new IOException("TEMPLATE_UNAVAILABLE");
      byte[] bytes = stream.readNBytes(BUNDLE_BYTES + 1);
      require(bytes.length <= BUNDLE_BYTES);
      return substitute(JSON.readTree(bytes), values);
    }
  }

  static JsonNode substitute(JsonNode node, Map<String, String> values) {
    if (node.isTextual() && node.textValue().startsWith("${")) {
      String text = node.textValue();
      require(text.endsWith("}") && values.containsKey(text.substring(2, text.length() - 1)));
      return JSON.getNodeFactory().textNode(values.get(text.substring(2, text.length() - 1)));
    }
    if (node.isObject()) {
      ObjectNode result = JSON.createObjectNode();
      node.fields().forEachRemaining(field -> result.set(field.getKey(), substitute(field.getValue(), values)));
      return result;
    }
    if (node.isArray()) {
      ArrayNode result = JSON.createArrayNode();
      node.forEach(child -> result.add(substitute(child, values)));
      return result;
    }
    return node.deepCopy();
  }

  public static void verify(Configuration configuration, byte[] bundle) throws IOException {
    require(bundle.length > 0 && bundle.length <= BUNDLE_BYTES);
    require(render(configuration).equals(JSON.readTree(bundle)));
  }

  public static String fingerprint(Configuration configuration) throws IOException {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(JSON.writeValueAsBytes(new TreeMap<>(configuration.values())));
      return java.util.HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA256_UNAVAILABLE", unavailable);
    }
  }

  private static void exact(Map<String, String> v, String key, String expected) {
    require(expected.equals(v.get(key)));
  }

  private static void pattern(Map<String, String> v, String key, String pattern) {
    require(v.get(key).matches(pattern));
  }

  static void require(boolean condition) {
    if (!condition) throw new IllegalArgumentException("SANDBOX_CONTRACT_INVALID");
  }
}
