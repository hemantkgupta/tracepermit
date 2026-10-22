package io.tracepermit.adapters.http.requester;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Closed owner-only configuration for one installed requester public-client profile. */
final class InstalledRequesterConfiguration {
  static final String PROFILE = "installed-requester-public-sign-in";
  static final String CALLBACK_PATH = "/requester/callback";
  static final String QUALIFICATION_PATH = "/api/v1/requester-qualification";
  static final String MCP_PATH = "/mcp";
  static final String BEARER_ENVIRONMENT = "TRACEPERMIT_REQUESTER_BEARER";
  static final Set<ConnectorScope> API_SCOPES = Set.of(
      ConnectorScope.of("approvals.read"), ConnectorScope.of("approvals.request"),
      ConnectorScope.of("approvals.execute"));
  static final Set<ConnectorScope> ALLOWED_SCOPES = Set.of(
      ConnectorScope.of("openid"), ConnectorScope.of("approvals.read"),
      ConnectorScope.of("approvals.request"), ConnectorScope.of("approvals.execute"));
  static final String AUTHORIZATION_SCOPE =
      "openid approvals.read approvals.request approvals.execute";

  private static final Pattern TOKEN_IDENTIFIER =
      Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:@/+\\-=]{0,255}$");
  private static final Pattern SUBJECT_IDENTIFIER =
      Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:@/+\\-=|]{0,255}$");
  private static final Pattern ORGANIZATION = Pattern.compile("^org_[A-Za-z0-9]{1,128}$");
  private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private static final Set<PosixFilePermission> OWNER_FILE =
      Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
  private static final Set<PosixFilePermission> OWNER_DIRECTORY = Set.of(
      PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
      PosixFilePermission.OWNER_EXECUTE);

  private final URI issuer;
  private final URI authorizationUri;
  private final URI tokenUri;
  private final URI jwksUri;
  private final String audience;
  private final String clientId;
  private final String organization;
  private final String expectedSubject;
  private final URI callbackUri;
  private final URI qualificationUri;
  private final URI mcpUri;
  private final Path credentialFile;
  private final Path codexExecutable;

  private InstalledRequesterConfiguration(URI issuer, URI authorizationUri, URI tokenUri,
      URI jwksUri, String audience, String clientId, String organization,
      String expectedSubject, URI callbackUri, URI qualificationUri, URI mcpUri,
      Path credentialFile, Path codexExecutable) {
    this.issuer = issuer;
    this.authorizationUri = authorizationUri;
    this.tokenUri = tokenUri;
    this.jwksUri = jwksUri;
    this.audience = audience;
    this.clientId = clientId;
    this.organization = organization;
    this.expectedSubject = expectedSubject;
    this.callbackUri = callbackUri;
    this.qualificationUri = qualificationUri;
    this.mcpUri = mcpUri;
    this.credentialFile = credentialFile;
    this.codexExecutable = codexExecutable;
  }

  static InstalledRequesterConfiguration load(Path supplied) {
    try {
      Path config = requireAbsoluteNormalized(supplied);
      PosixFileAttributes attributes = privateAttributes(config, OWNER_FILE, true);
      require(attributes.size() > 0 && attributes.size() <= 65_536 && linkCount(config) == 1);
      JsonNode root = JSON.readTree(utf8(readBounded(config, 65_536)));
      fields(root, Set.of("schemaVersion", "profile", "oidc", "local"));
      require("1.0".equals(text(root, "schemaVersion", 8)));
      require(PROFILE.equals(text(root, "profile", 64)));

      JsonNode oidc = root.path("oidc");
      fields(oidc, Set.of("issuer", "authorizationUri", "tokenUri", "jwksUri",
          "audience", "clientId", "organization", "expectedSubject"));
      URI issuer = URI.create(text(oidc, "issuer", 512));
      URI authorization = URI.create(text(oidc, "authorizationUri", 768));
      URI token = URI.create(text(oidc, "tokenUri", 768));
      URI jwks = URI.create(text(oidc, "jwksUri", 768));
      requireHttps(issuer);
      require(issuer.toString().equals("https://" + issuer.getRawAuthority() + "/"));
      require(authorization.toString().equals(issuer + "authorize"));
      require(token.toString().equals(issuer + "oauth/token"));
      require(jwks.toString().equals(issuer + ".well-known/jwks.json"));
      String audience = text(oidc, "audience", 512);
      requireHttps(URI.create(audience));
      String clientId = identifier(oidc, "clientId", TOKEN_IDENTIFIER);
      String organization = identifier(oidc, "organization", ORGANIZATION);
      String expectedSubject = identifier(oidc, "expectedSubject", SUBJECT_IDENTIFIER);

      JsonNode local = root.path("local");
      fields(local, Set.of("callbackUri", "qualificationUri", "mcpUri",
          "credentialFile", "codexExecutable"));
      URI callback = URI.create(text(local, "callbackUri", 768));
      requireLoopback(callback, CALLBACK_PATH);
      URI qualification = URI.create(text(local, "qualificationUri", 768));
      requireLoopback(qualification, QUALIFICATION_PATH);
      URI mcp = URI.create(text(local, "mcpUri", 768));
      requireLoopback(mcp, MCP_PATH);
      require(origin(qualification).equals(origin(mcp)));

      Path credential = requireAbsoluteNormalized(Path.of(text(local, "credentialFile", 1024)));
      Path credentialDirectory = credential.getParent();
      require(credentialDirectory != null);
      privateAttributes(credentialDirectory, OWNER_DIRECTORY, false);
      require(credentialDirectory.toRealPath().equals(credentialDirectory));

      Path codex = requireAbsoluteNormalized(Path.of(text(local, "codexExecutable", 1024)));
      PosixFileAttributes codexAttributes = Files.readAttributes(
          codex, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      String currentOwner = currentOwner(codex).getName();
      require(codexAttributes.isRegularFile() && !Files.isSymbolicLink(codex)
          && (codexAttributes.owner().getName().equals(currentOwner)
              || "root".equals(codexAttributes.owner().getName()))
          && Files.isExecutable(codex) && linkCount(codex) >= 1);
      require(codex.toRealPath().equals(codex));

      // Constructor revalidates the verifier shape without discovery or network access.
      provider(issuer, audience, jwks, clientId, organization);
      return new InstalledRequesterConfiguration(issuer, authorization, token, jwks, audience,
          clientId, organization, expectedSubject, callback, qualification, mcp,
          credential, codex);
    } catch (IOException | RuntimeException failure) {
      throw new InstalledRequesterFailure("REQUESTER_CONFIGURATION_INVALID");
    }
  }

  OidcProviderConfiguration provider() {
    return provider(issuer, audience, jwksUri, clientId, organization);
  }

  private static OidcProviderConfiguration provider(URI issuer, String audience, URI jwks,
      String client, String organization) {
    return new OidcProviderConfiguration(issuer, audience, jwks, URI.create(audience),
        List.of(issuer), "TracePermit installed requester", Map.of(client, "Installed requester"),
        "org_id", Map.of(organization, organization), ALLOWED_SCOPES, ConnectorOrigin.CLI,
        Duration.ofMinutes(15), Duration.ofMinutes(1), Duration.ofSeconds(5));
  }

  URI issuer() { return issuer; }
  URI authorizationUri() { return authorizationUri; }
  URI tokenUri() { return tokenUri; }
  URI jwksUri() { return jwksUri; }
  String audience() { return audience; }
  String clientId() { return clientId; }
  String organization() { return organization; }
  String expectedSubject() { return expectedSubject; }
  URI callbackUri() { return callbackUri; }
  URI qualificationUri() { return qualificationUri; }
  URI mcpUri() { return mcpUri; }
  Path credentialFile() { return credentialFile; }
  Path codexExecutable() { return codexExecutable; }

  private static void requireLoopback(URI uri, String path) {
    require("http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost())
        && uri.getPort() >= 1 && uri.getPort() <= 65_535
        && path.equals(uri.getRawPath()) && uri.getRawQuery() == null
        && uri.getFragment() == null && uri.getUserInfo() == null
        && uri.toString().equals("http://127.0.0.1:" + uri.getPort() + path));
  }

  private static String origin(URI uri) {
    return uri.getScheme() + "://" + uri.getRawAuthority();
  }

  private static void requireHttps(URI uri) {
    require("https".equals(uri.getScheme()) && uri.getHost() != null && !uri.getHost().isBlank()
        && uri.getPort() != 0 && uri.getPort() <= 65_535 && uri.getUserInfo() == null
        && uri.getRawQuery() == null && uri.getFragment() == null);
  }

  private static Path requireAbsoluteNormalized(Path path) {
    require(path != null && path.isAbsolute() && path.normalize().equals(path));
    return path;
  }

  private static PosixFileAttributes privateAttributes(Path path,
      Set<PosixFilePermission> permissions, boolean regular) throws IOException {
    PosixFileAttributes attributes = Files.readAttributes(
        path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    require(!Files.isSymbolicLink(path) && attributes.permissions().equals(permissions)
        && attributes.owner().equals(currentOwner(path))
        && (regular ? attributes.isRegularFile() : attributes.isDirectory()));
    return attributes;
  }

  private static java.nio.file.attribute.UserPrincipal currentOwner(Path path) throws IOException {
    return path.getFileSystem().getUserPrincipalLookupService()
        .lookupPrincipalByName(System.getProperty("user.name"));
  }

  private static int linkCount(Path path) throws IOException {
    Object value = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
    require(value instanceof Number);
    return ((Number) value).intValue();
  }

  private static byte[] readBounded(Path path, int maximum) throws IOException {
    try (var channel = Files.newByteChannel(
        path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      ByteBuffer buffer = ByteBuffer.allocate(maximum + 1);
      while (buffer.hasRemaining() && channel.read(buffer) != -1) { /* bounded */ }
      require(buffer.position() > 0 && buffer.position() <= maximum);
      return java.util.Arrays.copyOf(buffer.array(), buffer.position());
    }
  }

  private static String utf8(byte[] value) throws IOException {
    return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value)).toString();
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

  private static String identifier(JsonNode node, String field, Pattern pattern) {
    String value = text(node, field, 256);
    require(pattern.matcher(value).matches());
    return value;
  }

  private static void require(boolean condition) {
    if (!condition) throw new IllegalArgumentException();
  }
}
