package io.tracepermit.agentcore.lifecyclecli;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Test-only plaintext loopback gate. It forwards COMMIT to the real server, then withholds its
 * successful reply ONLY when an independent observer recognizes the selected durable transaction.
 * No SQL, credentials, row payloads or authentication frames are retained or printed. Unexpected
 * TLS/compression/framing fails the test, rather than silently bypassing the fault.
 */
final class LifecycleCommitGate implements AutoCloseable {
  // Lifetime accept budget for ONE gate, including a rejected accept. This is deliberately
  // cumulative: closed sessions never restore admission budget. Diagnostics do not alter it.
  // PROPOSAL ONLY: conditional PostgreSQL measurement budget, NOT a qualified engine contract.
  static final int MAX_CUMULATIVE_CONNECTIONS = 44;
  static final int REFUSAL_RESERVE = 1;
  static final int MAX_ACTIVE_PAIRS = 32;
  static final int MAX_ACTIVE_ADMITTED = MAX_ACTIVE_PAIRS - REFUSAL_RESERVE;
  static final int MAX_SESSION_REFERENCES = MAX_CUMULATIVE_CONNECTIONS + REFUSAL_RESERVE;
  static final int MAX_SOCKET_REFERENCES = 2 * MAX_CUMULATIVE_CONNECTIONS + REFUSAL_RESERVE;
  static final int MAX_GATE_THREAD_REFERENCES = 2 * MAX_CUMULATIVE_CONNECTIONS + 1;
  static final int MAX_EVENT_CHARACTERS = 512;
  private static final int MAX_FRAME = 1_048_576;
  private static final long MAX_BYTES = 64L * 1_048_576;
  static final long MAX_GATE_BYTE_ATTEMPTS = MAX_CUMULATIVE_CONNECTIONS
      * (2 * MAX_BYTES + 3L * MAX_FRAME + 8);
  private final java.util.concurrent.atomic.AtomicLong gateByteAttempts =
      new java.util.concurrent.atomic.AtomicLong();
  private static final byte[] PG_COMMIT = "COMMIT\0".getBytes(StandardCharsets.US_ASCII);
  private final ServerSocket listener;
  private final int upstreamPort;
  private final boolean postgres;
  enum DurablePhase { NOT_STARTED, CLAIMED, LEASE_ONLY, STARTED, RESULT, OTHER }
  record CommitEvidence(boolean eligible, int eventCount, DurablePhase phase) {}
  @FunctionalInterface
  interface UpstreamConnect {
    void connect(Socket socket, int port) throws IOException;
  }

  private final Supplier<CommitEvidence> durableBoundary;
  private final List<Socket> sockets = new CopyOnWriteArrayList<>();
  private final List<Thread> threads = new CopyOnWriteArrayList<>();
  private enum Role { UNCLASSIFIED, POSTGRES_SESSION, POSTGRES_CANCEL, MARIA_SESSION, UNSUPPORTED }
  private enum Phase { ACCEPTED, CONNECTING, NEGOTIATING, RELAY, OBSERVER, HELD, CLOSED }

  private static final class Session {
    final int ordinal;
    final Socket client;
    Socket server;
    Role role = Role.UNCLASSIFIED;
    Phase phase = Phase.ACCEPTED;
    boolean rejected;
    boolean upstreamConnected;
    boolean ownUpstreamPortMatch;
    int commits;
    int rollbacks;
    int mutationReplies;
    int historyProjections;
    int checks;
    int eligible;
    int lastEvents;
    DurablePhase lastDurablePhase = DurablePhase.OTHER;
    boolean closeRecorded;

    Session(int ordinal, Socket client) {
      this.ordinal = ordinal;
      this.client = client;
    }

    boolean closed() {
      return client.isClosed() && (server == null || server.isClosed());
    }
  }

  private final Object diagnosticLock = new Object();
  private final List<Session> sessions = new java.util.ArrayList<>();
  private int peak;
  private int observerChecks;
  private int observerMatches;
  private int selectedOrdinal;
  private int acceptIdleTimeouts;
  private int upstreamConnectTimeouts;
  private int admissionAttempts;
  private int droppedDiagnosticEvents;
  private static final int MAX_DIAGNOSTIC_EVENTS = 5 * MAX_SESSION_REFERENCES + 3;
  private final List<String> diagnosticEvents = new java.util.ArrayList<>();
  private record Failure(String phase, String type, String code) {}

  private final AtomicReference<Failure> failure = new AtomicReference<>();
  private final AtomicBoolean closing = new AtomicBoolean();
  private final AtomicBoolean selected = new AtomicBoolean();
  private final CountDownLatch held = new CountDownLatch(1);
  private final CountDownLatch decision = new CountDownLatch(1);
  private final CountDownLatch applied = new CountDownLatch(1);
  private volatile boolean release;
  private volatile boolean forwarded;
  private volatile boolean dropped;

  LifecycleCommitGate(boolean postgres, int upstreamPort, Supplier<CommitEvidence> durableBoundary)
      throws IOException {
    this(postgres, upstreamPort, durableBoundary,
        (socket, port) -> socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000));
  }

  /** Test-only injection for the upstream connection timeout resource path. */
  LifecycleCommitGate(boolean postgres, int upstreamPort, Supplier<CommitEvidence> durableBoundary,
      UpstreamConnect upstreamConnect) throws IOException {
    this.postgres = postgres;
    this.upstreamPort = upstreamPort;
    this.durableBoundary = durableBoundary;
    listener = new ServerSocket();
    listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 16);
    listener.setSoTimeout(1_000);
    thread(
        () -> {
          int connections = 0;
          while (!closing.get()) {
            boolean acceptedSocket = false;
            try {
              Socket client = listener.accept();
              acceptedSocket = true;
              retainSocket(client);
              Session session = accepted(client);
              synchronized (diagnosticLock) { admissionAttempts++; }
              if (++connections > MAX_CUMULATIVE_CONNECTIONS || !client.getInetAddress().isLoopbackAddress()) {
                synchronized (diagnosticLock) {
                  session.rejected = true;
                  event("REFUSE", session);
                }
                throw new IOException("COMMIT_GATE_CONNECTION_BOUND");
              }
              synchronized (diagnosticLock) {
                long active = sessions.stream().filter(s -> !s.rejected && !s.closed()).count();
                if (active > MAX_ACTIVE_ADMITTED) {
                  session.rejected = true;
                  event("REFUSE", session);
                  throw new IOException("COMMIT_GATE_ACTIVE_BOUND");
                }
                event("ADMIT", session);
              }
              // Forward individual protocol frames promptly, including ReadyForQuery after
              // CommandComplete. Nagle plus delayed ACKs must not manufacture JDBC timeouts.
              client.setTcpNoDelay(true);
              client.setSoTimeout(10_000);
              Socket server = new Socket();
              retainSocket(server);
              synchronized (diagnosticLock) {
                session.server = server;
                session.phase = Phase.CONNECTING;
              }
              upstreamConnect.connect(server, upstreamPort);
              synchronized (diagnosticLock) { session.upstreamConnected = true; }
              server.setTcpNoDelay(true);
              server.setSoTimeout(10_000);
              thread(() -> bridge(client, server, session));
            } catch (java.net.SocketTimeoutException idle) {
              synchronized (diagnosticLock) {
                if (acceptedSocket) upstreamConnectTimeouts++;
                else acceptIdleTimeouts++;
              }
              // Bounded accept wakes to observe close; no connection retry.
            } catch (Exception error) {
              fail("ACCEPT_OR_CONNECT", error);
              return;
            }
          }
        });
  }

  int port() {
    return listener.getLocalPort();
  }

  void awaitHeld() throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
    while (!held.await(20, TimeUnit.MILLISECONDS)) {
      requireHealthy();
      if (System.nanoTime() >= end) throw new AssertionError("COMMIT_REPLY_NOT_INTERCEPTED");
    }
    requireHealthy();
    if (forwarded || dropped) throw new AssertionError("COMMIT_REPLY_NOT_HELD");
  }

  void decide(boolean releaseReply) throws Exception {
    release = releaseReply;
    decision.countDown();
    if (!applied.await(5, TimeUnit.SECONDS)) throw new AssertionError("COMMIT_GATE_NOT_APPLIED");
    requireHealthy();
    if (forwarded != releaseReply || dropped == releaseReply) {
      throw new AssertionError("COMMIT_GATE_WRONG_DISPOSITION");
    }
  }

  void requireHealthy() {
    Failure error = failure.get();
    if (error != null) {
      // Never attach an exception that might contain a driver payload.
      throw new AssertionError("COMMIT_GATE_PROTOCOL_OR_OBSERVER_FAILURE["
          + error.phase() + ":" + error.type() + ":" + error.code() + "]");
    }
  }

  private void bridge(Socket client, Socket server, Session session) {
    try {
      phase(session, Phase.NEGOTIATING);
      if (postgres) {
        // PostgreSQL startup has no tag. SSL/GSS negotiation is one byte, not a backend frame.
        byte[] startup = startup(client.getInputStream());
        int code = ByteBuffer.wrap(startup, 4, 4).getInt();
        int negotiations = 0;
        while (code == 80877103 || code == 80877104) {
          if (++negotiations > 2) throw new IOException("COMMIT_GATE_NEGOTIATION_BOUND");
          send(server, startup);
          int refused = server.getInputStream().read();
          if (refused != 'N') throw new IOException("COMMIT_GATE_ENCRYPTION_UNSUPPORTED");
          send(client, new byte[] {(byte) refused});
          startup = startup(client.getInputStream());
          code = ByteBuffer.wrap(startup, 4, 4).getInt();
        }
        // Classification only. CancelRequest remains refused by the same startup guard; this
        // diagnostic does not add protocol support or exclude control sockets from the budget.
        role(session, code == 196608 ? Role.POSTGRES_SESSION
            : code == 80877102 ? Role.POSTGRES_CANCEL : Role.UNSUPPORTED);
        if (ByteBuffer.wrap(startup, 4, 4).getInt() != 196608) {
          throw new IOException("COMMIT_GATE_STARTUP_UNSUPPORTED");
        }
        send(server, startup);
        phase(session, Phase.RELAY);
        thread(() -> copy(client, server));
        long bytes = 0;
        for (;;) {
          byte[] frame = pgFrame(server.getInputStream());
          bytes = bounded(bytes, frame.length);
          postgresEvidence(frame, session);
          if (frame[0] == 'C' && Arrays.equals(Arrays.copyOfRange(frame, 5, frame.length), PG_COMMIT)
              && intercept(client, server, frame, session)) return;
          send(client, frame);
        }
      } else {
        // MariaDB uses 3-byte little-endian packet lengths and a sequence byte.
        send(client, mariaFrame(server.getInputStream()));
        byte[] handshake = mariaFrame(client.getInputStream());
        if (handshake.length < 8) throw new IOException("COMMIT_GATE_HANDSHAKE_SHORT");
        int flags = (handshake[4] & 255) | ((handshake[5] & 255) << 8);
        if ((flags & (0x0800 | 0x0020)) != 0) {
          throw new IOException("COMMIT_GATE_ENCRYPTION_OR_COMPRESSION_UNSUPPORTED");
        }
        send(server, handshake);
        role(session, Role.MARIA_SESSION);
        phase(session, Phase.RELAY);
        var commit = new AtomicBoolean();
        thread(
            () -> {
              try {
                long bytes = 0;
                for (;;) {
                  byte[] frame = mariaFrame(client.getInputStream());
                  bytes = bounded(bytes, frame.length);
                  if (frame.length >= 5 && frame[3] == 0 && frame[4] == 3) {
                    String sql = new String(frame, 5, frame.length - 5, StandardCharsets.US_ASCII);
                    commit.set(sql.strip().equalsIgnoreCase("COMMIT"));
                  }
                  send(server, frame);
                }
              } catch (EOFException finished) {
                closePair(client, server);
              } catch (Exception error) {
                failUnlessClosed(error, client, server, "MARIA_CLIENT_PACKET");
              }
            });
        long bytes = 0;
        for (;;) {
          byte[] frame = mariaFrame(server.getInputStream());
          bytes = bounded(bytes, frame.length);
          if (commit.getAndSet(false) && frame.length >= 11 && frame[3] == 1 && frame[4] == 0
              && intercept(client, server, frame, session)) return;
          send(client, frame);
        }
      }
    } catch (EOFException finished) {
      closePair(client, server);
    } catch (Exception error) {
      failUnlessClosed(error, client, server, postgres ? "POSTGRES_REPLY" : "MARIA_REPLY");
    }
  }

  private boolean intercept(Socket client, Socket server, byte[] successfulCommit, Session session)
      throws Exception {
    synchronized (diagnosticLock) { session.commits++; }
    if (selected.get()) return false;
    boolean durable;
    try {
      synchronized (diagnosticLock) {
        observerChecks++;
        session.phase = Phase.OBSERVER;
      }
      CommitEvidence evidence = durableBoundary.get();
      durable = evidence.eligible();
      synchronized (diagnosticLock) {
        session.checks++;
        session.lastEvents = evidence.eventCount();
        session.lastDurablePhase = evidence.phase();
        if (durable) session.eligible++;
      }
    } catch (RuntimeException | AssertionError error) {
      fail("DURABLE_OBSERVER", error);
      throw error;
    }
    phase(session, Phase.RELAY);
    if (durable) synchronized (diagnosticLock) { observerMatches++; }
    if (!durable || !selected.compareAndSet(false, true)) {
      return false;
    }
    // The entire successful acknowledgement is still private to this gate. In PG, ReadyForQuery
    // remains behind it on this same ordered stream. In MariaDB the entire OK packet is held.
    synchronized (diagnosticLock) {
      selectedOrdinal = session.ordinal;
      session.phase = Phase.HELD;
      event("SELECT", session);
    }
    held.countDown();
    diagnostic("SELECTED");
    if (!decision.await(5, TimeUnit.SECONDS)) throw new IOException("COMMIT_GATE_HOLD_EXPIRED");
    if (release && !closing.get()) {
      send(client, successfulCommit);
      forwarded = true;
      applied.countDown();
      phase(session, Phase.RELAY);
      return false;
    }
    dropped = true;
    closePair(client, server);
    applied.countDown();
    return true;
  }

  private void copy(Socket from, Socket to) {
    try {
      long bytes = 0;
      byte[] buffer = new byte[8192];
      for (int size; (size = from.getInputStream().read(buffer)) != -1; ) {
        bytes = bounded(bytes, size);
        accountByteAttempt(size);
        to.getOutputStream().write(buffer, 0, size);
        to.getOutputStream().flush();
      }
      closePair(from, to);
    } catch (Exception error) {
      failUnlessClosed(error, from, to, "CLIENT_FORWARD");
    }
  }

  static long bounded(long used, int next) throws IOException {
    if (used + next > MAX_BYTES) throw new IOException("COMMIT_GATE_BYTE_BOUND");
    return used + next;
  }

  static byte[] startup(InputStream input) throws IOException {
    byte[] header = exact(input, 4);
    int length = ByteBuffer.wrap(header).getInt();
    if (length < 8 || length > MAX_FRAME) throw new IOException("COMMIT_GATE_STARTUP_BOUND");
    return join(header, exact(input, length - 4));
  }

  static byte[] pgFrame(InputStream input) throws IOException {
    byte[] header = exact(input, 5);
    int length = ByteBuffer.wrap(header, 1, 4).getInt();
    if (length < 4 || length > MAX_FRAME) throw new IOException("COMMIT_GATE_PG_FRAME_BOUND");
    return join(header, exact(input, length - 4));
  }

  private static byte[] mariaFrame(InputStream input) throws IOException {
    byte[] header = exact(input, 4);
    int length = (header[0] & 255) | ((header[1] & 255) << 8) | ((header[2] & 255) << 16);
    if (length < 1 || length > MAX_FRAME) throw new IOException("COMMIT_GATE_MARIA_FRAME_BOUND");
    return join(header, exact(input, length));
  }

  private static byte[] exact(InputStream input, int length) throws IOException {
    byte[] value = input.readNBytes(length);
    if (value.length != length) throw new EOFException();
    return value;
  }

  private static byte[] join(byte[] header, byte[] body) {
    byte[] frame = Arrays.copyOf(header, header.length + body.length);
    System.arraycopy(body, 0, frame, header.length, body.length);
    return frame;
  }

  void accountByteAttempt(int bytes) throws IOException {
    if (gateByteAttempts.addAndGet(bytes) > MAX_GATE_BYTE_ATTEMPTS) {
      throw new IOException("COMMIT_GATE_TOTAL_BYTE_BOUND");
    }
  }

  private void send(Socket socket, byte[] frame) throws IOException {
    accountByteAttempt(frame.length);
    socket.getOutputStream().write(frame);
    socket.getOutputStream().flush();
  }

  private void thread(Runnable action) {
    Thread thread = new Thread(() -> {
      try {
        action.run();
      } catch (Throwable error) {
        fail("GATE_THREAD", error);
      }
    }, "lifecycle-commit-gate");
    thread.setDaemon(true);
    synchronized (diagnosticLock) {
      if (threads.size() == MAX_GATE_THREAD_REFERENCES) {
        throw new IllegalStateException("COMMIT_GATE_THREAD_REFERENCE_BOUND");
      }
      threads.add(thread);
    }
    thread.start();
  }

  private void failUnlessClosed(Exception error, Socket first, Socket second, String phase) {
    if (!first.isClosed() && !second.isClosed()) fail(phase, error);
    closePair(first, second);
  }

  private void fail(String phase, Throwable error) {
    // Only our own constant error codes may reach a diagnostic. Never retain/attach a raw driver
    // exception, SQL message, authentication frame or row payload.
    String message = error.getMessage();
    String code = message != null && message.matches("COMMIT_GATE_[A-Z_]+") ? message : "REDACTED";
    if (!closing.get() && failure.compareAndSet(null,
        new Failure(phase, error.getClass().getSimpleName(), code))) diagnostic("FAILURE");
  }

  void retainSocket(Socket socket) throws IOException {
    synchronized (diagnosticLock) {
      if (sockets.size() == MAX_SOCKET_REFERENCES) {
        socket.close();
        throw new IOException("COMMIT_GATE_SOCKET_REFERENCE_BOUND");
      }
      sockets.add(socket);
    }
  }

  private Session accepted(Socket client) throws IOException {
    synchronized (diagnosticLock) {
      if (sessions.size() == MAX_SESSION_REFERENCES) {
        client.close();
        throw new IOException("COMMIT_GATE_SESSION_REFERENCE_BOUND");
      }
      Session session = new Session(sessions.size() + 1, client);
      // Correlate only live sockets owned by THIS gate; never print address/port/PID values.
      // A match is diagnostic correlation, not proof of application identity. Combine it with
      // routeCollision below before inferring a self-dial loop.
      session.ownUpstreamPortMatch = sessions.stream().anyMatch(s -> s.server != null
          && s.server.isConnected() && !s.server.isClosed()
          && s.server.getLocalPort() == client.getPort());
      sessions.add(session);
      peak = Math.max(peak, (int) sessions.stream().filter(s -> !s.closed()).count());
      event("ACCEPT", session);
      return session;
    }
  }

  /** Direct reference-limit control accepts only already-closed, test-owned sockets. */
  void registerClosedResourceControlSocket(Socket socket) throws IOException {
    if (!socket.isClosed()) throw new IllegalArgumentException("RESOURCE_CONTROL_SOCKET_NOT_CLOSED");
    retainSocket(socket);
    accepted(socket);
  }

  private void phase(Session session, Phase phase) {
    synchronized (diagnosticLock) { session.phase = phase; }
  }

  private void role(Session session, Role role) {
    synchronized (diagnosticLock) {
      session.role = role;
      event("ROLE", session);
    }
  }

  /** No request SQL is inspected: only fixed backend command tags and a closed column-name set. */
  private void postgresEvidence(byte[] frame, Session session) {
    synchronized (diagnosticLock) {
      if (frame[0] == 'C') {
        if (tag(frame, "ROLLBACK")) session.rollbacks++;
        if (tag(frame, "INSERT") || tag(frame, "UPDATE") || tag(frame, "DELETE")
            || tag(frame, "CREATE") || tag(frame, "ALTER") || tag(frame, "DROP")) {
          session.mutationReplies++;
        }
      } else if (frame[0] == 'T' && historyProjection(frame)) {
        session.historyProjections++;
      }
    }
  }

  private static boolean tag(byte[] frame, String expected) {
    if (frame.length <= 5 + expected.length()) return false;
    for (int index = 0; index < expected.length(); index++) {
      if (frame[5 + index] != expected.charAt(index)) return false;
    }
    byte end = frame[5 + expected.length()];
    return end == 0 || end == ' ';
  }

  /** Recognizes a schema-history projection, not a Flyway API/call-stack identity. No data rows. */
  private static boolean historyProjection(byte[] frame) {
    if (frame.length < 7 || ByteBuffer.wrap(frame, 5, 2).getShort() != 10) return false;
    var names = new java.util.HashSet<>(java.util.Set.of(
        "installed_rank", "version", "description", "type", "script", "checksum",
        "installed_by", "installed_on", "execution_time", "success"));
    int offset = 7;
    for (int column = 0; column < 10; column++) {
      int end = offset;
      while (end < frame.length && end - offset < 64 && frame[end] != 0) end++;
      if (end >= frame.length || frame[end] != 0 || end + 19 > frame.length) return false;
      if (!names.remove(new String(frame, offset, end - offset, StandardCharsets.US_ASCII))) {
        return false;
      }
      offset = end + 19; // NUL plus the protocol's 18 fixed descriptor bytes.
    }
    return offset == frame.length && names.isEmpty();
  }

  /** Lock held by callers; fixed event/ordinal/role/phase/counts only, never per-row/query logs. */
  private void event(String kind, Session session) {
    if (diagnosticEvents.size() == MAX_DIAGNOSTIC_EVENTS) {
      droppedDiagnosticEvents++;
      throw new IllegalStateException("COMMIT_GATE_JOURNAL_BOUND");
    }
    String entry = "LIFECYCLE_COMMIT_GATE_EVENT[event=" + kind
        + ",ordinal=" + session.ordinal + ",role=" + session.role
        + ",phase=" + (session.closed() ? Phase.CLOSED : session.phase)
        + ",lastPhase=" + session.phase + ",commits=" + session.commits
        + ",rollbacks=" + session.rollbacks + ",mutationReplies=" + session.mutationReplies
        + ",historyProjections=" + session.historyProjections
        + ",checks=" + session.checks + ",eligible=" + session.eligible
        + ",events=" + session.lastEvents + ",durablePhase=" + session.lastDurablePhase + "]";
    checkEventCharacters(entry);
    diagnosticEvents.add(entry);
  }

  static void checkEventCharacters(String entry) {
    if (entry.length() > MAX_EVENT_CHARACTERS) {
      throw new IllegalStateException("COMMIT_GATE_JOURNAL_CHARACTER_BOUND");
    }
  }

  /** Fixed event only; this control cannot supply diagnostic text or a live session. */
  void recordResourceControlEvent() {
    synchronized (diagnosticLock) {
      if (sessions.size() != 1 || !sessions.getFirst().closed()) {
        throw new IllegalStateException("RESOURCE_CONTROL_SESSION_INVALID");
      }
      event("RESOURCE_CONTROL", sessions.getFirst());
    }
  }

  int droppedDiagnosticEventCount() {
    synchronized (diagnosticLock) {
      return droppedDiagnosticEvents;
    }
  }

  private void recordClosedSessions() {
    synchronized (diagnosticLock) {
      for (Session session : sessions) {
        if (session.closed() && !session.closeRecorded) {
          session.closeRecorded = true;
          event("CLOSE", session);
        }
      }
    }
  }

  /** Fixed roles/phases/counts only; at most selected, first failure and cleanup per gate. */
  private void diagnostic(String event) {
    synchronized (diagnosticLock) {
      int closed = (int) sessions.stream().filter(Session::closed).count();
      StringBuilder counts = new StringBuilder("LIFECYCLE_COMMIT_GATE_").append(event)
          .append("[cumulativeLimit=").append(MAX_CUMULATIVE_CONNECTIONS)
          .append(",accepted=").append(sessions.size())
          .append(",admissionAttempts=").append(admissionAttempts)
          .append(",active=").append(sessions.size() - closed)
          .append(",closed=").append(closed)
          .append(",rejected=").append(sessions.stream().filter(s -> s.rejected).count())
          .append(",peak=").append(peak)
          .append(",routeCollision=").append(listener.getLocalPort() == upstreamPort ? 1 : 0)
          .append(",ownUpstreamPortMatches=")
          .append(sessions.stream().filter(s -> s.ownUpstreamPortMatch).count())
          .append(",upstreamConnected=")
          .append(sessions.stream().filter(s -> s.upstreamConnected).count())
          .append(",retainedSocketRefs=").append(sockets.size())
          .append(",closedSocketRefs=").append(sockets.stream().filter(Socket::isClosed).count())
          .append(",recognizedCommits=").append(sessions.stream().mapToInt(s -> s.commits).sum())
          .append(",observerChecks=").append(observerChecks)
          .append(",observerMatches=").append(observerMatches)
          .append(",acceptIdleTimeouts=").append(acceptIdleTimeouts)
          .append(",upstreamConnectTimeouts=").append(upstreamConnectTimeouts)
          .append(",selectedAcceptOrdinal=").append(selectedOrdinal);
      counts.append(",droppedDiagnosticEvents=").append(droppedDiagnosticEvents);
      for (Role role : Role.values()) {
        counts.append(',').append(role).append("_accepted=")
            .append(sessions.stream().filter(s -> s.role == role).count());
        counts.append(',').append(role).append("_active=")
            .append(sessions.stream().filter(s -> s.role == role && !s.closed()).count());
      }
      for (Phase phase : Phase.values()) {
        counts.append(',').append(phase).append("_sessions=")
            .append(sessions.stream()
                .filter(s -> (s.closed() ? Phase.CLOSED : s.phase) == phase).count());
      }
      System.out.println(counts.append(']'));
    }
  }

  record ResourceCounts(int accepted, int active, int closed, int rejected, int peak,
      long upstream, int socketReferences, int threadReferences, int journalEntries, long bytes) {}

  /** Observation only: rejection is recorded BEFORE the failure record is published. */
  boolean failurePublished() {
    return failure.get() != null;
  }

  int maxRecordedEventCharacters() {
    synchronized (diagnosticLock) {
      return diagnosticEvents.stream().mapToInt(String::length).max().orElse(0);
    }
  }

  int upstreamTimeoutCount() {
    synchronized (diagnosticLock) {
      return upstreamConnectTimeouts;
    }
  }

  /** Resource-only control: register a test-owned thread through the same bounded ledger. */
  void startResourceControlThread(Runnable action) {
    thread(action);
  }

  /** Independent observation for the interrupted-cleanup control; never closes resources. */
  boolean ownedResourcesTerminated() {
    return listener.isClosed()
        && threads.stream().noneMatch(Thread::isAlive)
        && sockets.stream().allMatch(Socket::isClosed);
  }

  boolean closeStarted() {
    return closing.get();
  }

  boolean ownedSocketsClosed() {
    return listener.isClosed() && sockets.stream().allMatch(Socket::isClosed);
  }

  ResourceCounts resourceCounts() {
    synchronized (diagnosticLock) {
      int closed = (int) sessions.stream().filter(Session::closed).count();
      return new ResourceCounts(sessions.size(), sessions.size() - closed, closed,
          (int) sessions.stream().filter(s -> s.rejected).count(), peak,
          sessions.stream().filter(s -> s.upstreamConnected).count(),
          sockets.size(), threads.size(), diagnosticEvents.size(), gateByteAttempts.get());
    }
  }

  private void closePair(Socket first, Socket second) {
    for (Socket socket : List.of(first, second)) {
      try {
        socket.close();
      } catch (IOException ignored) {
        // Thread and socket closure are asserted below.
      }
    }
    recordClosedSessions();
  }

  @Override
  public void close() throws IOException {
    try {
      closing.set(true);
      decision.countDown();
      listener.close();
      long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      // Re-snapshot: an accept/handshake already in flight can publish its owned socket or relay
      // thread concurrently with close. A single CopyOnWriteArrayList iterator could miss it.
      do {
        for (Socket socket : sockets) socket.close();
        for (Thread thread : threads) {
          if (System.nanoTime() >= end) break;
          thread.join(Math.max(1, Math.min(20,
              TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime()))));
        }
      } while (threads.stream().anyMatch(Thread::isAlive) && System.nanoTime() < end);
      for (Socket socket : sockets) socket.close();
      recordClosedSessions();
      diagnostic("CLEANUP");
      synchronized (diagnosticLock) {
        for (Session session : sessions) event("SUMMARY", session);
        for (String event : diagnosticEvents) System.out.println(event);
      }
      if (!listener.isClosed()) {
        throw new AssertionError("COMMIT_GATE_LISTENER_NOT_CLOSED");
      }
      if (threads.stream().anyMatch(Thread::isAlive) || sockets.stream().anyMatch(s -> !s.isClosed())) {
        throw new AssertionError("COMMIT_GATE_CLEANUP_INCOMPLETE");
      }
      requireHealthy();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError("COMMIT_GATE_CLEANUP_INTERRUPTED");
    }
  }
}
