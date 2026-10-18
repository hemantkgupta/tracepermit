package io.tracepermit.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.contracts.JsonSupport;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Integration proof for the bounded permit consumption store inside the real
 * {@link ExecutionPermitAuthority}.
 *
 * <p>Scope is deliberately narrow: when the bounded store is saturated, a freshly issued,
 * validly signed permit is refused with the replay refusal code and no target effect
 * follows — the test-local counter only proves the effect follows successful consumption.
 * Permit-ID cardinality is not an invocation counter, and the legacy global permit-ID
 * namespace is preserved: tenant- and action-aware overloads do not open cross-tenant
 * reuse.
 */
final class ExecutionPermitAuthorityBoundedStoreTest {
  private static final Path ROOT = Path.of(System.getProperty("tracepermit.repositoryRoot"));
  private static final Instant BASE = Instant.parse("2026-10-08T10:15:31Z");

  @Test
  void saturatedStoreRefusesFreshValidPermitWithoutTargetEffect() throws Exception {
    Fixture fixture = new Fixture(() -> "permit-fresh", 1, 1);
    AtomicInteger targetEffects = new AtomicInteger();

    assertThat(fixture.store().consume("permit-filler", BASE.plusSeconds(30), BASE)).isTrue();

    String permit = fixture.authority().issue(fixture.event(), fixture.evaluation(), fixture.audience());
    assertThatThrownBy(
            () ->
                fixture
                    .authority()
                    .verifyAndConsume(permit, fixture.event(), fixture.audience()))
        .isInstanceOf(ExecutionPermitException.class)
        .extracting(error -> ((ExecutionPermitException) error).code())
        .isEqualTo("EXECUTION_PERMIT_REPLAYED");
    assertThat(targetEffects.get()).isZero();
    assertThat(fixture.store().liveEntries()).isEqualTo(1);
    assertThat(fixture.store().saturatedRefusals()).isEqualTo(1);

    // Positive control: after genuine expiry and bounded reclamation, consumption
    // succeeds again and only then does the target effect follow.
    fixture.clock().advance(Duration.ofSeconds(120));
    String renewed = fixture.authority().issue(fixture.event(), fixture.evaluation(), fixture.audience());
    JsonNode consumed = fixture.authority().verifyAndConsume(renewed, fixture.event(), fixture.audience());
    assertThat(consumed.path("permitId").asText()).isEqualTo("permit-fresh");
    targetEffects.incrementAndGet();
    assertThat(targetEffects.get()).isEqualTo(1);
    assertThat(fixture.store().reclamationRemovals()).isEqualTo(1);
  }

  @Test
  void globalPermitNamespaceClosesCrossTenantOverloadReuse() throws Exception {
    Fixture fixture = new Fixture(() -> "permit-fixed", 2, 2);
    AtomicInteger targetEffects = new AtomicInteger();

    String permit = fixture.authority().issue(fixture.event(), fixture.evaluation(), fixture.audience());
    JsonNode consumed = fixture.authority().verifyAndConsume(permit, fixture.event(), fixture.audience());
    assertThat(consumed.path("permitId").asText()).isEqualTo("permit-fixed");
    targetEffects.incrementAndGet();

    // The legacy namespace stays global: no tenant- or action-aware overload may open
    // cross-tenant reuse of the same permit ID.
    assertThat(fixture.store().consume("tenant-b", "permit-fixed", BASE.plusSeconds(60), BASE))
        .isFalse();
    assertThat(
            fixture
                .store()
                .consume("tenant-b", "permit-fixed", BASE.plusSeconds(60), "sha256:x", BASE))
        .isFalse();
    assertThat(fixture.store().consume("permit-fixed", BASE.plusSeconds(60), BASE)).isFalse();
    assertThat(targetEffects.get()).isEqualTo(1);
    assertThat(fixture.store().liveEntries()).isEqualTo(1);
    assertThat(fixture.store().saturatedRefusals()).isZero();
  }

  private static final class Fixture {
    private final MutableClock clock;
    private final InMemoryPermitConsumptionStore store;
    private final ExecutionPermitAuthority authority;
    private final JsonNode event;
    private final JsonNode evaluation;
    private final String audience;

    Fixture(Supplier<String> ids, int capacity, int cleanupBudget) {
      JsonNode vector = JsonSupport.read(ROOT.resolve("contracts/vectors/execution-permit-v1.json"));
      this.event = JsonSupport.read(ROOT.resolve(vector.path("fixture").asText()));
      this.evaluation = vector.path("evaluation");
      this.audience = vector.path("audience").asText();
      this.clock = new MutableClock(BASE);
      this.store = new InMemoryPermitConsumptionStore(capacity, cleanupBudget);
      this.authority =
          new ExecutionPermitAuthority(
              ExecutionPermitAuthority.DEFAULT_ISSUER,
              "ed25519-key-01",
              keyPair(),
              30,
              clock,
              ids,
              store);
    }

    MutableClock clock() {
      return clock;
    }

    InMemoryPermitConsumptionStore store() {
      return store;
    }

    ExecutionPermitAuthority authority() {
      return authority;
    }

    JsonNode event() {
      return event;
    }

    JsonNode evaluation() {
      return evaluation;
    }

    String audience() {
      return audience;
    }

    private static KeyPair keyPair() {
      try {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
      } catch (NoSuchAlgorithmException exception) {
        throw new IllegalStateException(exception);
      }
    }
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    MutableClock(Instant instant) {
      this.instant = instant;
    }

    void advance(Duration duration) {
      instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(instant, zone);
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
