package io.tracepermit.adapters.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class BoundedDispatchExecutorTest {
  @Test void concurrentOffersCannotExceedEightReservedAndSixteenPending() throws Exception {
    var gate = new CountDownLatch(1);
    var created = new CountDownLatch(8);
    var gateFailed = new AtomicBoolean();
    var ran = new AtomicInteger();
    var pool = pool(8, 16, worker -> new Thread(() -> {
      created.countDown(); awaitGate(gate, gateFailed); worker.run();
    }));
    var producers = Executors.newFixedThreadPool(8);
    try {
      assertThat(created.await(2, TimeUnit.SECONDS)).isTrue();
      var offers = new ArrayList<java.util.concurrent.Future<Boolean>>();
      for (int i = 0; i < 80; i++) {
        offers.add(producers.submit(() -> {
          try { pool.execute(ran::incrementAndGet); return true; }
          catch (RejectedExecutionException rejected) { return false; }
        }));
      }
      int admitted = 0;
      for (var offered : offers) if (offered.get(2, TimeUnit.SECONDS)) admitted++;
      assertThat(admitted).isEqualTo(24);
      assertThat(pool.occupancy()).isEqualTo(new BoundedDispatchExecutor.Occupancy(8, 16));
      assertThat(ran).hasValue(0);
      gate.countDown(); pool.shutdown();
      assertThat(pool.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      assertThat(ran).hasValue(24);
      assertThat(pool.occupancy()).isEqualTo(new BoundedDispatchExecutor.Occupancy(0, 0));
    } finally {
      gate.countDown(); producers.shutdownNow(); stop(pool);
      assertThat(producers.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(gateFailed).isFalse();
  }

  @Test void pendingFifoDrainsOnGracefulShutdownAndRefusesNewWork() throws Exception {
    var pool = pool(1, 3, Thread::new);
    var gate = new CountDownLatch(1);
    var entered = new CountDownLatch(1);
    var gateFailed = new AtomicBoolean();
    var order = Collections.synchronizedList(new ArrayList<Integer>());
    try {
      pool.execute(() -> { entered.countDown(); awaitGate(gate, gateFailed); order.add(0); });
      assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
      for (int i = 1; i <= 3; i++) { int index = i; pool.execute(() -> order.add(index)); }
      pool.shutdown(); pool.shutdown();
      assertThat(pool.isShutdown()).isTrue(); assertThat(pool.isTerminated()).isFalse();
      assertThat(pool.awaitTermination(1, TimeUnit.MILLISECONDS)).isFalse();
      assertThatThrownBy(() -> pool.execute(() -> order.add(99))).isInstanceOf(RejectedExecutionException.class);
      gate.countDown();
      assertThat(pool.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      assertThat(order).containsExactly(0, 1, 2, 3);
      assertThat(pool.isTerminated()).isTrue();
      assertThat(pool.awaitTermination(0, TimeUnit.NANOSECONDS)).isTrue();
    } finally { gate.countDown(); stop(pool); }
    assertThat(gateFailed).isFalse();
  }

  @Test void shutdownNowReturnsPendingAndUnstartedReservationsWithoutRunningThem() throws Exception {
    var gate = new CountDownLatch(1);
    var created = new CountDownLatch(8);
    var gateFailed = new AtomicBoolean();
    var ran = new AtomicInteger();
    var pool = pool(8, 16, worker -> new Thread(() -> {
      created.countDown(); awaitGate(gate, gateFailed); worker.run();
    }));
    var tasks = new ArrayList<Runnable>();
    try {
      assertThat(created.await(2, TimeUnit.SECONDS)).isTrue();
      for (int i = 0; i < 24; i++) { Runnable task = ran::incrementAndGet; tasks.add(task); pool.execute(task); }
      var abandoned = pool.shutdownNow();
      assertThat(abandoned).containsExactlyInAnyOrderElementsOf(tasks);
      assertThat(pool.shutdownNow()).isEmpty();
      gate.countDown();
      assertThat(pool.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      assertThat(ran).hasValue(0);
      assertThat(pool.occupancy()).isEqualTo(new BoundedDispatchExecutor.Occupancy(0, 0));
      assertThatThrownBy(() -> pool.submit(ran::incrementAndGet)).isInstanceOf(RejectedExecutionException.class);
    } finally { gate.countDown(); stop(pool); }
  }

  @Test void shutdownNowInterruptsActiveWorkButDoesNotPretendItTerminated() throws Exception {
    var pool = pool(1, 2, Thread::new);
    var entered = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);
    var gate = new CountDownLatch(1);
    var gateFailed = new AtomicBoolean();
    Runnable firstPending = () -> {};
    Runnable secondPending = () -> {};
    try {
      pool.execute(() -> {
        entered.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (gate.getCount() != 0 && System.nanoTime() < deadline) {
          try { gate.await(10, TimeUnit.MILLISECONDS); }
          catch (InterruptedException ignored) { interrupted.countDown(); }
        }
        if (gate.getCount() != 0) gateFailed.set(true);
      });
      assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
      pool.execute(firstPending); pool.execute(secondPending);
      assertThat(pool.shutdownNow()).containsExactly(firstPending, secondPending);
      assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(pool.awaitTermination(1, TimeUnit.MILLISECONDS)).isFalse();
      assertThat(pool.occupancy()).isEqualTo(new BoundedDispatchExecutor.Occupancy(1, 0));
      gate.countDown();
      assertThat(pool.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    } finally { gate.countDown(); stop(pool); }
    assertThat(gateFailed).isFalse();
  }

  @Test void failedCancelledAndInterruptedTasksReleaseOnceWithoutRetryOrInterruptLeak() throws Exception {
    var pool = pool(1, 8, Thread::new);
    var gate = new CountDownLatch(1);
    var gateFailed = new AtomicBoolean();
    var entered = new CountDownLatch(1);
    var attempts = new AtomicInteger();
    var cancelledRan = new AtomicBoolean();
    try {
      pool.execute(() -> { entered.countDown(); awaitGate(gate, gateFailed); });
      assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
      pool.execute(() -> { attempts.incrementAndGet(); throw new IllegalStateException("private-canary"); });
      pool.execute(() -> { attempts.incrementAndGet(); throw new AssertionError("private-canary"); });
      var failed = pool.submit(() -> { attempts.incrementAndGet(); throw new IllegalArgumentException("private-canary"); });
      var cancelled = pool.submit(() -> cancelledRan.set(true));
      assertThat(cancelled.cancel(false)).isTrue();
      pool.execute(() -> Thread.currentThread().interrupt());
      var next = pool.submit(() -> Thread.currentThread().isInterrupted());
      gate.countDown();
      assertThatThrownBy(() -> failed.get(2, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
      assertThat(next.get(2, TimeUnit.SECONDS)).isFalse();
      assertThat(ApprovalAdmissionExecutors.awaitQuiescence(pool, 1, Duration.ofSeconds(2))).isTrue();
      assertThat(attempts).hasValue(3); assertThat(cancelledRan).isFalse();
      assertThat(pool.occupancy()).isEqualTo(new BoundedDispatchExecutor.Occupancy(0, 0));
      assertThat(pool.submit(() -> 42).get(2, TimeUnit.SECONDS)).isEqualTo(42);
    } finally { gate.countDown(); stop(pool); }
    assertThat(gateFailed).isFalse();
  }

  @Test void futureCompletionStillCannotReleaseAReservedLaneEarly() throws Exception {
    var pool = pool(1, 2, Thread::new);
    var completionEntered = new CountDownLatch(1);
    var releaseCompletion = new CountDownLatch(1);
    var gateFailed = new AtomicBoolean();
    var observer = Executors.newSingleThreadExecutor();
    var future = new FutureTask<Void>(() -> null) {
      @Override protected void done() { completionEntered.countDown(); awaitGate(releaseCompletion, gateFailed); }
    };
    try {
      pool.execute(future);
      assertThat(completionEntered.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(future.get(2, TimeUnit.SECONDS)).isNull();
      assertThat(pool.occupancy().reserved()).isEqualTo(1);
      var quiet = observer.submit(() -> ApprovalAdmissionExecutors.awaitQuiescence(pool, 1, Duration.ofSeconds(2)));
      assertThatThrownBy(() -> quiet.get(50, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
      releaseCompletion.countDown();
      assertThat(quiet.get(2, TimeUnit.SECONDS)).isTrue();
    } finally {
      releaseCompletion.countDown(); observer.shutdownNow(); stop(pool);
      assertThat(observer.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(gateFailed).isFalse();
  }

  @Test void ownedBarrierRetainsItsBudgetInterruptionAndShutdownRefusal() throws Exception {
    var pool = pool(1, 2, Thread::new);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var gateFailed = new AtomicBoolean();
    var observer = Executors.newSingleThreadExecutor();
    var observingThread = new AtomicReference<Thread>();
    var interruptRetained = new AtomicBoolean();
    try {
      pool.execute(() -> { entered.countDown(); awaitGate(release, gateFailed); });
      assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
      long start = System.nanoTime();
      assertThat(ApprovalAdmissionExecutors.awaitQuiescence(pool, 1, Duration.ofMillis(100))).isFalse();
      assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
      var quiet = observer.submit(() -> {
        observingThread.set(Thread.currentThread());
        boolean result = ApprovalAdmissionExecutors.awaitQuiescence(pool, 1, Duration.ofSeconds(2));
        interruptRetained.set(Thread.currentThread().isInterrupted());
        return result;
      });
      // The observer is owned and published before interruption; no sleep-based race.
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (observingThread.get() == null && System.nanoTime() < deadline) Thread.onSpinWait();
      assertThat(observingThread.get()).isNotNull(); observingThread.get().interrupt();
      assertThat(quiet.get(2, TimeUnit.SECONDS)).isFalse(); assertThat(interruptRetained).isTrue();
      pool.shutdown();
      assertThat(ApprovalAdmissionExecutors.awaitQuiescence(pool, 1, Duration.ofSeconds(2))).isFalse();
    } finally {
      release.countDown(); observer.shutdownNow(); stop(pool);
      assertThat(observer.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(gateFailed).isFalse();
  }

  @Test void rejectingObserverCannotSuppressOverflowOrExposeItsFailure() throws Exception {
    var pool = pool(1, 1, Thread::new);
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    var gateFailed = new AtomicBoolean(); var observed = new AtomicInteger();
    var snapshot = new AtomicReference<BoundedDispatchExecutor.Occupancy>();
    var original = pool.rejectionObserver();
    BoundedDispatchExecutor.RejectionObserver failing = (qualificationTask, occupancy) -> {
      observed.incrementAndGet(); snapshot.set(occupancy); throw new IllegalStateException("private-canary");
    };
    try {
      assertThatThrownBy(() -> pool.execute(null)).isInstanceOf(NullPointerException.class);
      pool.execute(() -> { entered.countDown(); awaitGate(release, gateFailed); });
      assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue(); pool.execute(() -> {});
      pool.replaceRejectionObserver(original, failing);
      assertThatThrownBy(() -> pool.execute(() -> {})).isInstanceOf(RejectedExecutionException.class)
          .hasMessage("Approval dispatch capacity unavailable.").hasNoCause().hasNoSuppressedExceptions();
      assertThat(observed).hasValue(1); assertThat(snapshot.get()).isEqualTo(new BoundedDispatchExecutor.Occupancy(1, 1));
      assertThatThrownBy(() -> pool.replaceRejectionObserver(original, failing))
          .isInstanceOf(IllegalStateException.class).hasMessage("ADMISSION_DIAGNOSTIC_CHANGED");
      pool.replaceRejectionObserver(failing, original);
    } finally { release.countDown(); stop(pool); }
    assertThat(gateFailed).isFalse();
  }

  @Test void invalidOrFailedWorkerStartupDoesNotLeaveOwnedThreadsRunning() throws Exception {
    assertThatThrownBy(() -> pool(1, 1, task -> null)).isInstanceOf(NullPointerException.class);
    var reused = new Thread(() -> {});
    assertThatThrownBy(() -> pool(2, 1, task -> reused)).isInstanceOf(IllegalArgumentException.class);
    assertThat(reused.getState()).isEqualTo(Thread.State.NEW);
    var created = new ArrayList<Thread>();
    var index = new AtomicInteger();
    ThreadFactory factory = worker -> {
      Thread thread = index.incrementAndGet() == 2 ? new Thread(worker) {
        @Override public synchronized void start() { throw new IllegalStateException("private-canary"); }
      } : new Thread(worker);
      created.add(thread); return thread;
    };
    assertThatThrownBy(() -> pool(3, 1, factory)).isInstanceOf(IllegalStateException.class)
        .hasMessage("DISPATCH_WORKER_START_FAILED").hasNoCause();
    for (Thread thread : created) { thread.join(2000); assertThat(thread.isAlive()).isFalse(); }
  }

  @Test void workerStartupPrecedesBindingAndBindingFailureStopsOwnedWorkers() throws Exception {
    var created = new ArrayList<Thread>();
    try (var occupied = new java.net.ServerSocket()) {
      occupied.bind(new java.net.InetSocketAddress("127.0.0.1", 0));
      var address = new java.net.InetSocketAddress("127.0.0.1", occupied.getLocalPort());
      // If binding ran before worker startup this would be a BindException,
      // not the factory failure, and an unstarted bound server could leak.
      assertThatThrownBy(() -> SimulatorApprovalServer.bindListener(address, 1, 1, worker -> null))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> SimulatorApprovalServer.bindListener(address, 2, 1, worker -> {
        var thread = new Thread(worker); created.add(thread); return thread;
      })).isInstanceOf(java.net.BindException.class);
    }
    assertThat(created).hasSize(2);
    for (Thread thread : created) { thread.join(2000); assertThat(thread.isAlive()).isFalse(); }
  }

  private static BoundedDispatchExecutor pool(int active, int pending, ThreadFactory factory) {
    return (BoundedDispatchExecutor) ApprovalAdmissionExecutors.newDispatchPool(active, pending, factory);
  }

  private static void awaitGate(CountDownLatch gate, AtomicBoolean failed) {
    try { if (!gate.await(5, TimeUnit.SECONDS)) failed.set(true); }
    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
  }

  private static void stop(BoundedDispatchExecutor pool) throws InterruptedException {
    pool.shutdownNow(); assertThat(pool.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
  }
}
