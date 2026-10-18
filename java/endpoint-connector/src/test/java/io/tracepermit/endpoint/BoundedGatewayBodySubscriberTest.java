package io.tracepermit.endpoint;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class BoundedGatewayBodySubscriberTest {
  @Test
  void completesOnlyAtEofAndAcceptsExactlyTheCapAcrossChunks() {
    BoundedGatewayBodySubscriber subscriber = new BoundedGatewayBodySubscriber(4);
    Subscription subscription = new Subscription();
    subscriber.onSubscribe(subscription);
    subscriber.onNext(List.of(buffer("a"), buffer("bc")));
    subscriber.onNext(List.of(buffer("d")));
    assertThat(subscriber.getBody().toCompletableFuture()).isNotDone();
    subscriber.onComplete();
    assertThat(subscriber.getBody().toCompletableFuture().join()).isEqualTo("abcd".getBytes(StandardCharsets.UTF_8));
    assertThat(subscription.requests).isEqualTo(3);
    assertThat(subscription.cancellations).isZero();
    assertThatThrownBy(() -> new BoundedGatewayBodySubscriber(0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void overflowCancelsWithoutBufferingTheOversizedChunkOrRecoveringLater() {
    BoundedGatewayBodySubscriber subscriber = new BoundedGatewayBodySubscriber(4);
    Subscription subscription = new Subscription();
    subscriber.onSubscribe(subscription);
    subscriber.onNext(List.of(buffer("abc")));
    ByteBuffer oversized = buffer("secret-sentinel");
    subscriber.onNext(List.of(oversized));
    subscriber.onNext(List.of(buffer("d")));
    subscriber.onComplete();
    assertThat(oversized.position()).isZero();
    assertThat(subscription.cancellations).isOne();
    assertThatThrownBy(() -> subscriber.getBody().toCompletableFuture().join())
        .isInstanceOf(CompletionException.class)
        .cause().isInstanceOf(ConnectorException.class)
        .hasMessage("The gateway response is too large.");
  }

  @Test
  void rejectsDuplicateAndLateSubscriptionsAfterCancellation() {
    BoundedGatewayBodySubscriber subscriber = new BoundedGatewayBodySubscriber(4);
    Subscription first = new Subscription();
    Subscription duplicate = new Subscription();
    subscriber.onSubscribe(first);
    subscriber.onSubscribe(duplicate);
    subscriber.cancel();
    Subscription late = new Subscription();
    subscriber.onSubscribe(late);
    subscriber.onNext(List.of(buffer("a")));
    assertThat(first.cancellations).isOne();
    assertThat(duplicate.cancellations).isOne();
    assertThat(late.cancellations).isOne();
    assertThat(subscriber.getBody().toCompletableFuture()).isCancelled();

    BoundedGatewayBodySubscriber beforeSubscribe = new BoundedGatewayBodySubscriber(4);
    beforeSubscribe.cancel();
    Subscription neverRequested = new Subscription();
    beforeSubscribe.onSubscribe(neverRequested);
    assertThat(neverRequested.requests).isZero();
    assertThat(neverRequested.cancellations).isOne();
  }

  @Test
  void preservesTransportFailureAndSupportsEmptyCompletion() {
    BoundedGatewayBodySubscriber failed = new BoundedGatewayBodySubscriber(4);
    IOException failure = new IOException("synthetic-private-diagnostic");
    failed.onError(failure);
    failed.onComplete();
    assertThatThrownBy(() -> failed.getBody().toCompletableFuture().join())
        .isInstanceOf(CompletionException.class).hasCause(failure);
    BoundedGatewayBodySubscriber empty = new BoundedGatewayBodySubscriber(4);
    empty.onComplete();
    assertThat(empty.getBody().toCompletableFuture().join()).isEmpty();
  }

  private static ByteBuffer buffer(String value) {
    return ByteBuffer.wrap(value.getBytes(StandardCharsets.UTF_8));
  }

  private static final class Subscription implements Flow.Subscription {
    private int requests;
    private int cancellations;

    @Override
    public void request(long count) {
      assertThat(count).isOne();
      requests++;
    }

    @Override
    public void cancel() {
      cancellations++;
    }
  }
}
