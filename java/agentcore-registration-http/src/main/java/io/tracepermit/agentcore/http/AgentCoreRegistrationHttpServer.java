package io.tracepermit.agentcore.http;

import io.tracepermit.adapters.remote.agentcore.AgentCoreRegistrationIngress;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Explicitly started numeric-loopback registration library. No executable/default wiring or TLS edge.
 * Transport expiry/close cannot roll back a dispatched registration. Running work retains capacity
 * until its actual return, including when a trusted port ignores interruption.
 */
public final class AgentCoreRegistrationHttpServer implements AutoCloseable {
  public static final String ROUTE = "/v1/agentcore/registrations";

  /** Trusted operator limits only; there is no request-configurable address, capacity or deadline. */
  public record Limits(int workers, int pending, Duration headers, Duration body, Duration overall, Duration close) {
    public Limits {
      if (workers < 1 || workers > 16 || pending < 1 || pending > 64
          || !bounded(headers, 30_000) || !bounded(body, 30_000) || !bounded(overall, 30_000)
          || !bounded(close, 2_000) || headers.compareTo(overall) > 0 || body.compareTo(overall) > 0) {
        throw new IllegalArgumentException("HTTP_LIMITS_INVALID");
      }
    }
    public static Limits defaults() {
      return new Limits(4, 8, Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofSeconds(1));
    }
    private static boolean bounded(Duration value, long maximumMillis) {
      return value != null && value.compareTo(Duration.ofMillis(50)) >= 0
          && value.compareTo(Duration.ofMillis(maximumMillis)) <= 0;
    }
  }

  private static final RegistrationHttpCodec.Response BUSY = RegistrationHttpCodec.error(503, "HTTP_CAPACITY_UNAVAILABLE");
  private static final RegistrationHttpCodec.Response UNAVAILABLE = RegistrationHttpCodec.error(503, "HTTP_REGISTRATION_UNAVAILABLE");
  private final AgentCoreRegistrationIngress ingress;
  private final Limits limits;
  private final Selector selector;
  private final ServerSocketChannel listener;
  private final Thread ioThread;
  private final ThreadPoolExecutor workers;
  private final Semaphore capacity;
  private final ArrayBlockingQueue<Context> completions;
  private final Set<Context> held = ConcurrentHashMap.newKeySet();
  private final AtomicBoolean closing = new AtomicBoolean();
  private final int port;

  private static final class Context {
    final SocketChannel channel;
    final long overallDeadline;
    final ByteBuffer headers = ByteBuffer.allocate(RegistrationHttpCodec.MAX_HEADER_BYTES);
    final AtomicBoolean completionOffered = new AtomicBoolean();
    final AtomicBoolean released = new AtomicBoolean();
    volatile boolean networkClosed;
    volatile boolean done;
    volatile boolean completionConsumed;
    volatile boolean cancelled;
    volatile Job job;
    volatile Thread runner;
    volatile RegistrationHttpCodec.Response response;
    SelectionKey key;
    long phaseDeadline;
    int scanned;
    RegistrationHttpCodec.Head head;
    ByteBuffer body;
    ByteBuffer output;
    Context(SocketChannel channel, long now, Limits limits) {
      this.channel = channel;
      overallDeadline = now + limits.overall().toNanos();
      phaseDeadline = now + limits.headers().toNanos();
    }
    @Override public String toString() { return "RegistrationHttpContext[redacted]"; }
  }

  private final class Job implements Runnable {
    private final Context context;
    Job(Context context) { this.context = context; }
    @Override public void run() {
      Context c = context;
      c.runner = Thread.currentThread();
      RegistrationHttpCodec.Response response = UNAVAILABLE;
      try {
        if (!closing.get() && !c.cancelled && !expired(System.nanoTime(), c.overallDeadline)) {
          response = RegistrationHttpCodec.receipt(ingress.register(c.head.authorization(),
              c.head.humanAuthorization(), c.body.array()));
        }
      } catch (AgentCoreRegistrationIngress.IngressException rejected) {
        response = RegistrationHttpCodec.ingressError(rejected.code());
      } catch (Throwable ignored) {
        // Fixed containment, no retries or throwable logging. Even unexpected failure may be uncertain.
      } finally {
        c.runner = null;
        complete(c, response);
        Thread.interrupted();
      }
    }
    void cancel() {
      context.cancelled = true;
      if (workers.remove(this)) complete(context, UNAVAILABLE);
      Thread running = context.runner;
      if (running != null) running.interrupt();
    }
    @Override public String toString() { return "RegistrationHttpJob[redacted]"; }
  }

  /** Port may be zero. Address is hardcoded numeric IPv4 loopback and cannot be overridden. */
  public AgentCoreRegistrationHttpServer(AgentCoreRegistrationIngress ingress, int requestedPort, Limits limits) {
    if (ingress == null || limits == null || requestedPort < 0 || requestedPort > 65_535) {
      throw new IllegalArgumentException("HTTP_CONFIGURATION_INVALID");
    }
    this.ingress = ingress;
    this.limits = limits;
    int bound = limits.workers() + limits.pending();
    capacity = new Semaphore(bound);
    completions = new ArrayBlockingQueue<>(bound);
    AtomicInteger sequence = new AtomicInteger();
    workers = new ThreadPoolExecutor(limits.workers(), limits.workers(), 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(limits.pending()), task -> {
          Thread thread = new Thread(task, "registration-http-worker-" + sequence.incrementAndGet());
          thread.setDaemon(true);
          thread.setUncaughtExceptionHandler((ignoredThread, ignoredFailure) -> {});
          return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    Selector selected = null;
    ServerSocketChannel listening = null;
    try {
      selected = Selector.open();
      listening = ServerSocketChannel.open(StandardProtocolFamily.INET);
      listening.configureBlocking(false);
      listening.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), requestedPort), bound);
      listening.register(selected, SelectionKey.OP_ACCEPT);
      port = ((InetSocketAddress) listening.getLocalAddress()).getPort();
      selector = selected;
      listener = listening;
      ioThread = new Thread(this::serve, "registration-http-io");
      ioThread.setDaemon(true);
      ioThread.setUncaughtExceptionHandler((ignoredThread, ignoredFailure) -> {});
      ioThread.start();
    } catch (Throwable failure) {
      closeChannel(listening);
      closeSelector(selected);
      workers.shutdownNow();
      throw new IllegalStateException("HTTP_START_FAILED", null);
    }
  }

  public int port() { return port; }

  private void serve() {
    try {
      while (!closing.get()) {
        drainCompletions();
        expire();
        selector.select(20);
        var iterator = selector.selectedKeys().iterator();
        while (iterator.hasNext()) {
          SelectionKey key = iterator.next(); iterator.remove();
          if (!key.isValid()) continue;
          try {
            if (key.isAcceptable()) accept();
            else {
              Context c = (Context) key.attachment();
              if (key.isReadable()) read(c);
              if (key.isValid() && key.isWritable()) write(c);
            }
          } catch (IOException | RuntimeException failure) {
            if (key.attachment() instanceof Context c) closeNetwork(c);
            else requestStop();
          }
        }
      }
    } catch (Throwable ignored) {
      requestStop();
    } finally {
      requestStop();
      for (Context c : held) closeNetwork(c);
      for (Runnable task : workers.shutdownNow()) if (task instanceof AgentCoreRegistrationHttpServer.Job job) complete(job.context, UNAVAILABLE);
      drainCompletions();
      closeSelector(selector);
    }
  }

  private void accept() throws IOException {
    // A finite accept batch prevents a hot listener starving deadlines/completions.
    for (int i = 0; i < limits.workers() + limits.pending(); i++) {
      SocketChannel channel = listener.accept();
      if (channel == null) return;
      channel.configureBlocking(false);
      channel.setOption(StandardSocketOptions.SO_LINGER, -1);
      if (closing.get() || !capacity.tryAcquire()) {
        try { channel.write(BUSY.buffer()); } finally { closeChannel(channel); }
        continue; // Nonblocking best effort503, otherwise transport close; never parse/authenticate.
      }
      Context c = new Context(channel, System.nanoTime(), limits);
      held.add(c);
      try {
        c.key = channel.register(selector, SelectionKey.OP_READ, c);
      } catch (IOException | RuntimeException failure) {
        closeNetwork(c);
      }
    }
  }

  private void read(Context c) throws IOException {
    if (c.networkClosed || c.cancelled) return;
    if (expired(System.nanoTime(), c.phaseDeadline) || expired(System.nanoTime(), c.overallDeadline)) { closeNetwork(c); return; }
    if (c.head == null) {
      int count = c.channel.read(c.headers);
      if (count < 0) { closeNetwork(c); return; }
      byte[] bytes = c.headers.array();
      int end = -1;
      for (int i = Math.max(3, c.scanned); i < c.headers.position(); i++) {
        if (bytes[i - 3] == 13 && bytes[i - 2] == 10 && bytes[i - 1] == 13 && bytes[i] == 10) { end = i + 1; break; }
      }
      c.scanned = c.headers.position();
      if (end < 0) {
        if (!c.headers.hasRemaining()) respond(c, RegistrationHttpCodec.error(431, "HTTP_HEADERS_TOO_LARGE"));
        return;
      }
      try {
        c.head = RegistrationHttpCodec.parse(bytes, end, port);
      } catch (RegistrationHttpCodec.Rejection rejected) {
        respond(c, rejected.response()); return;
      }
      // Allocate only after closed framing validates the32KiB Content-Length ceiling.
      c.body = ByteBuffer.allocate(c.head.bodyLength());
      int remainder = c.headers.position() - end;
      if (c.head.humanAuthorization() != null) { dispatch(c); return; }
      if (remainder > c.body.remaining()) { respond(c, RegistrationHttpCodec.error(400, "HTTP_REQUEST_INVALID")); return; }
      c.body.put(bytes, end, remainder);
      c.phaseDeadline = System.nanoTime() + limits.body().toNanos();
    } else {
      if (c.channel.read(c.body) < 0) { closeNetwork(c); return; }
    }
    if (!c.body.hasRemaining()) dispatch(c);
  }

  private void dispatch(Context c) {
    if (closing.get() || c.cancelled || expired(System.nanoTime(), c.overallDeadline)) { closeNetwork(c); return; }
    c.phaseDeadline = c.overallDeadline;
    c.key.interestOps(0);
    Job job = new Job(c); c.job = job;
    try { workers.execute(job); }
    catch (RejectedExecutionException rejected) { complete(c, BUSY); }
  }

  private void complete(Context c, RegistrationHttpCodec.Response response) {
    if (!c.completionOffered.compareAndSet(false, true)) return;
    c.response = response;
    c.done = true;
    if (closing.get()) {
      if (c.networkClosed) release(c);
    } else {
      if (!completions.offer(c)) requestStop(); // Invariant: one completion per held reservation.
      selector.wakeup();
      if (closing.get() && c.networkClosed) release(c);
    }
  }

  private void drainCompletions() {
    Context c;
    while ((c = completions.poll()) != null) {
      c.completionConsumed = true;
      if (c.networkClosed || closing.get() || expired(System.nanoTime(), c.overallDeadline)) {
        closeNetwork(c); release(c);
      } else {
        respond(c, c.response);
      }
    }
  }

  private void respond(Context c, RegistrationHttpCodec.Response response) {
    if (c.networkClosed || closing.get() || expired(System.nanoTime(), c.overallDeadline)) { closeNetwork(c); return; }
    c.output = response.buffer();
    c.head = null; c.body = null;
    c.phaseDeadline = c.overallDeadline;
    c.key.interestOps(SelectionKey.OP_WRITE);
  }

  private void write(Context c) throws IOException {
    if (expired(System.nanoTime(), c.overallDeadline)) { closeNetwork(c); return; }
    c.channel.write(c.output);
    if (!c.output.hasRemaining()) closeNetwork(c);
  }

  private static boolean expired(long now, long deadline) { return now - deadline >= 0; }

  private void expire() {
    long now = System.nanoTime();
    for (Context c : held) if (!c.networkClosed && (expired(now, c.phaseDeadline) || expired(now, c.overallDeadline))) closeNetwork(c);
  }

  private void closeNetwork(Context c) {
    c.cancelled = true; c.networkClosed = true;
    closeChannel(c.channel);
    if (c.key != null) c.key.cancel();
    Job job = c.job;
    if (job == null) release(c);
    else {
      if (!c.done) job.cancel();
      // Normal running work releases only when its completion is consumed, not on Future.cancel.
      if (c.completionConsumed || closing.get() && c.done) release(c);
    }
  }

  private void release(Context c) {
    if (c.released.compareAndSet(false, true)) {
      held.remove(c); capacity.release(); c.head = null; c.body = null; c.output = null; c.response = null;
    }
  }

  private void requestStop() {
    closing.set(true); closeChannel(listener); selector.wakeup();
  }

  record Occupancy(int held, int active, int pending) {}
  Occupancy occupancy() { return new Occupancy(held.size(), workers.getActiveCount(), workers.getQueue().size()); }

  @Override public void close() {
    long deadline = System.nanoTime() + limits.close().toNanos();
    requestStop();
    for (Context c : held) closeNetwork(c);
    for (Runnable task : workers.shutdownNow()) if (task instanceof AgentCoreRegistrationHttpServer.Job job) complete(job.context, UNAVAILABLE);
    boolean interrupted = false;
    try {
      long remaining = deadline - System.nanoTime();
      if (remaining > 0) ioThread.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
      remaining = deadline - System.nanoTime();
      if (remaining > 0) workers.awaitTermination(remaining, TimeUnit.NANOSECONDS);
    } catch (InterruptedException failure) { interrupted = true; }
    finally { if (interrupted) Thread.currentThread().interrupt(); }
  }

  private static void closeChannel(java.nio.channels.Channel channel) {
    if (channel != null) try { channel.close(); } catch (IOException ignored) { }
  }
  private static void closeSelector(Selector selector) {
    if (selector != null) try { selector.close(); } catch (IOException ignored) { }
  }
}
