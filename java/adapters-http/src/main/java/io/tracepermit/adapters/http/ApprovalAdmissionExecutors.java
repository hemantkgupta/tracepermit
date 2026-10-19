package io.tracepermit.adapters.http;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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

  static void validate(int activeLanes, int pendingBound) {
    if (activeLanes < 1 || activeLanes > MAX_ACTIVE_LANES
        || pendingBound < 1 || pendingBound > MAX_PENDING_BOUND) {
      throw new IllegalArgumentException(
          "Admission capacity must stay within 1.." + MAX_ACTIVE_LANES + " active lanes and 1.."
              + MAX_PENDING_BOUND + " pending exchanges.");
    }
  }
}
