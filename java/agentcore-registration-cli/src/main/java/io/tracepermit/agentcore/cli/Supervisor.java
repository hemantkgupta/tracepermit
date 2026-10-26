package io.tracepermit.agentcore.cli;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Every blocking pipe operation belongs to a daemon; only the owned child is terminated. */
final class Supervisor {
  static final Duration TOTAL = Duration.ofSeconds(60);
  static final Duration CLEANUP = Duration.ofSeconds(5);
  private final AtomicReference<Process> owned = new AtomicReference<>();

  Process ownedProcess() { return owned.get(); }

  Receipt run(ProcessBuilder builder, Duration total, Duration cleanup) {
    long deadline = System.nanoTime() + total.toNanos();
    Process child = null;
    Thread out = null;
    Thread err = null;
    boolean interrupted = false;
    Receipt result = Receipt.failure("UNKNOWN");
    try {
      // Process creation itself has platform limits. The creation daemon retains ownership even
      // if creation returns after the command deadline, and immediately kills that late child.
      var expired = new AtomicBoolean();
      var startFailure = new AtomicBoolean();
      Thread starter = daemon("registration-start", () -> {
        try {
          Process process = builder.start();
          owned.set(process);
          if (expired.get()) terminate(process, cleanup, null, null);
        } catch (Exception failure) { startFailure.set(true); }
      });
      try {
        starter.join(remainingMillis(deadline));
      } finally { expired.set(true); }
      child = owned.get();
      if (starter.isAlive() || startFailure.get() || child == null || System.nanoTime() >= deadline)
        return Receipt.failure("UNKNOWN");
      var stdout = new Capture(child.getInputStream(), 1_024);
      var stderr = new Capture(child.getErrorStream(), 1_024);
      out = daemon("registration-stdout", stdout);
      err = daemon("registration-stderr", stderr);
      while (child.isAlive()) {
        if (stdout.failed.get() || stderr.failed.get() || System.nanoTime() >= deadline)
          return Receipt.failure("UNKNOWN");
        child.waitFor(Math.min(25, remainingMillis(deadline)), TimeUnit.MILLISECONDS);
      }
      out.join(remainingMillis(deadline)); err.join(remainingMillis(deadline));
      if (out.isAlive() || err.isAlive() || stdout.failed.get() || stderr.failed.get()
          || stderr.bytes.size() != 0 || System.nanoTime() >= deadline)
        return Receipt.failure("UNKNOWN");
      result = Receipt.decode(stdout.bytes.toByteArray(), child.exitValue());
    } catch (InterruptedException stop) {
      interrupted = true;
      result = Receipt.failure("UNKNOWN");
    } catch (RuntimeException failure) {
      result = Receipt.failure("UNKNOWN");
    } finally {
      boolean reaped = terminate(child == null ? owned.get() : child, cleanup, out, err);
      if (interrupted) Thread.currentThread().interrupt();
      if (!reaped || interrupted) {
        result = Receipt.failure("UNKNOWN");
      }
    }
    return result;
  }

  void stop() { terminate(owned.get(), CLEANUP, null, null); }

  static boolean send(OutputStream target, byte[] bytes, Duration budget) {
    var failed = new AtomicBoolean();
    Thread writer = daemon("registration-receipt", () -> {
      try {
        target.write(bytes); target.flush();
        if (target instanceof java.io.PrintStream print && print.checkError()) failed.set(true);
      }
      catch (Exception error) { failed.set(true); }
    });
    try { writer.join(Math.max(1, budget.toMillis())); }
    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
    return !writer.isAlive() && !failed.get();
  }

  private static long remainingMillis(long deadline) {
    return Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
  }

  private static Thread daemon(String name, Runnable runnable) {
    Thread thread = new Thread(runnable, name); thread.setDaemon(true); thread.start(); return thread;
  }

  private static boolean terminate(Process process, Duration reserve, Thread out, Thread err) {
    if (process == null) return true;
    boolean interrupted = Thread.interrupted();
    long deadline = System.nanoTime() + reserve.toNanos();
    Thread closer = null;
    try {
      // Never close a blocked child pipe before terminating the child.
      // Process.destroy also closes pipes internally. Use the handle to signal before any close.
      process.toHandle().destroy();
      long graceDeadline = Math.min(deadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100));
      while (process.isAlive() && System.nanoTime() < graceDeadline) {
        try {
          if (process.waitFor(remainingMillis(graceDeadline), TimeUnit.MILLISECONDS)) break;
        } catch (InterruptedException stop) {
          interrupted = true;
          break;
        }
      }
      if (process.isAlive()) {
        process.toHandle().destroyForcibly();
        while (process.isAlive() && System.nanoTime() < deadline) {
          try {
            if (process.waitFor(remainingMillis(deadline), TimeUnit.MILLISECONDS)) break;
          } catch (InterruptedException stop) {
            interrupted = true;
          }
        }
      }
    } finally {
      // Closing is also bounded; platform pipe-close bugs cannot hold the supervisor thread.
      closer = daemon("registration-pipe-cleanup", () -> {
        try { process.getOutputStream().close(); } catch (Exception ignored) { }
        try { process.getInputStream().close(); } catch (Exception ignored) { }
        try { process.getErrorStream().close(); } catch (Exception ignored) { }
      });
      while (closer.isAlive() && System.nanoTime() < deadline) {
        try {
          closer.join(remainingMillis(deadline));
        } catch (InterruptedException stop) {
          interrupted = true;
        }
      }
      if (out != null) {
        while (out.isAlive() && System.nanoTime() < deadline) {
          try {
            out.join(remainingMillis(deadline));
          } catch (InterruptedException stop) {
            interrupted = true;
          }
        }
      }
      if (err != null) {
        while (err.isAlive() && System.nanoTime() < deadline) {
          try {
            err.join(remainingMillis(deadline));
          } catch (InterruptedException stop) {
            interrupted = true;
          }
        }
      }
      if (interrupted) Thread.currentThread().interrupt();
    }
    return !process.isAlive() && (closer == null || !closer.isAlive()) && (out == null || !out.isAlive()) && (err == null || !err.isAlive());
  }

  private static final class Capture implements Runnable {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    final AtomicBoolean failed = new AtomicBoolean();
    private final InputStream source;
    private final int maximum;
    Capture(InputStream source, int maximum) { this.source = source; this.maximum = maximum; }
    @Override public void run() {
      try {
        byte[] buffer = new byte[256]; int count;
        while ((count = source.read(buffer)) != -1) {
          if (count > maximum - bytes.size()) { failed.set(true); return; }
          bytes.write(buffer, 0, count);
        }
      } catch (Exception failure) { failed.set(true); }
    }
  }
}
