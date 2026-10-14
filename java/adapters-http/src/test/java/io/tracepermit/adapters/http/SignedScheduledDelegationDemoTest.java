package io.tracepermit.adapters.http;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

final class SignedScheduledDelegationDemoTest {
  @Test
  void narrowedChildExecutesOnceWhileFourAmplificationsNeverDispatch() {
    assertThatCode(() -> SignedScheduledSemanticDemoMain.main(new String[] {"--delegation-auto"}))
        .doesNotThrowAnyException();
  }
}
