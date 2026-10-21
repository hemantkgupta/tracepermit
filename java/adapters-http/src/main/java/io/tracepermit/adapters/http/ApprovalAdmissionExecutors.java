package io.tracepermit.adapters.http;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded admission plumbing for the loopback approval foundation. The dispatch
 * pool runs exactly {@code activeLanes} lanes over a queue bounded at
 * {@code pendingBound}, so at most {@code activeLanes + pendingBound} exchanges
 * are ever accepted: active work is the lane count, pending work is the queue.
 * A lane only starts a handler once it has been taken from the queue, so no
 * authentication, body parsing, or database work happens before admission, and
 * a queued exchange runs no handler code but retains its connection and any
 * buffered bytes. These are task-count bounds, not TCP or byte bounds. An offer
 * beyond the pending bound is rejected before any handler runs, which the JDK HTTP
 * implementation surfaces as a closed connection with no response (the
 * dispatcher catches the rejection and closes only that connection); callers
 * must not reframe that into a guaranteed JSON error contract. Threads are
 * daemon and named so a lingering blocked handler cannot hold the JVM exit.
 */
final class ApprovalAdmissionExecutors {
  static final int DEFAULT_ACTIVE_LANES = 8;
  static final int DEFAULT_PENDING_BOUND = 16;
  static final int MAX_ACTIVE_LANES = 64;
  static final int MAX_PENDING_BOUND = 1024;
  static final long SHUTDOWN_JOIN_SECONDS = 2;

  private ApprovalAdmissionExecutors() {}

  static ExecutorService newDispatchPool(int activeLanes, int pendingBound) {
    validate(activeLanes, pendingBound);
    var lanes = new AtomicInteger();
    return new ThreadPoolExecutor(activeLanes, activeLanes, 60, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(pendingBound),
        task -> {
          Thread thread = new Thread(task, "approval-dispatch-" + lanes.incrementAndGet());
          thread.setDaemon(true);
          return thread;
        },
        new ThreadPoolExecutor.AbortPolicy());
  }

  /**
   * Establishes a bounded test/qualification barrier behind every exchange already
   * accepted by this fixed dispatch pool. Each lane must reach and hold one barrier
   * task before the barrier is released, which proves that all earlier active and
   * queued handler tasks have returned. This does not retry an exchange, alter the
   * admission bounds, or expose a runtime endpoint.
   */
  static boolean awaitQuiescence(ExecutorService executor, int activeLanes, Duration timeout) {
    Objects.requireNonNull(executor, "Executor must not be null.");
    Objects.requireNonNull(timeout, "Timeout must not be null.");
    if (activeLanes < 1 || activeLanes > MAX_ACTIVE_LANES) {
      throw new IllegalArgumentException("Active lanes must stay within the configured bounds.");
    }
    if (timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("Quiescence timeout must be positive.");
    }

    long timeoutNanos;
    try {
      timeoutNanos = timeout.toNanos();
    } catch (ArithmeticException overflow) {
      timeoutNanos = Long.MAX_VALUE;
    }
    long start = System.nanoTime();
    long deadline = timeoutNanos == Long.MAX_VALUE || Long.MAX_VALUE - start < timeoutNanos
        ? Long.MAX_VALUE
        : start + timeoutNanos;

    var entered = new java.util.concurrent.CountDownLatch(activeLanes);
    var release = new java.util.concurrent.CountDownLatch(1);
    var barriers = new ArrayList<Future<?>>(activeLanes);
    try {
      for (int i = 0; i < activeLanes; i++) {
        barriers.add(executor.submit(() -> {
          entered.countDown();
          try {
            release.await();
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
        }));
      }

      if (!entered.await(remainingNanos(deadline), TimeUnit.NANOSECONDS)) {
        return false;
      }
      release.countDown();
      for (Future<?> barrier : barriers) {
        barrier.get(remainingNanos(deadline), TimeUnit.NANOSECONDS);
      }
      return true;
    } catch (RejectedExecutionException | ExecutionException | TimeoutException failure) {
      return false;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    } finally {
      release.countDown();
      for (Future<?> barrier : barriers) {
        if (!barrier.isDone()) barrier.cancel(true);
      }
    }
  }

  private static long remainingNanos(long deadline) {
    if (deadline == Long.MAX_VALUE) return Long.MAX_VALUE;
    return Math.max(0L, deadline - System.nanoTime());
  }

  static void validate(int activeLanes, int pendingBound) {
    if (activeLanes < 1 || activeLanes > MAX_ACTIVE_LANES
        || pendingBound < 1 || pendingBound > MAX_PENDING_BOUND) {
      throw new IllegalArgumentException(
          "Admission capacity must stay within 1.." + MAX_ACTIVE_LANES + " active lanes and 1.."
              + MAX_PENDING_BOUND + " pending exchanges.");
    }
  }
}
