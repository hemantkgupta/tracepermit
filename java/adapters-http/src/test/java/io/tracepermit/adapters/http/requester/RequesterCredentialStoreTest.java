package io.tracepermit.adapters.http.requester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RequesterCredentialStoreTest {
  private static final Instant EXPIRY = InstalledRequesterTestSupport.NOW.plusSeconds(600);
  @TempDir Path directory;

  @Test void publishesExactOwnerOnlyEnvelopeAndConsumesOnce() throws Exception {
    var fixture = InstalledRequesterTestSupport.fixture(directory);
    var store = new RequesterCredentialStore(
        fixture.credential(), InstalledRequesterTestSupport.CLOCK);
    store.publish("sentinelA.sentinelB.sentinelC", EXPIRY);
    assertThat(Files.getPosixFilePermissions(fixture.credential()))
        .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    String envelope = Files.readString(fixture.credential(), StandardCharsets.UTF_8);
    assertThat(envelope).containsOnlyOnce("accessToken").containsOnlyOnce("expiresAt")
        .containsOnlyOnce("schemaVersion").doesNotContain("refresh", "clientSecret", "idToken");

    var credential = store.read();
    assertThat(credential.accessToken()).isEqualTo("sentinelA.sentinelB.sentinelC");
    assertThat(credential.expiresAt()).isEqualTo(EXPIRY);
    store.consume(credential);
    assertThat(fixture.credential()).doesNotExist();
    assertFailure("CREDENTIAL_CLEANUP_REQUIRED", () -> store.consume(credential));
  }

  @Test void existingPartialExpiredAndLinkedTargetsAreNeverOverwritten() throws Exception {
    var fixture = InstalledRequesterTestSupport.fixture(directory);
    var store = new RequesterCredentialStore(
        fixture.credential(), InstalledRequesterTestSupport.CLOCK);
    Files.writeString(fixture.credential(), "partial", StandardCharsets.US_ASCII);
    Files.setPosixFilePermissions(fixture.credential(), PosixFilePermissions.fromString("rw-------"));
    assertFailure("CREDENTIAL_ALREADY_EXISTS",
        () -> store.publish("newA.newB.newC", EXPIRY));
    assertThat(Files.readString(fixture.credential())).isEqualTo("partial");

    Files.delete(fixture.credential());
    assertFailure("CREDENTIAL_EXPIRED",
        () -> store.publish("newA.newB.newC", InstalledRequesterTestSupport.NOW.plusSeconds(59)));
    assertThat(fixture.credential()).doesNotExist();

    Path outside = directory.resolve("outside");
    Files.writeString(outside, "outside", StandardCharsets.US_ASCII);
    Files.setPosixFilePermissions(outside, PosixFilePermissions.fromString("rw-------"));
    Files.createLink(fixture.credential(), outside);
    assertFailure("CREDENTIAL_ALREADY_EXISTS",
        () -> store.publish("newA.newB.newC", EXPIRY));
    assertThat(Files.readString(outside)).isEqualTo("outside");
  }

  @Test void simultaneousPublishersHaveExactlyOneWinnerWithoutOverwrite() throws Exception {
    var fixture = InstalledRequesterTestSupport.fixture(directory);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var futures = List.of("firstA.firstB.firstC", "secondA.secondB.secondC").stream()
          .map(token -> executor.submit(() -> {
            start.await();
            try {
              new RequesterCredentialStore(fixture.credential(),
                  InstalledRequesterTestSupport.CLOCK).publish(token, EXPIRY);
              return "success";
            } catch (InstalledRequesterFailure failure) {
              return failure.code();
            }
          })).toList();
      start.countDown();
      assertThat(futures.stream().map(future -> {
        try {
          return future.get();
        } catch (Exception failure) {
          throw new AssertionError(failure);
        }
      }).toList()).containsExactlyInAnyOrder("success", "CREDENTIAL_ALREADY_EXISTS");
    }
    var stored = new RequesterCredentialStore(
        fixture.credential(), InstalledRequesterTestSupport.CLOCK).read();
    assertThat(stored.accessToken()).isIn("firstA.firstB.firstC", "secondA.secondB.secondC");
  }

  @Test void unsafeDirectoryModeAndTamperedEnvelopeFailWithStableCategory() throws Exception {
    var fixture = InstalledRequesterTestSupport.fixture(directory);
    var store = new RequesterCredentialStore(
        fixture.credential(), InstalledRequesterTestSupport.CLOCK);
    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-xr-x"));
    assertFailure("CREDENTIAL_PATH_UNSAFE",
        () -> store.publish("newA.newB.newC", EXPIRY));
    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));

    Files.writeString(fixture.credential(), "{\"schemaVersion\":\"1.0\","
        + "\"accessToken\":\"secretA.secretB.secretC\",\"expiresAt\":\"invalid\"}");
    Files.setPosixFilePermissions(fixture.credential(), PosixFilePermissions.fromString("rw-------"));
    assertFailure("CREDENTIAL_PATH_UNSAFE", store::read);
  }

  private static void assertFailure(String code, Runnable operation) {
    assertThatThrownBy(operation::run).isInstanceOfSatisfying(
        InstalledRequesterFailure.class,
        failure -> assertThat(failure.code()).isEqualTo(code));
  }
}
