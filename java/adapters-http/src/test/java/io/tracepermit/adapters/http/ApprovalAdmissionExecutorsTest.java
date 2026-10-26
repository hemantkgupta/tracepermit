package io.tracepermit.adapters.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class ApprovalAdmissionExecutorsTest {

  @Test
  void idleLaneReservationsMustSurviveWorkerWakeupDelay() throws Exception {
    var workersCreated = new CountDownLatch(8);
    var allowWorkers = new CountDownLatch(1);
    var ran = new AtomicInteger();
    var pool = ApprovalAdmissionExecutors.newDispatchPool(8, 16, worker -> {
      var thread = new Thread(() -> {
        workersCreated.countDown();
        try {
          if (!allowWorkers.await(5, TimeUnit.SECONDS)) throw new AssertionError("WORKER_GATE_TIMEOUT");
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
        worker.run();
      });
      thread.setDaemon(true);
      return thread;
    });
    try {
      // Model already-created, idle workers whose wakeup is delayed. Their
      // scheduler state must not collapse eight lane reservations into queue16.
      if (pool instanceof ThreadPoolExecutor legacy) legacy.prestartAllCoreThreads();
      assertThat(workersCreated.await(2, TimeUnit.SECONDS)).isTrue();
      for (int i = 0; i < 24; i++) pool.execute(ran::incrementAndGet);
      assertThat(ran).as("no task runs before a worker is released").hasValue(0);
      assertThatThrownBy(() -> pool.execute(ran::incrementAndGet))
          .isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
      allowWorkers.countDown();
      pool.shutdown();
      assertThat(pool.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      assertThat(ran).as("all and only the 24 admitted tasks run once").hasValue(24);
    } finally {
      allowWorkers.countDown(); pool.shutdownNow();
      assertThat(pool.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void completedFuturesMustNotReportQuiescenceBeforeTheirWorkersLeave() throws Exception {
    var pool = new PostCompletionPool(8);
    var observer = Executors.newSingleThreadExecutor();
    try {
      var quiet = observer.submit(() -> ApprovalAdmissionExecutors.awaitQuiescence(pool, 8, Duration.ofSeconds(5)));
      assertThat(pool.completed.await(5, TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(() -> quiet.get(50, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
      pool.release.countDown();
      assertThat(quiet.get(5, TimeUnit.SECONDS)).isTrue();
      assertThat(pool.getActiveCount()).isZero();
    } finally {
      pool.release.countDown(); observer.shutdownNow(); pool.shutdownNow();
      assertThat(observer.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      assertThat(pool.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(pool.holdTimedOut).isFalse();
  }

  @Test
  void completedFutureHoldCannotExtendTheOriginalBarrierBudget() throws Exception {
    var pool = new PostCompletionPool(1);
    try {
      long started = System.nanoTime();
      assertThat(ApprovalAdmissionExecutors.awaitQuiescence(pool, 1, Duration.ofMillis(100))).isFalse();
      assertThat(pool.completed.getCount()).isZero();
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    } finally {
      pool.release.countDown(); pool.shutdownNow();
      assertThat(pool.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void completedFutureHoldPreservesInterruptionAndRefusesShutdown() throws Exception {
    var pool = new PostCompletionPool(1);
    var observer = Executors.newSingleThreadExecutor();
    var thread = new AtomicReference<Thread>();
    var interrupted = new AtomicBoolean();
    try {
      var quiet = observer.submit(() -> {
        thread.set(Thread.currentThread());
        boolean result = ApprovalAdmissionExecutors.awaitQuiescence(pool, 1, Duration.ofSeconds(5));
        interrupted.set(Thread.currentThread().isInterrupted());
        return result;
      });
      assertThat(pool.completed.await(5, TimeUnit.SECONDS)).isTrue();
      thread.get().interrupt();
      assertThat(quiet.get(2, TimeUnit.SECONDS)).isFalse();
      assertThat(interrupted).isTrue();
      pool.shutdown();
      assertThat(ApprovalAdmissionExecutors.awaitQuiescence(pool, 1, Duration.ofSeconds(5))).isFalse();
    } finally {
      pool.release.countDown(); observer.shutdownNow(); pool.shutdownNow();
      assertThat(observer.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      assertThat(pool.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }
  }

  /** Widen the exact Future-complete/worker-release interval without changing admission. */
  private static final class PostCompletionPool extends ThreadPoolExecutor {
    private final CountDownLatch completed;
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicBoolean holdTimedOut = new AtomicBoolean();

    private PostCompletionPool(int lanes) {
      super(lanes, lanes, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), new AbortPolicy());
      completed = new CountDownLatch(lanes);
    }

    @Override protected void afterExecute(Runnable task, Throwable failure) {
      super.afterExecute(task, failure);
      if (task instanceof RunnableFuture<?>) {
        completed.countDown();
        try {
          if (!release.await(5, TimeUnit.SECONDS)) holdTimedOut.set(true);
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
      }
    }
  }

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
