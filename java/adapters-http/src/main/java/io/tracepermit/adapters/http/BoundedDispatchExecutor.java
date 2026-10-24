package io.tracepermit.adapters.http;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Each lane owns at most one task, including the handoff before its worker wakes.
 * Only after all lanes are reserved can work occupy the bounded pending FIFO.
 * All reservation, promotion, shutdown and release decisions use one monitor;
 * no handler, task or rejection observer runs under that monitor.
 */
final class BoundedDispatchExecutor extends AbstractExecutorService {
  record Occupancy(int reserved, int pending) {}

  @FunctionalInterface
  interface RejectionObserver {
    void rejected(boolean qualificationTask, Occupancy occupancy);
  }

  private static final RejectionObserver NO_OBSERVER = (qualificationTask, occupancy) -> {};
  private final Object lock = new Object();
  private final Lane[] lanes;
  private final ArrayDeque<Runnable> pending = new ArrayDeque<>();
  private final int pendingBound;
  private int reserved;
  private int exited;
  private boolean shutdown;
  private boolean stopping;
  private RejectionObserver observer = NO_OBSERVER;

  BoundedDispatchExecutor(int activeLanes, int pendingBound, ThreadFactory factory) {
    ApprovalAdmissionExecutors.validate(activeLanes, pendingBound);
    Objects.requireNonNull(factory, "Thread factory must not be null.");
    this.pendingBound = pendingBound;
    lanes = new Lane[activeLanes];
    var uniqueThreads = new IdentityHashMap<Thread, Boolean>();
    for (int i = 0; i < activeLanes; i++) {
      var lane = new Lane();
      lane.thread = Objects.requireNonNull(factory.newThread(() -> work(lane)), "Dispatch worker is required.");
      if (lane.thread.getState() != Thread.State.NEW || uniqueThreads.put(lane.thread, true) != null) {
        throw new IllegalArgumentException("Dispatch workers must be distinct new threads.");
      }
      lane.thread.setDaemon(true);
      lane.thread.setName("approval-dispatch-" + (i + 1));
      lanes[i] = lane;
    }
    int started = 0;
    try {
      for (var lane : lanes) {
        lane.thread.start();
        started++;
      }
    } catch (Throwable startupFailure) {
      synchronized (lock) {
        exited += lanes.length - started;
      }
      shutdownNow();
      throw new IllegalStateException("DISPATCH_WORKER_START_FAILED");
    }
  }

  @Override public void execute(Runnable task) {
    Objects.requireNonNull(task, "Dispatch task must not be null.");
    RejectionObserver rejected;
    Occupancy occupancy;
    synchronized (lock) {
      if (!shutdown) {
        for (var lane : lanes) {
          if (lane.task == null) {
            lane.task = task;
            reserved++;
            lock.notifyAll();
            return;
          }
        }
        if (pending.size() < pendingBound) {
          pending.addLast(task);
          return;
        }
      }
      rejected = observer;
      occupancy = occupancyLocked();
    }
    try {
      rejected.rejected(task instanceof java.util.concurrent.RunnableFuture<?>, occupancy);
    } catch (Throwable observationFailure) {
      // Observation cannot suppress refusal or expose its throwable to callers.
    }
    throw new RejectedExecutionException("Approval dispatch capacity unavailable.");
  }

  private void work(Lane lane) {
    try {
      while (true) {
        Runnable task;
        synchronized (lock) {
          while (lane.task == null && !shutdown) {
            try {
              lock.wait();
            } catch (InterruptedException interrupted) {
              // As with a fixed pool, an idle interrupt is not a shutdown request.
            }
          }
          if (stopping || lane.task == null) return;
          lane.running = true;
          task = lane.task;
        }
        try {
          task.run();
        } catch (Throwable taskFailure) {
          // A failed task is never retried or reported as a successful exchange.
          // Do not print its potentially sensitive throwable. Keep the lane usable.
        } finally {
          synchronized (lock) {
            lane.running = false;
            lane.task = stopping ? null : pending.pollFirst();
            if (lane.task == null) reserved--;
            lock.notifyAll();
          }
          // A task's interrupt must not leak to the next, unrelated exchange.
          Thread.interrupted();
        }
      }
    } finally {
      synchronized (lock) {
        exited++;
        lock.notifyAll();
      }
    }
  }

  @Override public void shutdown() {
    synchronized (lock) {
      shutdown = true;
      lock.notifyAll();
    }
  }

  @Override public List<Runnable> shutdownNow() {
    List<Runnable> abandoned;
    synchronized (lock) {
      shutdown = true;
      stopping = true;
      abandoned = new ArrayList<>(pending);
      pending.clear();
      for (var lane : lanes) {
        if (lane.task != null && !lane.running) {
          abandoned.add(lane.task);
          lane.task = null;
          reserved--;
        }
      }
      lock.notifyAll();
    }
    for (var lane : lanes) lane.thread.interrupt();
    return abandoned;
  }

  @Override public boolean isShutdown() {
    synchronized (lock) {
      return shutdown;
    }
  }

  @Override public boolean isTerminated() {
    synchronized (lock) {
      return shutdown && exited == lanes.length;
    }
  }

  @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    Objects.requireNonNull(unit, "Time unit must not be null.");
    long remaining = unit.toNanos(timeout);
    synchronized (lock) {
      long started = System.nanoTime();
      while (!isTerminated()) {
        if (remaining <= 0) return false;
        TimeUnit.NANOSECONDS.timedWait(lock, remaining);
        long now = System.nanoTime();
        remaining -= Math.max(0, now - started);
        started = now;
      }
      return true;
    }
  }

  Occupancy occupancy() {
    synchronized (lock) {
      return occupancyLocked();
    }
  }

  private Occupancy occupancyLocked() {
    return new Occupancy(reserved, pending.size());
  }

  int activeLanes() { return lanes.length; }

  RejectionObserver rejectionObserver() {
    synchronized (lock) {
      return observer;
    }
  }

  void replaceRejectionObserver(RejectionObserver expected, RejectionObserver replacement) {
    Objects.requireNonNull(replacement, "Rejection observer must not be null.");
    synchronized (lock) {
      if (observer != expected) throw new IllegalStateException("ADMISSION_DIAGNOSTIC_CHANGED");
      observer = replacement;
    }
  }

  private static final class Lane {
    private Thread thread;
    private Runnable task;
    private boolean running;
  }
}
