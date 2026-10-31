package io.tracepermit.auth0reviewer;

import io.tracepermit.agentcore.organizationcli.Auth0OrganizationReview;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Controlling-terminal intent only; never a replacement for verified independent reviewer identity. */
final class ReviewConfirmation {
  private final Reader input;
  private final Writer output;
  ReviewConfirmation(Reader input, Writer output) {
    this.input = java.util.Objects.requireNonNull(input);
    this.output = java.util.Objects.requireNonNull(output);
  }
  private ReviewConfirmation() { input = null;output = null; }
  static ReviewConfirmation live() { return new ReviewConfirmation(); }
  boolean decide(Auth0OrganizationReview.Preview preview) throws java.io.IOException {
    return input == null ? confirm(preview) : confirm(input, output, preview);
  }

  static boolean available() {
    Path tty = Path.of("/dev/tty");
    try {
      if (Files.isSymbolicLink(tty)
          || (((Number)Files.getAttribute(tty, "unix:mode")).intValue() & 0170000) != 0020000) return false;
      try (var in = new java.io.FileInputStream(tty.toFile());
          var out = new java.io.FileOutputStream(tty.toFile())) {
        return in.getFD().valid() && out.getFD().valid();
      }
    } catch (java.io.IOException | RuntimeException failure) { return false; }
  }

  static boolean confirm(Auth0OrganizationReview.Preview preview) throws java.io.IOException {
    try (var in = Files.newBufferedReader(Path.of("/dev/tty"), StandardCharsets.UTF_8);
        var out = Files.newBufferedWriter(Path.of("/dev/tty"), StandardCharsets.UTF_8)) {
      return confirm(in, out, preview);
    }
  }

  static boolean confirm(Reader input, Writer output, Auth0OrganizationReview.Preview preview)
      throws java.io.IOException {
    output.write("Login has not approved this operation. Review the bound operation data:\n");
    output.write(preview.display());
    output.write("\nType exactly: " + preview.confirmation() + "\n> ");
    output.flush();
    StringBuilder answer = new StringBuilder();
    for (int i = 0; i <= 256; i++) {
      int c = input.read();
      if (c == '\n') return preview.confirms(answer.toString());
      if (c < 32 || c > 126) return false;
      answer.append((char)c);
    }
    return false;
  }
}
