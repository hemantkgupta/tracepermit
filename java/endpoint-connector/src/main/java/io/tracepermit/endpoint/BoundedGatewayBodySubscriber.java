package io.tracepermit.endpoint;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Completes only after EOF, without buffering a response beyond the configured byte cap. */
final class BoundedGatewayBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
  private final int maximumBytes;
  private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
  private final CompletableFuture<byte[]> body = new CompletableFuture<>();
  private Flow.Subscription subscription;
  private boolean cancelled;

  BoundedGatewayBodySubscriber(int maximumBytes) {
    if (maximumBytes < 1) throw new IllegalArgumentException("Invalid response byte cap.");
    this.maximumBytes = maximumBytes;
  }

  @Override
  public CompletionStage<byte[]> getBody() {
    return body;
  }

  @Override
  public synchronized void onSubscribe(Flow.Subscription incoming) {
    if (cancelled || subscription != null) {
      incoming.cancel();
      return;
    }
    subscription = incoming;
    incoming.request(1);
  }

  @Override
  public synchronized void onNext(List<ByteBuffer> buffers) {
    if (body.isDone()) return;
    for (ByteBuffer buffer : buffers) {
      if (buffer.remaining() > maximumBytes - bytes.size()) {
        subscription.cancel();
        body.completeExceptionally(new ConnectorException(
            "GATEWAY_RESPONSE_TOO_LARGE", "The gateway response is too large."));
        return;
      }
      byte[] chunk = new byte[buffer.remaining()];
      buffer.get(chunk);
      bytes.writeBytes(chunk);
    }
    subscription.request(1);
  }

  @Override
  public synchronized void onError(Throwable failure) {
    body.completeExceptionally(failure);
  }

  @Override
  public synchronized void onComplete() {
    body.complete(bytes.toByteArray());
  }

  synchronized void cancel() {
    cancelled = true;
    if (subscription != null) subscription.cancel();
    body.cancel(false);
  }
}
