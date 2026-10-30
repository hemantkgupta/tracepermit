package io.tracepermit.sandbox.verifier;

import java.nio.file.Path;

/** Private validation adapter compiled beside the byte-unchanged published strict parser. */
public final class PublishedTarCompatibility {
  private PublishedTarCompatibility() {}

  public static void main(String[] args) {
    if (args.length != 3) throw new IllegalArgumentException("archive digest expected-result required");
    String actual = TarVerifier.verify(Path.of(args[0]), args[1]).name();
    if (!actual.equals(args[2])) {
      throw new IllegalStateException("expected " + args[2] + " but got " + actual);
    }
    System.out.println(actual);
  }
}
