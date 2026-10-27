package io.tracepermit.agentcore.cli;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real JDBC TCP fixture: forward the commit, hold its reply, observe durability independently. */
final class CommitGate implements AutoCloseable {
  final ServerSocket listener;
  final CountDownLatch held = new CountDownLatch(1);
  private final CountDownLatch release = new CountDownLatch(1);
  private final List<Socket> sockets = new CopyOnWriteArrayList<>();
  private final List<Thread> threads = new CopyOnWriteArrayList<>();
  private final AtomicBoolean closing = new AtomicBoolean();

  CommitGate(int targetPort, boolean replay) throws Exception {
    listener = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
    String insert = replay ? "INSERT INTO TP_WORKLOAD_REPLAY_CLAIMS" : "INSERT INTO TP_BUSINESS_OPERATIONS";
    launch(() -> {
      try {
        while (!closing.get()) {
          Socket client = listener.accept(); client.setSoTimeout(15_000); sockets.add(client);
          Socket database = new Socket(); database.connect(new java.net.InetSocketAddress("127.0.0.1", targetPort), 2000);
          database.setSoTimeout(15_000); sockets.add(database);
          var writes = new AtomicBoolean(); var commit = new AtomicBoolean();
          launch(() -> forward(client, database, writes, commit, insert, true));
          launch(() -> forward(database, client, writes, commit, insert, false));
        }
      } catch (Exception expectedOnClose) { }
    });
  }

  int port() { return listener.getLocalPort(); }

  private void forward(Socket source, Socket destination, AtomicBoolean writes, AtomicBoolean commit, String insert, boolean request) {
    try {
      var input = source.getInputStream(); var output = destination.getOutputStream();
      byte[] buffer = new byte[65_536]; int size;
      String tail = "";
      while ((size = input.read(buffer)) != -1) {
        if (request) {
          // Only an ephemeral marker window; never log, assert, retain or publish wire data.
          String marker = tail + new String(buffer, 0, size, StandardCharsets.ISO_8859_1).toUpperCase(java.util.Locale.ROOT);
          if (marker.contains(insert)) writes.set(true);
          // Exclude AUTOCOMMIT and @@innodb_flush_log_at_trx_commit metadata queries.
          if (writes.get() && marker.matches("(?s).*(?:^|[^A-Z_])COMMIT(?:[^A-Z_]|$).*")) commit.set(true);
          tail = marker.substring(Math.max(0, marker.length() - 64));
        } else if (commit.get()) {
          held.countDown();
          if (!release.await(10, TimeUnit.SECONDS)) return;
        }
        output.write(buffer, 0, size); output.flush();
      }
    } catch (Exception expectedFault) { }
    finally { try { source.close(); } catch (Exception ignored) { } try { destination.close(); } catch (Exception ignored) { } }
  }

  private void launch(Runnable action) {
    Thread thread = new Thread(action, "cli-commit-gate"); thread.setDaemon(true); threads.add(thread); thread.start();
  }

  void loseAcknowledgement() {
    for (Socket socket : sockets) try { socket.close(); } catch (Exception ignored) { }
    release.countDown();
  }

  @Override public void close() {
    closing.set(true); try { listener.close(); } catch (Exception ignored) { }
    loseAcknowledgement();
    long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos(); boolean interrupted = false;
    for (Thread thread : threads) {
      try { thread.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))); }
      catch (InterruptedException stop) { interrupted = true; }
      if (thread.isAlive()) throw new AssertionError("PROXY_THREAD_NOT_REAPED");
    }
    if (interrupted) Thread.currentThread().interrupt();
  }
}
