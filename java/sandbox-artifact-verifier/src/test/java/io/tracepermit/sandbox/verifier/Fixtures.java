package io.tracepermit.sandbox.verifier;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Synthetic fixtures only. */
final class Fixtures {
  static final String COMMIT = "a".repeat(40);
  static final String TREE = "b".repeat(40);
  private Fixtures() {}

  static byte[] header(String path, char type, long size) {
    byte[] block = new byte[512];
    put(block, 0, path);
    octal(block, 100, 8, 0644);
    octal(block, 108, 8, 0);
    octal(block, 116, 8, 0);
    octal(block, 124, 12, size);
    octal(block, 136, 12, 0);
    block[156] = (byte) type;
    put(block, 257, "ustar");
    put(block, 263, "00");
    checksum(block);
    return block;
  }

  static void put(byte[] block, int offset, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(bytes, 0, block, offset, bytes.length);
  }

  static void checksum(byte[] block) {
    for (int i = 148; i < 156; i++) block[i] = ' ';
    int sum = 0;
    for (byte value : block) sum += value & 0xff;
    put(block, 148, String.format(java.util.Locale.ROOT, "%06o", sum));
    block[154] = 0;
    block[155] = ' ';
  }

  private static void octal(byte[] block, int offset, int length, long number) {
    put(block, offset, String.format(java.util.Locale.ROOT, "%0" + (length - 1) + "o", number));
  }

  static byte[] archive(byte[]... headers) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    for (byte[] header : headers) bytes.writeBytes(header);
    bytes.writeBytes(new byte[1024]);
    return bytes.toByteArray();
  }

  static String digest(byte[] value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
  }

  static String evidence(String digest) {
    return """
        {"schemaVersion":"1",
         "candidate":{"commit":"%s","tree":"%s","archiveSha256":"%s"},
         "gates":[
          {"name":"focused","outcome":"PASS","freshness":"FRESH","executed":1,"failed":0,"skipped":0},
          {"name":"repository","outcome":"PASS","freshness":"FRESH","executed":1,"failed":0,"skipped":0},
          {"name":"format","outcome":"PASS","freshness":"FRESH","executed":0,"failed":0,"skipped":0},
          {"name":"docs-links","outcome":"PASS","freshness":"FRESH","executed":1,"failed":0,"skipped":0},
          {"name":"docs-flows","outcome":"PASS","freshness":"FRESH","executed":1,"failed":0,"skipped":0}],
         "coverage":{"scope":"ALL_REQUIRED_MODULES","freshness":"FRESH","complete":true,
          "line":{"covered":94,"missed":6},"branch":{"covered":75,"missed":25}},
         "provenance":{"status":"PRESENT_AS_SUPPLIED","referenceSha256":"%s"},
         "cleanup":"CONFIRMED_AS_SUPPLIED"}
        """.formatted(COMMIT, TREE, digest, "d".repeat(64));
  }
}
