package io.tracepermit.endpoint;

public interface EndpointKeyProvider {
  KeyMetadata metadata();

  byte[] sign(byte[] signingInput);

  record KeyMetadata(String issuer, String keyId, String deviceId) {
    public KeyMetadata {
      issuer = bounded(issuer, "Issuer", 256);
      keyId = bounded(keyId, "Key ID", 128);
      deviceId = bounded(deviceId, "Device ID", 128);
    }

    private static String bounded(String value, String label, int maximum) {
      if (value == null || value.isBlank() || value.length() > maximum) {
        throw new IllegalArgumentException(label + " is invalid.");
      }
      return value;
    }
  }
}
