package io.tracepermit.adapters.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

final class ApprovalAdmissionExecutorsTest {

  @Test
  void quiescenceBarrierWaitsForEveryEarlierAcceptedTask() throws Exception {
    var pool = ApprovalAdmissionExecutors.newDispatchPool(2, 4);
    var blockersEntered = new CountDownLatch(2);
    var releaseBlockers = new CountDownLatch(1);
    var observer = Executors.newSingleThreadExecutor();
    try {
      for (int i = 0; i < 2; i++) {
        pool.execute(() -> {
          blockersEntered.countDown();
          try {
            releaseBlockers.await();
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
        });
      }
      assertThat(blockersEntered.await(5, TimeUnit.SECONDS)).isTrue();

      var quiescence = observer.submit(() ->
          ApprovalAdmissionExecutors.awaitQuiescence(pool, 2, Duration.ofSeconds(5)));
      assertThatThrownBy(() -> quiescence.get(50, TimeUnit.MILLISECONDS))
          .isInstanceOf(TimeoutException.class);

      releaseBlockers.countDown();
      assertThat(quiescence.get(5, TimeUnit.SECONDS)).isTrue();
    } finally {
      releaseBlockers.countDown();
      observer.shutdownNow();
      pool.shutdownNow();
      assertThat(observer.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void quiescenceBarrierFailsWithinItsBoundWhileEarlierWorkIsBlocked() throws Exception {
    var pool = ApprovalAdmissionExecutors.newDispatchPool(1, 2);
    var blockerEntered = new CountDownLatch(1);
    var releaseBlocker = new CountDownLatch(1);
    try {
      pool.execute(() -> {
        blockerEntered.countDown();
        try {
          releaseBlocker.await();
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
      });
      assertThat(blockerEntered.await(5, TimeUnit.SECONDS)).isTrue();

      long started = System.nanoTime();
      assertThat(ApprovalAdmissionExecutors.awaitQuiescence(pool, 1, Duration.ofMillis(100)))
          .isFalse();
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    } finally {
      releaseBlocker.countDown();
      pool.shutdownNow();
      assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }
}
