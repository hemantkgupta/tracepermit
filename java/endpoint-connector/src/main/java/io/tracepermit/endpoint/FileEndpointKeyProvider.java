package io.tracepermit.endpoint;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

public final class FileEndpointKeyProvider implements EndpointKeyProvider {
  private static final int MAX_KEY_BYTES = 16 * 1024;
  private static final Set<PosixFilePermission> ALLOWED_PERMISSIONS =
      EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

  private final KeyMetadata metadata;
  private final PrivateKey privateKey;

  public FileEndpointKeyProvider(KeyMetadata metadata, Path keyPath) {
    this.metadata = Objects.requireNonNull(metadata);
    this.privateKey = loadPrivateKey(Objects.requireNonNull(keyPath));
  }

  @Override
  public KeyMetadata metadata() {
    return metadata;
  }

  @Override
  public byte[] sign(byte[] signingInput) {
    Objects.requireNonNull(signingInput);
    try {
      Signature signer = Signature.getInstance("Ed25519");
      signer.initSign(privateKey);
      signer.update(signingInput);
      return signer.sign();
    } catch (GeneralSecurityException exception) {
      throw new ConnectorException(
          "SOURCE_SIGNING_UNAVAILABLE",
          "The endpoint signing operation is unavailable.",
          exception);
    }
  }

  private static PrivateKey loadPrivateKey(Path configuredPath) {
    Path path = configuredPath.toAbsolutePath().normalize();
    try {
      if (Files.isSymbolicLink(path)
          || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
        throw unavailable(null);
      }
      Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
      if (!permissions.contains(PosixFilePermission.OWNER_READ)
          || !ALLOWED_PERMISSIONS.containsAll(permissions)) {
        throw unavailable(null);
      }
      byte[] encoded;
      try (InputStream input = Files.newInputStream(path)) {
        encoded = input.readNBytes(MAX_KEY_BYTES + 1);
      }
      if (encoded.length == 0 || encoded.length > MAX_KEY_BYTES) {
        throw unavailable(null);
      }
      try {
        PrivateKey key = KeyFactory.getInstance("Ed25519")
            .generatePrivate(new PKCS8EncodedKeySpec(encoded));
        if (!"EdDSA".equals(key.getAlgorithm())) throw unavailable(null);
        return key;
      } finally {
        java.util.Arrays.fill(encoded, (byte) 0);
      }
    } catch (ConnectorException exception) {
      throw exception;
    } catch (IOException | GeneralSecurityException | UnsupportedOperationException exception) {
      throw unavailable(exception);
    }
  }

  private static ConnectorException unavailable(Throwable cause) {
    return new ConnectorException(
        "ENDPOINT_KEY_UNAVAILABLE",
        "The endpoint signing key is unavailable or invalid.",
        cause);
  }
}
