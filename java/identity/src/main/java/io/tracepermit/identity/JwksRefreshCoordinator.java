package io.tracepermit.identity;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Coordinates OIDC JWKS refresh operations with per-provider coalescing, bounded worker capacity,
 * monotonic waiter deadlines, failure cooldowns, and late-result discarding.
 */
public final class JwksRefreshCoordinator implements AutoCloseable {
  public static final int DEFAULT_MAX_CONCURRENT_REFRESHES = 4;
  public static final int DEFAULT_MAX_PENDING_REFRESHES = 16;
  public static final Duration DEFAULT_WAITER_TIMEOUT = Duration.ofSeconds(5);
  public static final Duration DEFAULT_FAILURE_COOLDOWN = Duration.ofSeconds(10);

  private final int maxConcurrentRefreshes;
  private final int maxPendingRefreshes;
  private final Duration waiterTimeout;
  private final Duration failureCooldown;
  private final ThreadPoolExecutor executor;
  private final ConcurrentHashMap<String, InFlightRefresh<?>> inFlight = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Instant> failureCooldowns = new ConcurrentHashMap<>();

  public JwksRefreshCoordinator() {
    this(
        DEFAULT_MAX_CONCURRENT_REFRESHES,
        DEFAULT_MAX_PENDING_REFRESHES,
        DEFAULT_WAITER_TIMEOUT,
        DEFAULT_FAILURE_COOLDOWN);
  }

  public JwksRefreshCoordinator(
      int maxConcurrentRefreshes,
      int maxPendingRefreshes,
      Duration waiterTimeout,
      Duration failureCooldown) {
    if (maxConcurrentRefreshes <= 0 || maxPendingRefreshes < 0) {
      throw new IllegalArgumentException("Concurrency and queue bounds must be positive.");
    }
    this.maxConcurrentRefreshes = maxConcurrentRefreshes;
    this.maxPendingRefreshes = maxPendingRefreshes;
    this.waiterTimeout = Objects.requireNonNull(waiterTimeout);
    this.failureCooldown = Objects.requireNonNull(failureCooldown);
    if (waiterTimeout.isZero() || waiterTimeout.isNegative()) {
      throw new IllegalArgumentException("Waiter timeout must be positive.");
    }

    AtomicInteger workerCount = new AtomicInteger();
    ThreadFactory threadFactory =
        runnable -> {
          Thread thread =
              new Thread(runnable, "jwks-refresh-worker-" + workerCount.incrementAndGet());
          thread.setDaemon(true);
          return thread;
        };

    BlockingQueue<Runnable> queue =
        maxPendingRefreshes == 0
            ? new SynchronousQueue<>()
            : new ArrayBlockingQueue<>(maxPendingRefreshes);
    this.executor =
        new ThreadPoolExecutor(
            Math.min(2, maxConcurrentRefreshes),
            maxConcurrentRefreshes,
            60L,
            TimeUnit.SECONDS,
            queue,
            threadFactory,
            new ThreadPoolExecutor.AbortPolicy());
    this.executor.allowCoreThreadTimeOut(true);
  }

  public <T> T refresh(
      String cacheKey, OidcProviderConfiguration provider, Instant now, Callable<T> task) {
    return refresh(cacheKey, provider, now, task, ignored -> {});
  }

  public <T> T refresh(
      String cacheKey,
      OidcProviderConfiguration provider,
      Instant now,
      Callable<T> task,
      Consumer<T> onSuccess) {
    Objects.requireNonNull(cacheKey);
    Objects.requireNonNull(provider);
    Objects.requireNonNull(now);
    Objects.requireNonNull(task);
    Objects.requireNonNull(onSuccess);

    Instant cooldownUntil = failureCooldowns.get(cacheKey);
    if (cooldownUntil != null && now.isBefore(cooldownUntil)) {
      throw unavailable();
    }

    long startNanos = System.nanoTime();
    long timeoutNanos = waiterTimeout.toNanos();
    long deadlineNanos = startNanos + timeoutNanos;

    @SuppressWarnings("unchecked")
    InFlightRefresh<T> refresh =
        (InFlightRefresh<T>)
            inFlight.compute(
                cacheKey,
                (key, existing) -> {
                  if (existing != null && !existing.future.isDone()) {
                    return existing;
                  }
                  CompletableFuture<T> future = new CompletableFuture<>();
                  InFlightRefresh<T> created =
                      new InFlightRefresh<>(future, startNanos, deadlineNanos);
                  try {
                    executor.submit(
                        () -> {
                          try {
                            if (System.nanoTime() > deadlineNanos) {
                              future.completeExceptionally(
                                  new TimeoutException("Queue delay exceeded refresh deadline"));
                              return;
                            }
                            T result = task.call();
                            if (System.nanoTime() > deadlineNanos) {
                              // Late result! Must not publish or extend freshness.
                              future.completeExceptionally(
                                  new TimeoutException("Refresh completed after deadline"));
                              return;
                            }
                            try {
                              onSuccess.accept(result);
                            } catch (Throwable publishError) {
                              future.completeExceptionally(publishError);
                              return;
                            }
                            future.complete(result);
                          } catch (Throwable throwable) {
                            future.completeExceptionally(throwable);
                          }
                        });
                  } catch (RejectedExecutionException exception) {
                    future.completeExceptionally(exception);
                  }
                  return created;
                });

    try {
      long remainingNanos = refresh.deadlineNanos - System.nanoTime();
      if (remainingNanos <= 0) {
        throw unavailable();
      }
      T result = refresh.future.get(remainingNanos, TimeUnit.NANOSECONDS);
      failureCooldowns.remove(cacheKey);
      return result;
    } catch (TimeoutException exception) {
      throw unavailable();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw unavailable();
    } catch (ExecutionException exception) {
      Throwable cause = exception.getCause();
      if (!(cause instanceof RejectedExecutionException)) {
        Duration cooldown = provider.forcedRefreshCooldown();
        if (cooldown.compareTo(failureCooldown) < 0) {
          cooldown = failureCooldown;
        }
        failureCooldowns.put(cacheKey, now.plus(cooldown));
      }
      if (cause instanceof AuthenticationException authEx) {
        throw authEx;
      }
      throw unavailable();
    } finally {
      if (refresh.future.isDone()) {
        inFlight.remove(cacheKey, refresh);
      }
    }
  }

  public int maxConcurrentRefreshes() {
    return maxConcurrentRefreshes;
  }

  public int maxPendingRefreshes() {
    return maxPendingRefreshes;
  }

  public Duration waiterTimeout() {
    return waiterTimeout;
  }

  public Duration failureCooldown() {
    return failureCooldown;
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }

  private static AuthenticationException unavailable() {
    return new AuthenticationException(
        "OIDC_KEY_SET_UNAVAILABLE", "The configured OIDC verification keys are unavailable.");
  }

  private record InFlightRefresh<T>(
      CompletableFuture<T> future, long startNanos, long deadlineNanos) {}
}
