package io.tracepermit.adapters.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Numeric literals only: these tests do not perform an external DNS lookup or connection. */
final class BoundedHostResolverPublicAddressTest {
  @Test void publicLiteralReturnsTheValidatedConnectAddress() {
    try (var resolver = new BoundedHostResolver(1, 0)) {
      assertThat(resolver.resolvePublic("8.8.8.8", Duration.ofSeconds(1))).hasSize(1)
          .allMatch(address -> "8.8.8.8".equals(address.getHostAddress()));
    }
  }
  @ParameterizedTest @ValueSource(strings = {"127.0.0.1", "0.0.0.0", "10.0.0.1", "169.254.169.254", "192.168.1.1",
      "100.64.0.1", "198.18.0.1", "203.0.113.1", "::1", "fc00::1", "2001:db8::1"})
  void privateReservedLiteralNeverBecomesAConnectAddress(String address) {
    try (var resolver = new BoundedHostResolver(1, 0)) {
      assertThatThrownBy(() -> resolver.resolvePublic(address, Duration.ofSeconds(1)))
          .isInstanceOf(JwksFetchException.class).hasNoCause();
    }
  }
}
