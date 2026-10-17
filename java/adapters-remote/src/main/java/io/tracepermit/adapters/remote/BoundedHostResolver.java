package io.tracepermit.adapters.remote;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded worker pool for pre-call DNS resolution.
 *
 * <p>Enforces strict bounds on active and pending DNS worker threads, uses monotonic
 * elapsed-time deadlines, and fails closed when the worker pool is exhausted.
 */
public final class BoundedHostResolver implements AutoCloseable {
  public static final int DEFAULT_MAX_WORKERS = 8;
  public static final int DEFAULT_MAX_PENDING = 16;

  private static final BoundedHostResolver DEFAULT_INSTANCE =
      new BoundedHostResolver(DEFAULT_MAX_WORKERS, DEFAULT_MAX_PENDING);

  public static BoundedHostResolver defaultResolver() {
    return DEFAULT_INSTANCE;
  }

  private final int maxWorkers;
  private final int maxPending;
  private final ThreadPoolExecutor executor;

  public BoundedHostResolver(int maxWorkers, int maxPending) {
    if (maxWorkers <= 0 || maxPending < 0) {
      throw new IllegalArgumentException("Worker and pending limits must be positive.");
    }
    this.maxWorkers = maxWorkers;
    this.maxPending = maxPending;
    AtomicInteger threadCounter = new AtomicInteger();
    ThreadFactory threadFactory =
        runnable -> {
          Thread thread = new Thread(runnable, "ssrf-dns-worker-" + threadCounter.incrementAndGet());
          thread.setDaemon(true);
          return thread;
        };
    BlockingQueue<Runnable> queue =
        maxPending == 0 ? new SynchronousQueue<>() : new ArrayBlockingQueue<>(maxPending);
    this.executor =
        new ThreadPoolExecutor(
            Math.min(2, maxWorkers),
            maxWorkers,
            60L,
            TimeUnit.SECONDS,
            queue,
            threadFactory,
            new ThreadPoolExecutor.AbortPolicy());
    this.executor.allowCoreThreadTimeOut(true);
  }

  public List<InetAddress> resolve(
      SsrfSafeHttpsJwkSetSource.HostResolver resolver, String hostname, Duration timeout) {
    Objects.requireNonNull(resolver);
    Objects.requireNonNull(hostname);
    Objects.requireNonNull(timeout);

    long timeoutNanos = timeout.toNanos();
    if (timeoutNanos <= 0) {
      throw new JwksFetchException();
    }

    Future<List<InetAddress>> future;
    try {
      future = executor.submit(() -> resolver.resolve(hostname));
    } catch (RejectedExecutionException exception) {
      throw new JwksFetchException();
    }

    try {
      return future.get(timeoutNanos, TimeUnit.NANOSECONDS);
    } catch (TimeoutException exception) {
      future.cancel(true);
      throw new JwksFetchException();
    } catch (InterruptedException exception) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw new JwksFetchException();
    } catch (ExecutionException exception) {
      throw new JwksFetchException();
    }
  }

  public int maxWorkers() {
    return maxWorkers;
  }

  public int maxPending() {
    return maxPending;
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }
}
