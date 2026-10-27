package io.tracepermit.agentcore.httpcli;

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

/** Real JDBC TCP fixture: forward commits, then hold their replies. */
final class CommitGate implements AutoCloseable {
  final CountDownLatch held;
  private final ServerSocket listener;
  private final CountDownLatch release = new CountDownLatch(1);
  private final List<Socket> sockets = new CopyOnWriteArrayList<>();
  private final List<Thread> threads = new CopyOnWriteArrayList<>();
  private final AtomicBoolean closing = new AtomicBoolean();
  private final String insert;
  private final int targetPort;

  CommitGate(int targetPort, boolean replay, int expected) throws Exception {
    this.targetPort = targetPort;
    this.insert =
        replay ? "INSERT INTO TP_WORKLOAD_REPLAY_CLAIMS" : "INSERT INTO TP_BUSINESS_OPERATIONS";
    held = new CountDownLatch(expected);
    listener = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"));
    launch(this::accept);
  }

  int port() {
    return listener.getLocalPort();
  }

  void releaseAcknowledgements() {
    release.countDown();
  }

  void loseAcknowledgements() {
    for (Socket socket : sockets) {
      try {
        socket.close();
      } catch (Exception ignored) {
      }
    }
    release.countDown();
  }

  private void accept() {
    try {
      while (!closing.get()) {
        Socket client = listener.accept();
        client.setSoTimeout(30_000);
        sockets.add(client);
        Socket database = new Socket();
        database.connect(new java.net.InetSocketAddress("127.0.0.1", targetPort), 2_000);
        database.setSoTimeout(30_000);
        sockets.add(database);
        var writes = new AtomicBoolean();
        var commit = new AtomicBoolean();
        launch(() -> forward(client, database, writes, commit, true));
        launch(() -> forward(database, client, writes, commit, false));
      }
    } catch (Exception expectedOnClose) {
    }
  }

  private void forward(
      Socket source,
      Socket destination,
      AtomicBoolean writes,
      AtomicBoolean commit,
      boolean request) {
    try {
      var input = source.getInputStream();
      var output = destination.getOutputStream();
      byte[] buffer = new byte[65_536];
      int size;
      String tail = "";
      while ((size = input.read(buffer)) != -1) {
        if (request) {
          String marker =
              tail
                  + new String(buffer, 0, size, StandardCharsets.ISO_8859_1)
                      .toUpperCase(java.util.Locale.ROOT);
          if (marker.contains(insert)) writes.set(true);
          if (writes.get()
              && marker.matches("(?s).*(?:^|[^A-Z_])COMMIT(?:[^A-Z_]|$).*")) {
            commit.set(true);
          }
          tail = marker.substring(Math.max(0, marker.length() - 64));
        } else if (commit.get()) {
          held.countDown();
          if (!release.await(25, TimeUnit.SECONDS)) return;
        }
        output.write(buffer, 0, size);
        output.flush();
      }
    } catch (Exception expectedFault) {
    } finally {
      try {
        source.close();
      } catch (Exception ignored) {
      }
      try {
        destination.close();
      } catch (Exception ignored) {
      }
    }
  }

  private void launch(Runnable action) {
    Thread thread = new Thread(action, "http-cli-commit-gate");
    thread.setDaemon(true);
    threads.add(thread);
    thread.start();
  }

  @Override
  public void close() {
    closing.set(true);
    try {
      listener.close();
    } catch (Exception ignored) {
    }
    loseAcknowledgements();
    long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
    boolean interrupted = false;
    for (Thread thread : threads) {
      try {
        thread.join(
            Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
      } catch (InterruptedException stop) {
        interrupted = true;
      }
      if (thread.isAlive()) throw new AssertionError("PROXY_THREAD_NOT_REAPED");
    }
    if (interrupted) Thread.currentThread().interrupt();
  }
}
