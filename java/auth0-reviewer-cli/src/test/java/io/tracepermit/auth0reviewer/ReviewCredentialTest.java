package io.tracepermit.auth0reviewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class ReviewCredentialTest {
  @Test void verifiedCredentialIsPrivateOneExchangeAndErasedOnClose() {
    AtomicInteger exchanges = new AtomicInteger();
    var login = new ReviewerLogin(ReviewerFixtures.config(), (state, uri) -> "synthetic-code", form -> {
      exchanges.incrementAndGet();return ReviewerFixtures.response(c -> {});
    }, new SecureRandom(), ReviewerFixtures.CLOCK);
    var credential = login.loginForReview();
    byte[] copy = credential.copy();
    assertThat(copy).isNotEmpty();assertThat(credential.copy()).isNotSameAs(copy).containsExactly(copy);
    assertThat(credential.identity().mappedRole()).contains("REVIEWER");
    assertThat(credential.toString()).isEqualTo("ReviewCredential[redacted]");
    byte[] owned = {1,2,3};
    var temporary = new ReviewCredential(credential.identity(),owned);
    temporary.close();assertThat(owned).containsOnly((byte)0);
    credential.close();credential.close();
    assertThatThrownBy(credential::copy).hasMessage("REVIEW_CREDENTIAL_CLOSED");
    assertThat(exchanges).hasValue(1);
    java.util.Arrays.fill(copy,(byte)0);
  }
}
