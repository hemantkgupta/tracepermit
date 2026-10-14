package io.tracepermit.contracts;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

public final class CanonicalJson {
  public static final String PROFILE = "tracepermit-canonical-json-v1";

  private CanonicalJson() {}

  public static String canonicalize(JsonNode value) {
    return canonicalize(value, false);
  }

  /**
   * Exact-integer candidate for a future versioned profile. Signed v1 artifacts
   * must continue to use {@link #canonicalize(JsonNode)} and its frozen vectors.
   */
  public static String canonicalizeExactIntegers(JsonNode value) {
    return canonicalize(value, true);
  }

  private static String canonicalize(JsonNode value, boolean exactIntegers) {
    if (value == null || value.isNull()) return "null";
    if (value.isBoolean()) return value.toString();
    if (exactIntegers && value.isIntegralNumber()) {
      if (value.canConvertToLong()) return Long.toString(value.longValue());
      return value.bigIntegerValue().toString();
    }
    if (value.isNumber()) return canonicalNumber(value.doubleValue());
    if (value.isTextual()) return quote(value.textValue());
    if (value.isArray()) {
      List<String> items = new ArrayList<>();
      value.forEach(item -> items.add(canonicalize(item, exactIntegers)));
      return "[" + String.join(",", items) + "]";
    }
    if (value.isObject()) {
      List<String> names = new ArrayList<>();
      value.fieldNames().forEachRemaining(names::add);
      names.sort(Comparator.naturalOrder());
      List<String> entries = new ArrayList<>();
      for (String name : names) {
        entries.add(quote(name) + ":" + canonicalize(value.get(name), exactIntegers));
      }
      return "{" + String.join(",", entries) + "}";
    }
    throw new IllegalArgumentException("Unsupported JSON value");
  }

  public static String sha256(JsonNode value) {
    return "sha256:" + hex(digest(canonicalize(value).getBytes(StandardCharsets.UTF_8)));
  }

  public static String base64Url(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public static byte[] sha256Bytes(byte[] bytes) {
    return digest(bytes);
  }

  private static byte[] digest(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static String hex(byte[] bytes) {
    StringBuilder value = new StringBuilder(bytes.length * 2);
    for (byte item : bytes) value.append(String.format("%02x", item));
    return value.toString();
  }

  private static String quote(String value) {
    try {
      return JsonSupport.MAPPER.writeValueAsString(value);
    } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
      throw new IllegalArgumentException("Cannot encode JSON string", exception);
    }
  }

  private static String canonicalNumber(double value) {
    if (!Double.isFinite(value)) throw new IllegalArgumentException("Canonical JSON does not support non-finite numbers.");
    if (value == 0.0d) return "0";
    BigDecimal decimal = BigDecimal.valueOf(value).stripTrailingZeros();
    BigDecimal absolute = decimal.abs();
    if (absolute.compareTo(new BigDecimal("0.000001")) >= 0 && absolute.compareTo(new BigDecimal("1e21")) < 0) {
      return decimal.toPlainString();
    }
    String digits = decimal.unscaledValue().abs().toString();
    int exponent = digits.length() - 1 - decimal.scale();
    String coefficient = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
    return (decimal.signum() < 0 ? "-" : "") + coefficient + "e" + (exponent >= 0 ? "+" : "") + exponent;
  }
}
