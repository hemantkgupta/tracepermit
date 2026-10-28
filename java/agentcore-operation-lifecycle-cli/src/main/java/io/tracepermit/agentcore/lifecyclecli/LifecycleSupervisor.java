package io.tracepermit.agentcore.lifecyclecli;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Finite owner for exactly one lifecycle worker and its bounded pipes. */
final class LifecycleSupervisor {
  static final Duration TOTAL = Duration.ofSeconds(60);
  static final Duration CLEANUP = Duration.ofSeconds(5);
  private static final Duration GRACEFUL = Duration.ofMillis(100);
  private final AtomicReference<Process> owned = new AtomicReference<>();

  LifecycleReceipt run(ProcessBuilder builder, Duration total, Duration cleanup) {
    long deadline = System.nanoTime() + total.toNanos();
    Process child = null;
    Thread outputThread = null;
    Thread errorThread = null;
    boolean interrupted = false;
    LifecycleReceipt result = LifecycleReceipt.failure("UNKNOWN", "UNKNOWN");
    try {
      var expired = new AtomicBoolean();
      var failed = new AtomicBoolean();
      Thread starter =
          daemon(
              "operation-lifecycle-start",
              () -> {
                try {
                  Process process = builder.start();
                  owned.set(process);
                  if (expired.get()) terminate(process, cleanup, null, null);
                } catch (Exception failure) {
                  failed.set(true);
                }
              });
      try {
        starter.join(remainingMillis(deadline));
      } finally {
        expired.set(true);
      }
      child = owned.get();
      if (starter.isAlive() || failed.get() || child == null || System.nanoTime() >= deadline) {
        return result;
      }
      var output = new Capture(child.getInputStream(), LifecycleProtocol.RECEIPT_BYTES);
      var error = new Capture(child.getErrorStream(), LifecycleProtocol.RECEIPT_BYTES);
      outputThread = daemon("operation-lifecycle-stdout", output);
      errorThread = daemon("operation-lifecycle-stderr", error);
      while (child.isAlive()) {
        if (output.failed.get() || error.failed.get() || System.nanoTime() >= deadline) {
          return result;
        }
        child.waitFor(Math.min(25, remainingMillis(deadline)), TimeUnit.MILLISECONDS);
      }
      outputThread.join(remainingMillis(deadline));
      errorThread.join(remainingMillis(deadline));
      if (outputThread.isAlive()
          || errorThread.isAlive()
          || output.failed.get()
          || error.failed.get()
          || error.bytes.size() != 0
          || System.nanoTime() >= deadline) {
        return result;
      }
      result = LifecycleReceipt.decode(output.bytes.toByteArray(), child.exitValue());
    } catch (InterruptedException stop) {
      interrupted = true;
    } catch (RuntimeException failure) {
      result = LifecycleReceipt.failure("UNKNOWN", "UNKNOWN");
    } finally {
      boolean reaped = terminate(child == null ? owned.get() : child, cleanup, outputThread, errorThread);
      if (interrupted) Thread.currentThread().interrupt();
      if (!reaped || interrupted) result = LifecycleReceipt.failure("UNKNOWN", "UNKNOWN");
    }
    return result;
  }

  void stop() {
    terminate(owned.get(), CLEANUP, null, null);
  }

  static boolean send(OutputStream target, byte[] bytes, Duration budget) {
    var failed = new AtomicBoolean();
    Thread writer =
        daemon(
            "operation-lifecycle-receipt",
            () -> {
              try {
                target.write(bytes);
                target.flush();
                if (target instanceof java.io.PrintStream print && print.checkError()) {
                  failed.set(true);
                }
              } catch (Exception error) {
                failed.set(true);
              }
            });
    try {
      writer.join(Math.max(1, budget.toMillis()));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    }
    return !writer.isAlive() && !failed.get();
  }

  private static long remainingMillis(long deadline) {
    return Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
  }

  private static Thread daemon(String name, Runnable runnable) {
    Thread thread = new Thread(runnable, name);
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  private static boolean terminate(
      Process process, Duration reserve, Thread output, Thread error) {
    if (process == null) return true;
    boolean interrupted = Thread.interrupted();
    long deadline = System.nanoTime() + reserve.toNanos();
    Thread closer;
    try {
      process.toHandle().destroy();
      interrupted |= awaitExit(process, Math.min(deadline, System.nanoTime() + GRACEFUL.toNanos()));
      if (process.isAlive()) {
        process.toHandle().destroyForcibly();
        interrupted |= awaitExit(process, deadline);
      }
    } finally {
      closer =
          daemon(
              "operation-lifecycle-pipe-cleanup",
              () -> {
                try {
                  process.getOutputStream().close();
                } catch (Exception ignored) {
                  // Fixed cleanup result below.
                }
                try {
                  process.getInputStream().close();
                } catch (Exception ignored) {
                  // Fixed cleanup result below.
                }
                try {
                  process.getErrorStream().close();
                } catch (Exception ignored) {
                  // Fixed cleanup result below.
                }
              });
      interrupted |= join(closer, deadline);
      interrupted |= join(output, deadline);
      interrupted |= join(error, deadline);
      if (interrupted) Thread.currentThread().interrupt();
    }
    return !process.isAlive() && done(closer) && done(output) && done(error);
  }

  private static boolean done(Thread thread) {
    return thread == null || !thread.isAlive();
  }

  private static boolean awaitExit(Process process, long deadline) {
    boolean interrupted = false;
    while (process.isAlive() && System.nanoTime() < deadline) {
      try {
        if (process.waitFor(remainingMillis(deadline), TimeUnit.MILLISECONDS)) break;
      } catch (InterruptedException stop) {
        interrupted = true;
      }
    }
    return interrupted;
  }

  private static boolean join(Thread thread, long deadline) {
    if (thread == null) return false;
    boolean interrupted = false;
    while (thread.isAlive() && System.nanoTime() < deadline) {
      try {
        thread.join(remainingMillis(deadline));
      } catch (InterruptedException stop) {
        interrupted = true;
      }
    }
    return interrupted;
  }

  private static final class Capture implements Runnable {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    final AtomicBoolean failed = new AtomicBoolean();
    private final InputStream source;
    private final int maximum;

    Capture(InputStream source, int maximum) {
      this.source = source;
      this.maximum = maximum;
    }

    @Override
    public void run() {
      try {
        byte[] buffer = new byte[256];
        int count;
        while ((count = source.read(buffer)) != -1) {
          if (count > maximum - bytes.size()) {
            failed.set(true);
            return;
          }
          bytes.write(buffer, 0, count);
        }
      } catch (Exception failure) {
        failed.set(true);
      }
    }
  }
}
