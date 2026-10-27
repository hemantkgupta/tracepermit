package io.tracepermit.agentcore.httpcli;

import io.tracepermit.agentcore.cli.InstalledHttpRuntime;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Internal worker. It owns one closed HTTP session and no alternative runtime implementation. */
public final class InstalledHttpWorker {
  private static final Duration OUTPUT = Duration.ofSeconds(1);

  private InstalledHttpWorker() {}

  public static void main(String[] args) {
    System.setProperty("mariadb.logging.disable", "true");
    java.util.logging.LogManager.getLogManager().reset();
    String command = args.length == 1 ? args[0] : "";
    SessionReceipt finalReceipt = SessionReceipt.failure("UNKNOWN");
    int exit = 1;
    try {
      if (!command.equals("check-session") && !command.equals("serve-bounded")) {
        finalReceipt = SessionReceipt.failure("USAGE");
        exit = 64;
      } else if (!InstalledHttpRuntime.anonymousInput()) {
        finalReceipt = SessionReceipt.failure("INPUT_INVALID");
      } else {
        InstalledHttpRuntime.Plan plan = InstalledHttpRuntime.readHttpPlan(System.in);
        if (command.equals("check-session")) {
          finalReceipt = SessionReceipt.failure("CONFIG_VALID");
          exit = 0;
        } else {
          runSession(plan);
          return;
        }
      }
    } catch (InstalledHttpRuntime.Failure failure) {
      finalReceipt = SessionReceipt.failure(failure.code());
    } catch (Throwable failure) {
      finalReceipt = SessionReceipt.failure("UNKNOWN");
    }
    boolean sent = SessionProtocol.send(System.out, finalReceipt, OUTPUT);
    Runtime.getRuntime().halt(sent ? exit : 1);
  }

  private static void runSession(InstalledHttpRuntime.Plan plan) {
    InstalledHttpRuntime.Session session = null;
    Thread shutdown = null;
    SessionReceipt finalReceipt = null;
    int exit = 1;
    try {
      session = plan.start();
      InstalledHttpRuntime.Session owned = session;
      shutdown = new Thread(owned::close, "registration-http-worker-shutdown");
      Runtime.getRuntime().addShutdownHook(shutdown);
      long deadline = System.nanoTime() + plan.sessionDuration().toNanos();
      if (!SessionProtocol.send(System.out, SessionReceipt.ready(session.port()), OUTPUT)) return;
      while (System.nanoTime() < deadline) {
        long remaining = deadline - System.nanoTime();
        try {
          TimeUnit.NANOSECONDS.sleep(Math.max(1, remaining));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          finalReceipt = SessionReceipt.finalFor("UNKNOWN", session.port());
          return;
        }
      }
      session.close();
      Runtime.getRuntime().removeShutdownHook(shutdown);
      shutdown = null;
      finalReceipt = SessionReceipt.finalFor("SESSION_ENDED", session.port());
      exit = 0;
    } catch (InstalledHttpRuntime.Failure failure) {
      finalReceipt = SessionReceipt.failure(failure.code());
    } catch (Throwable failure) {
      finalReceipt =
          session == null
              ? SessionReceipt.failure("UNKNOWN")
              : SessionReceipt.finalFor("UNKNOWN", session.port());
    } finally {
      if (session != null && shutdown != null) session.close();
      if (finalReceipt != null) {
        boolean sent = SessionProtocol.send(System.out, finalReceipt, OUTPUT);
        Runtime.getRuntime().halt(sent ? exit : 1);
      }
      Runtime.getRuntime().halt(1);
    }
  }
}
