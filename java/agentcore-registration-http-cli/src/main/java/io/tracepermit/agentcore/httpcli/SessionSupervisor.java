package io.tracepermit.agentcore.httpcli;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Streaming two-receipt supervisor. It owns and terminates exactly one worker process. */
final class SessionSupervisor {
  static final Duration TOTAL = Duration.ofSeconds(60);
  static final Duration CLEANUP = Duration.ofSeconds(5);
  static final Duration READY = Duration.ofSeconds(20);
  static final Duration OUTPUT = Duration.ofSeconds(1);
  private static final Duration GRACEFUL = Duration.ofMillis(1_500);
  private final AtomicReference<Process> owned = new AtomicReference<>();

  Process ownedProcess() {
    return owned.get();
  }

  int run(ProcessBuilder builder, String command, OutputStream target, Duration total, Duration cleanup) {
    long started = System.nanoTime();
    long deadline = started + total.toNanos();
    Process child = null;
    OutputReader stdout = null;
    Capture stderr = null;
    Thread outThread = null;
    Thread errThread = null;
    boolean readySent = false;
    Integer readyPort = null;
    boolean interrupted = false;
    try {
      var expired = new AtomicBoolean();
      var startFailure = new AtomicBoolean();
      Thread starter =
          SessionProtocol.daemon(
              "registration-http-start",
              () -> {
                try {
                  Process process = builder.start();
                  owned.set(process);
                  if (expired.get()) terminate(process, cleanup, null, null);
                } catch (Exception failure) {
                  startFailure.set(true);
                }
              });
      try {
        starter.join(remainingMillis(deadline));
      } finally {
        expired.set(true);
      }
      child = owned.get();
      if (starter.isAlive() || startFailure.get() || child == null || System.nanoTime() >= deadline) {
        return sendFailure(target, SessionReceipt.failure("UNKNOWN"), deadline);
      }

      stdout = new OutputReader(child.getInputStream());
      stderr = new Capture(child.getErrorStream(), 1_024);
      outThread = SessionProtocol.daemon("registration-http-stdout", stdout);
      errThread = SessionProtocol.daemon("registration-http-stderr", stderr);

      long firstDeadline = command.equals("serve-bounded") ? Math.min(deadline, started + READY.toNanos()) : deadline;
      SessionReceipt first = awaitReceipt(stdout, stderr, child, firstDeadline);
      if (first == null) return sendFailure(target, SessionReceipt.failure("UNKNOWN"), deadline);

      if (command.equals("serve-bounded") && first.phase().equals("READY")) {
        if (!first.outcome().equals("LISTENING") || !send(target, first, deadline)) return 1;
        readySent = true;
        readyPort = first.port();
        SessionReceipt second = awaitReceipt(stdout, stderr, child, deadline);
        boolean complete =
            second != null
                && second.phase().equals("FINAL")
                && awaitExit(child, deadline)
                && join(outThread, deadline)
                && join(errThread, deadline)
                && stdout.clean(2)
                && stderr.clean()
                && child.exitValue() == second.exitCode();
        SessionReceipt result =
            complete && second.outcome().equals("SESSION_ENDED")
                ? second
                : SessionReceipt.finalFor("UNKNOWN", readyPort);
        if (!send(target, result, deadline)) return 1;
        return result.exitCode();
      }

      boolean complete =
          first.phase().equals("FINAL")
              && awaitExit(child, deadline)
              && join(outThread, deadline)
              && join(errThread, deadline)
              && stdout.clean(1)
              && stderr.clean()
              && child.exitValue() == first.exitCode();
      SessionReceipt result =
          complete && (!command.equals("serve-bounded") || first.exitCode() != 0)
              ? first
              : SessionReceipt.failure("UNKNOWN");
      if (!send(target, result, deadline)) return 1;
      return result.exitCode();
    } catch (InterruptedException stop) {
      interrupted = true;
      if (readySent && readyPort != null) {
        send(target, SessionReceipt.finalFor("UNKNOWN", readyPort), deadline);
      } else {
        send(target, SessionReceipt.failure("UNKNOWN"), deadline);
      }
      return 1;
    } catch (RuntimeException failure) {
      if (readySent && readyPort != null) {
        send(target, SessionReceipt.finalFor("UNKNOWN", readyPort), deadline);
      } else {
        send(target, SessionReceipt.failure("UNKNOWN"), deadline);
      }
      return 1;
    } finally {
      boolean reaped = terminate(child == null ? owned.get() : child, cleanup, outThread, errThread);
      if (interrupted) Thread.currentThread().interrupt();
      if (!reaped && readySent && readyPort != null) {
        // The public final receipt, if any, was already UNKNOWN. Never synthesize success here.
      }
    }
  }

  void stop() {
    terminate(owned.get(), CLEANUP, null, null);
  }

  private static SessionReceipt awaitReceipt(
      OutputReader stdout, Capture stderr, Process child, long deadline) throws InterruptedException {
    while (System.nanoTime() < deadline) {
      SessionReceipt receipt = stdout.receipts.poll(Math.min(25, remainingMillis(deadline)), TimeUnit.MILLISECONDS);
      if (receipt != null) return receipt;
      if (stdout.failed.get() || stderr.failed.get() || stderr.bytes.size() != 0) return null;
      if (!child.isAlive() && stdout.done.get() && stdout.receipts.isEmpty()) return null;
    }
    return null;
  }

  private static boolean send(OutputStream target, SessionReceipt receipt, long deadline) {
    long remaining = deadline - System.nanoTime();
    if (remaining <= 0) return false;
    return SessionProtocol.send(
        target, receipt, Duration.ofNanos(Math.min(OUTPUT.toNanos(), remaining)));
  }

  private static int sendFailure(OutputStream target, SessionReceipt receipt, long deadline) {
    return send(target, receipt, deadline) ? receipt.exitCode() : 1;
  }

  private static boolean awaitExit(Process process, long deadline) throws InterruptedException {
    while (process.isAlive() && System.nanoTime() < deadline) {
      if (process.waitFor(remainingMillis(deadline), TimeUnit.MILLISECONDS)) break;
    }
    return !process.isAlive();
  }

  private static boolean join(Thread thread, long deadline) throws InterruptedException {
    if (thread == null) return true;
    while (thread.isAlive() && System.nanoTime() < deadline) thread.join(remainingMillis(deadline));
    return !thread.isAlive();
  }

  private static long remainingMillis(long deadline) {
    return Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
  }

  private static boolean terminate(Process process, Duration reserve, Thread out, Thread err) {
    if (process == null) return true;
    boolean interrupted = Thread.interrupted();
    long deadline = System.nanoTime() + reserve.toNanos();
    Thread closer = null;
    try {
      process.toHandle().destroy();
      interrupted |= awaitExitPreserving(process, Math.min(deadline, System.nanoTime() + GRACEFUL.toNanos()));
      if (process.isAlive()) {
        process.toHandle().destroyForcibly();
        interrupted |= awaitExitPreserving(process, deadline);
      }
    } finally {
      Process ownedProcess = process;
      closer =
          SessionProtocol.daemon(
              "registration-http-pipe-cleanup",
              () -> {
                try {
                  ownedProcess.getOutputStream().close();
                } catch (Exception ignored) {
                }
                try {
                  ownedProcess.getInputStream().close();
                } catch (Exception ignored) {
                }
                try {
                  ownedProcess.getErrorStream().close();
                } catch (Exception ignored) {
                }
              });
      interrupted |= joinPreserving(closer, deadline);
      interrupted |= joinPreserving(out, deadline);
      interrupted |= joinPreserving(err, deadline);
      if (interrupted) Thread.currentThread().interrupt();
    }
    return !process.isAlive() && done(closer) && done(out) && done(err);
  }

  private static boolean awaitExitPreserving(Process process, long deadline) {
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

  private static boolean joinPreserving(Thread thread, long deadline) {
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

  private static boolean done(Thread thread) {
    return thread == null || !thread.isAlive();
  }

  private static final class OutputReader implements Runnable {
    final ArrayBlockingQueue<SessionReceipt> receipts = new ArrayBlockingQueue<>(3);
    final AtomicBoolean failed = new AtomicBoolean();
    final AtomicBoolean done = new AtomicBoolean();
    private final InputStream source;
    private int count;

    OutputReader(InputStream source) {
      this.source = source;
    }

    @Override
    public void run() {
      try {
        while (true) {
          Optional<SessionReceipt> receipt = SessionProtocol.readFrame(source);
          if (receipt.isEmpty()) return;
          count++;
          if (count > 2 || !receipts.offer(receipt.orElseThrow())) {
            failed.set(true);
            return;
          }
        }
      } catch (RuntimeException failure) {
        failed.set(true);
      } finally {
        done.set(true);
      }
    }

    boolean clean(int expected) {
      return done.get() && !failed.get() && count == expected && receipts.isEmpty();
    }
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
        int read;
        while ((read = source.read(buffer)) != -1) {
          if (read > maximum - bytes.size()) {
            failed.set(true);
            return;
          }
          bytes.write(buffer, 0, read);
        }
      } catch (Exception failure) {
        failed.set(true);
      }
    }

    boolean clean() {
      return !failed.get() && bytes.size() == 0;
    }
  }
}
