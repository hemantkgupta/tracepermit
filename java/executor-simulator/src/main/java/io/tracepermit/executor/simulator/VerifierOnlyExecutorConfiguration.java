package io.tracepermit.executor.simulator;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tracepermit.contracts.CanonicalJson;
import org.postgresql.ds.PGSimpleDataSource;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Strict operator-owned trust and persistence configuration for the one-shot executor. */
final class VerifierOnlyExecutorConfiguration {
  private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private static final Set<String> FORBIDDEN_PASSWORDS = Set.of(
      "change-me-password", "changeme-password", "replace-with-database-password",
      "tracepermit-password", "default-password");

  private final PGSimpleDataSource database;
  private final String issuer;
  private final String audience;
  private final String keyId;
  private final PublicKey publicKey;
  private final List<ExecutorRoute> routes;

  private VerifierOnlyExecutorConfiguration(
      PGSimpleDataSource database,
      String issuer,
      String audience,
      String keyId,
      PublicKey publicKey,
      List<ExecutorRoute> routes) {
    this.database = database;
    this.issuer = issuer;
    this.audience = audience;
    this.keyId = keyId;
    this.publicKey = publicKey;
    this.routes = List.copyOf(routes);
  }

  static VerifierOnlyExecutorConfiguration load(Path path) {
    try {
      Path config = path.toAbsolutePath().normalize();
      JsonNode root = JSON.readTree(utf8(readOwnerFile(config, 65_536)));
      fields(root, Set.of("schemaVersion", "postgres", "permit", "adapter"));
      require("1.0".equals(text(root, "schemaVersion", 8)));

      JsonNode postgres = root.path("postgres");
      fields(postgres, Set.of("host", "port", "database", "user", "passwordFile"));
      require("127.0.0.1".equals(text(postgres, "host", 32)));
      PGSimpleDataSource database = new PGSimpleDataSource();
      database.setServerNames(new String[] {"127.0.0.1"});
      database.setPortNumbers(new int[] {integer(postgres, "port", 1, 65_535)});
      database.setDatabaseName(identifier(postgres, "database", 128));
      database.setCurrentSchema("public");
      database.setUser(identifier(postgres, "user", 128));
      String password = utf8(readOwnerFile(reference(config, postgres, "passwordFile"), 1024));
      if (password.endsWith("\n")) password = password.substring(0, password.length() - 1);
      require(password.length() >= 16
          && password.chars().noneMatch(Character::isISOControl)
          && !FORBIDDEN_PASSWORDS.contains(password.toLowerCase(java.util.Locale.ROOT)));
      database.setPassword(password);
      database.setConnectTimeout(3);
      database.setSocketTimeout(5);
      database.setSslMode("disable");

      JsonNode permit = root.path("permit");
      fields(permit, Set.of("issuer", "audience", "keyId", "publicKeyFile"));
      String issuer = trustedUri(permit, "issuer", true);
      String audience = trustedUri(permit, "audience", false);
      String keyId = identifier(permit, "keyId", 128);
      PublicKey publicKey = KeyFactory.getInstance("Ed25519").generatePublic(
          new X509EncodedKeySpec(readOwnerFile(reference(config, permit, "publicKeyFile"), 4096)));

      JsonNode adapter = root.path("adapter");
      fields(adapter, Set.of("routes"));
      JsonNode configuredRoutes = adapter.path("routes");
      require(configuredRoutes.isArray() && !configuredRoutes.isEmpty() && configuredRoutes.size() <= 16);
      List<ExecutorRoute> routes = new ArrayList<>();
      for (JsonNode route : configuredRoutes) {
        fields(route, Set.of("toolName", "targetSystem", "targetEnvironment", "targetAccountId"));
        ExecutorRoute parsed = new ExecutorRoute(
            text(route, "toolName", 128),
            text(route, "targetSystem", 64),
            text(route, "targetEnvironment", 16),
            text(route, "targetAccountId", 128));
        require(!routes.contains(parsed));
        routes.add(parsed);
      }
      return new VerifierOnlyExecutorConfiguration(
          database, issuer, audience, keyId, publicKey, routes);
    } catch (IOException | GeneralSecurityException | RuntimeException failure) {
      // Configuration and driver errors can contain paths or secret material. Never attach them.
      throw new IllegalArgumentException("INVALID_EXECUTOR_CONFIGURATION");
    }
  }

  private static byte[] readOwnerFile(Path path, int maximum) throws IOException {
    PosixFileAttributes attributes = Files.readAttributes(
        path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    var owner = path.getFileSystem().getUserPrincipalLookupService()
        .lookupPrincipalByName(System.getProperty("user.name"));
    require(attributes.isRegularFile()
        && attributes.owner().equals(owner)
        && attributes.permissions().contains(PosixFilePermission.OWNER_READ)
        && Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            .containsAll(attributes.permissions())
        && attributes.size() > 0
        && attributes.size() <= maximum);
    try (var channel = Files.newByteChannel(
        path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      ByteBuffer buffer = ByteBuffer.allocate(maximum + 1);
      while (buffer.hasRemaining() && channel.read(buffer) != -1) { /* bounded local file */ }
      require(buffer.position() > 0 && buffer.position() <= maximum);
      return java.util.Arrays.copyOf(buffer.array(), buffer.position());
    }
  }

  private static Path reference(Path config, JsonNode node, String field) {
    Path parent = config.getParent();
    Path candidate = parent.resolve(text(node, field, 1024)).normalize();
    require(candidate.startsWith(parent) && !candidate.equals(config));
    return candidate;
  }

  private static String utf8(byte[] value) throws IOException {
    return StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(value)).toString();
  }

  private static String trustedUri(JsonNode node, String field, boolean httpsOnly) {
    String value = text(node, field, 512);
    URI uri = URI.create(value);
    require(uri.isAbsolute()
        && uri.getUserInfo() == null
        && uri.getQuery() == null
        && uri.getFragment() == null
        && (!httpsOnly || ("https".equals(uri.getScheme()) && uri.getHost() != null)));
    return value;
  }

  private static void fields(JsonNode node, Set<String> expected) {
    require(node.isObject() && node.size() == expected.size());
    node.fieldNames().forEachRemaining(field -> require(expected.contains(field)));
  }

  private static String text(JsonNode node, String field, int maximum) {
    JsonNode value = node.path(field);
    require(value.isTextual()
        && !value.textValue().isBlank()
        && value.textValue().length() <= maximum
        && value.textValue().chars().noneMatch(Character::isISOControl));
    return value.textValue();
  }

  private static String identifier(JsonNode node, String field, int maximum) {
    String value = text(node, field, maximum);
    require(value.matches("^[A-Za-z0-9][A-Za-z0-9._-]*$"));
    return value;
  }

  private static int integer(JsonNode node, String field, int minimum, int maximum) {
    JsonNode value = node.path(field);
    require(value.isIntegralNumber()
        && value.canConvertToInt()
        && value.intValue() >= minimum
        && value.intValue() <= maximum);
    return value.intValue();
  }

  private static void require(boolean condition) {
    if (!condition) throw new IllegalArgumentException("INVALID_EXECUTOR_CONFIGURATION");
  }

  PGSimpleDataSource database() { return database; }
  String issuer() { return issuer; }
  String audience() { return audience; }
  String keyId() { return keyId; }
  PublicKey publicKey() { return publicKey; }
  List<ExecutorRoute> routes() { return routes; }
  String keyFingerprint() {
    return "sha256:" + java.util.HexFormat.of().formatHex(
        CanonicalJson.sha256Bytes(publicKey.getEncoded()));
  }
}
