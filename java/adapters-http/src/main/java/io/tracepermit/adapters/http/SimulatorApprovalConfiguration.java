package io.tracepermit.adapters.http;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tracepermit.adapters.remote.SsrfSafeHttpsJwkSetSource;
import io.tracepermit.execution.TenantPartition;
import io.tracepermit.identity.ConnectorOrigin;
import io.tracepermit.identity.ConnectorScope;
import io.tracepermit.identity.OidcProviderConfiguration;
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
import java.security.KeyPair;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.postgresql.ds.PGSimpleDataSource;

/** Explicit local operator configuration; intentionally has no secret-bearing toString/accessors. */
final class SimulatorApprovalConfiguration {
  private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private static final Set<ConnectorScope> SCOPES = Set.of(
      ConnectorScope.of("approvals.read"), ConnectorScope.of("approvals.request"),
      ConnectorScope.of("approvals.decide"), ConnectorScope.of("approvals.execute"),
      ConnectorScope.of("approvals.cancel"));
  private static final Set<String> FORBIDDEN_DEFAULT_SECRETS = Set.of(
      "change-me-password", "changeme-password", "replace-with-database-password",
      "replace-with-client-secret", "tracepermit-password", "default-password");
  private final int port;
  private final OidcProviderConfiguration provider;
  private final ApprovalMemberships memberships;
  private final PGSimpleDataSource database;
  private final KeyPair permitKey;
  private final String keyId;
  private final BrowserConfiguration browser;

  private SimulatorApprovalConfiguration(int port, OidcProviderConfiguration provider,
      ApprovalMemberships memberships, PGSimpleDataSource database, KeyPair permitKey, String keyId,
      BrowserConfiguration browser) {
    this.port = port;
    this.provider = provider;
    this.memberships = memberships;
    this.database = database;
    this.permitKey = permitKey;
    this.keyId = keyId;
    this.browser = browser;
  }

  static SimulatorApprovalConfiguration load(Path path) {
    try {
      Path config = path.toAbsolutePath().normalize();
      JsonNode root = JSON.readTree(utf8(readPrivateFile(config, 65_536)));
      fields(root, root.has("browser")
          ? Set.of("schemaVersion", "port", "postgres", "oidc", "memberships", "permit", "browser")
          : Set.of("schemaVersion", "port", "postgres", "oidc", "memberships", "permit"));
      String version = text(root, "schemaVersion", 8);
      require(Set.of("1.0", "1.1").contains(version));
      require(!"1.1".equals(version) || root.has("browser"));
      int port = integer(root, "port", 1, 65_535);
      JsonNode oidc = root.path("oidc");
      fields(oidc, Set.of("issuer", "audience", "jwksUri", "clientId", "tenantClaim", "tenantValue", "tenantId"));
      URI issuer = URI.create(text(oidc, "issuer", 512));
      String audience = text(oidc, "audience", 512);
      String clientId = tokenIdentifier(oidc, "clientId", false);
      TenantPartition tenant = new TenantPartition(identifier(oidc, "tenantId"));
      var provider = new OidcProviderConfiguration(issuer, audience,
          URI.create(text(oidc, "jwksUri", 768)), URI.create(audience), List.of(issuer),
          "TracePermit local simulator approvals", Map.of(clientId, "Approval API client"),
          text(oidc, "tenantClaim", 256), Map.of(tokenIdentifier(oidc, "tenantValue", false), tenant.value()),
          root.has("browser") ? java.util.stream.Stream.concat(SCOPES.stream(),
              java.util.stream.Stream.of(ConnectorScope.of("openid"))).collect(java.util.stream.Collectors.toUnmodifiableSet()) : SCOPES,
          ConnectorOrigin.CLI, Duration.ofMinutes(15), Duration.ofMinutes(1), Duration.ofSeconds(5));
      // Constructor validates the exact URL syntax without resolving DNS or opening a connection.
      new SsrfSafeHttpsJwkSetSource(Set.of(provider.jwksUri()), Duration.ofSeconds(3), Duration.ofSeconds(5));
      // This API has no OAuth discovery/browser flow. Metadata/origin fields above are verifier context only.
      var memberships = memberships(root.path("memberships"), issuer, clientId, tenant);
      JsonNode pg = root.path("postgres");
      fields(pg, Set.of("host", "port", "database", "user", "passwordFile"));
      require("127.0.0.1".equals(text(pg, "host", 32)));
      var database = new PGSimpleDataSource();
      database.setServerNames(new String[] {"127.0.0.1"});
      database.setPortNumbers(new int[] {integer(pg, "port", 1, 65_535)});
      database.setDatabaseName(identifier(pg, "database"));
      database.setCurrentSchema("public"); // Do not resolve application tables via a role-named schema.
      database.setUser(identifier(pg, "user"));
      String password = utf8(readPrivateFile(reference(config, pg, "passwordFile"), 1024));
      // A single terminal newline from an operator-created secret file is allowed; no trimming otherwise.
      if (password.endsWith("\n")) password = password.substring(0, password.length() - 1);
      require(operatorSecret(password));
      database.setPassword(password);
      database.setConnectTimeout(3);
      database.setSocketTimeout(5);
      database.setSslMode("disable"); // Numeric loopback only; not a remote database/TLS profile.
      JsonNode permit = root.path("permit");
      fields(permit, Set.of("keyId", "privateKeyFile", "publicKeyFile"));
      String keyId = identifier(permit, "keyId");
      KeyFactory keys = KeyFactory.getInstance("Ed25519");
      var privateKey = keys.generatePrivate(new PKCS8EncodedKeySpec(
          readPrivateFile(reference(config, permit, "privateKeyFile"), 4096)));
      var publicKey = keys.generatePublic(new X509EncodedKeySpec(
          readPrivateFile(reference(config, permit, "publicKeyFile"), 4096)));
      Signature proof = Signature.getInstance("Ed25519");
      byte[] challenge = "TracePermit operator key-pair validation v1".getBytes(StandardCharsets.UTF_8);
      proof.initSign(privateKey);
      proof.update(challenge);
      byte[] signature = proof.sign();
      proof.initVerify(publicKey);
      proof.update(challenge);
      require(proof.verify(signature));
      BrowserConfiguration browser = null;
      if (root.has("browser")) {
        JsonNode web = root.path("browser");
        String method = "1.0".equals(version) ? "client_secret_basic"
            : text(web, "tokenEndpointAuthMethod", 32);
        require(Set.of("none", "client_secret_basic").contains(method));
        Set<String> webFields = new HashSet<>(Set.of("authorizationUri", "tokenUri", "consoleDirectory"));
        if ("1.1".equals(version)) webFields.add("tokenEndpointAuthMethod");
        if ("client_secret_basic".equals(method)) webFields.add("clientSecretFile");
        fields(web, webFields);
        URI authorizationUri = URI.create(text(web, "authorizationUri", 768));
        URI tokenUri = URI.create(text(web, "tokenUri", 768));
        new SsrfSafeHttpsJwkSetSource(Set.of(authorizationUri, tokenUri),
            Duration.ofSeconds(3), Duration.ofSeconds(5));
        String secret = null;
        String organization = null;
        if ("client_secret_basic".equals(method)) {
          secret = utf8(readPrivateFile(reference(config, web, "clientSecretFile"), 4096));
          if (secret.endsWith("\n")) secret = secret.substring(0, secret.length() - 1);
          require(operatorSecret(secret));
        } else {
          // Narrow native Auth0 candidate: exact same-issuer endpoints and signed organization.
          require("org_id".equals(provider.tenantClaim()));
          organization = tokenIdentifier(oidc, "tenantValue", false);
          require(organization.matches("org_[A-Za-z0-9]{1,128}"));
          require(issuer.toString().equals("https://" + issuer.getRawAuthority() + "/"));
          require(authorizationUri.toString().equals(issuer + "authorize")
              && tokenUri.toString().equals(issuer + "oauth/token")
              && provider.jwksUri().toString().equals(issuer + ".well-known/jwks.json"));
        }
        Path console = reference(config, web, "consoleDirectory").toRealPath();
        require(Files.isDirectory(console) && Files.isRegularFile(console.resolve("index.html")));
        browser = new BrowserConfiguration(authorizationUri, tokenUri, secret, console, method, organization);
      }
      return new SimulatorApprovalConfiguration(port, provider, memberships, database,
          new KeyPair(publicKey, privateKey), keyId, browser);
    } catch (IOException | GeneralSecurityException | RuntimeException failure) {
      // Do not attach parser/driver exceptions: they may include subjects, paths, keys or secret contents.
      throw new IllegalArgumentException("INVALID_APPROVAL_CONFIGURATION");
    }
  }

  private static ApprovalMemberships memberships(JsonNode rows, URI issuer, String clientId,
      TenantPartition tenant) {
    require(rows.isArray() && !rows.isEmpty() && rows.size() <= 1000);
    List<ApprovalMemberships.Entry> entries = new ArrayList<>();
    for (JsonNode row : rows) {
      fields(row, Set.of("subject", "human", "roles"));
      require(row.path("human").isBoolean());
      JsonNode roles = row.path("roles");
      require(roles.isArray() && !roles.isEmpty() && roles.size() <= 3);
      Set<ApprovalMemberships.Role> parsed = new HashSet<>();
      for (JsonNode role : roles) {
        require(role.isTextual() && parsed.add(ApprovalMemberships.Role.valueOf(role.textValue())));
      }
      entries.add(new ApprovalMemberships.Entry(issuer + "#" + tokenIdentifier(row, "subject", true),
          clientId, tenant, row.path("human").booleanValue(), parsed));
    }
    require(entries.stream().anyMatch(entry -> entry.roles().contains(ApprovalMemberships.Role.REQUESTER)));
    require(entries.stream().anyMatch(reviewer -> reviewer.roles().contains(ApprovalMemberships.Role.REVIEWER)
        && entries.stream().anyMatch(requester -> requester.roles().contains(ApprovalMemberships.Role.REQUESTER)
            && !requester.principalId().equals(reviewer.principalId()))));
    return new ApprovalMemberships(entries);
  }

  private static byte[] readPrivateFile(Path path, int maximum) throws IOException {
    PosixFileAttributes attributes = Files.readAttributes(path, PosixFileAttributes.class,
        LinkOption.NOFOLLOW_LINKS);
    var owner = path.getFileSystem().getUserPrincipalLookupService()
        .lookupPrincipalByName(System.getProperty("user.name"));
    require(attributes.isRegularFile() && attributes.owner().equals(owner)
        && attributes.permissions().contains(PosixFilePermission.OWNER_READ)
        && Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            .containsAll(attributes.permissions())
        && attributes.size() > 0 && attributes.size() <= maximum);
    try (var channel = Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      ByteBuffer buffer = ByteBuffer.allocate(maximum + 1);
      while (buffer.hasRemaining() && channel.read(buffer) != -1) { /* bounded local file */ }
      require(buffer.position() > 0 && buffer.position() <= maximum);
      return java.util.Arrays.copyOf(buffer.array(), buffer.position());
    }
  }

  private static String utf8(byte[] value) throws IOException {
    return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value)).toString();
  }

  private static Path reference(Path config, JsonNode node, String field) {
    Path path = Path.of(text(node, field, 1024));
    return config.getParent().resolve(path).normalize();
  }

  private static void fields(JsonNode node, Set<String> expected) {
    require(node.isObject() && node.size() == expected.size());
    node.fieldNames().forEachRemaining(field -> require(expected.contains(field)));
  }

  private static String text(JsonNode node, String field, int maximum) {
    JsonNode value = node.path(field);
    require(value.isTextual());
    String result = value.textValue();
    require(!result.isBlank() && result.length() <= maximum
        && result.chars().noneMatch(Character::isISOControl));
    return result;
  }

  private static String identifier(JsonNode node, String field) {
    String value = text(node, field, 128);
    require(value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"));
    return value;
  }

  private static String tokenIdentifier(JsonNode node, String field, boolean subject) {
    String value = text(node, field, 256);
    require(value.matches(subject ? "[A-Za-z0-9][A-Za-z0-9._:@/+\\-=|]{0,255}"
        : "[A-Za-z0-9][A-Za-z0-9._:@/+\\-=]{0,255}"));
    return value;
  }

  private static boolean operatorSecret(String value) {
    return value.length() >= 16 && value.chars().noneMatch(Character::isISOControl)
        && !value.isBlank()
        && !FORBIDDEN_DEFAULT_SECRETS.contains(value.toLowerCase(java.util.Locale.ROOT));
  }

  private static int integer(JsonNode node, String field, int minimum, int maximum) {
    JsonNode value = node.path(field);
    require(value.isIntegralNumber() && value.canConvertToInt()
        && value.intValue() >= minimum && value.intValue() <= maximum);
    return value.intValue();
  }

  private static void require(boolean condition) {
    if (!condition) throw new IllegalArgumentException("INVALID_APPROVAL_CONFIGURATION");
  }

  int port() { return port; }
  OidcProviderConfiguration provider() { return provider; }
  ApprovalMemberships memberships() { return memberships; }
  PGSimpleDataSource database() { return database; }
  KeyPair permitKey() { return permitKey; }
  String keyId() { return keyId; }
  BrowserConfiguration browser() { return browser; }

  /** Not a record: never generate a client-secret-bearing toString. */
  static final class BrowserConfiguration {
    final URI authorizationUri;
    final URI tokenUri;
    final String clientSecret;
    final Path consoleDirectory;
    final String tokenEndpointAuthMethod;
    final String organization;
    BrowserConfiguration(URI authorizationUri, URI tokenUri, String clientSecret, Path consoleDirectory,
        String tokenEndpointAuthMethod, String organization) {
      this.authorizationUri = authorizationUri;
      this.tokenUri = tokenUri;
      this.clientSecret = clientSecret;
      this.consoleDirectory = consoleDirectory;
      this.tokenEndpointAuthMethod = tokenEndpointAuthMethod;
      this.organization = organization;
    }
  }
}
