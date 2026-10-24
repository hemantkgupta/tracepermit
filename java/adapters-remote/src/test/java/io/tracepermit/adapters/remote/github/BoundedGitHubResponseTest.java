package io.tracepermit.adapters.remote.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class BoundedGitHubResponseTest {
  @ParameterizedTest
  @ValueSource(ints = {0, 1, 1023, 1024, 1025, 10_000_000})
  void readsAtMostOneOverflowByteEvenWhenStreamAdvertisesNoLength(int availableBytes)
      throws IOException {
    int limit = 1024;
    var input = new InputStream() {
      private int consumed;

      @Override
      public int available() {
        return 0;
      }

      @Override
      public int read() throws IOException {
        if (consumed >= availableBytes) {
          return -1;
        }
        if (consumed == limit + 1) {
          throw new IOException("Read past the overflow sentinel");
        }
        consumed++;
        return 'x';
      }
    };

    byte[] result = BoundedGitHubResponse.read(input, limit);

    assertThat(result).hasSize(Math.min(availableBytes, limit + 1));
    assertThat(input.consumed).isEqualTo(result.length);
  }

  @Test
  void countsBytesRatherThanCharacters() throws IOException {
    byte[] utf8 = "éé".getBytes(StandardCharsets.UTF_8);
    assertThat(BoundedGitHubResponse.read(new ByteArrayInputStream(utf8), 2)).hasSize(3);
  }

  @Test
  void propagatesReadFailureWithoutTreatingPartialBodyAsConfirmation() {
    InputStream input = new InputStream() {
      @Override
      public int read() throws IOException {
        throw new IOException("synthetic read failure");
      }
    };
    assertThatThrownBy(() -> BoundedGitHubResponse.read(input, 1024))
        .isInstanceOf(IOException.class).hasMessage("synthetic read failure");
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, 1_048_577, Integer.MAX_VALUE})
  void rejectsUnsafeLimitsBeforeReading(int limit) {
    assertThatThrownBy(() -> BoundedGitHubResponse.read(InputStream.nullInputStream(), limit))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
