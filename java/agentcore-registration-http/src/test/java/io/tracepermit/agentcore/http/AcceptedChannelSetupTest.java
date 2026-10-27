package io.tracepermit.agentcore.http;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class AcceptedChannelSetupTest {
  @ParameterizedTest
  @CsvSource({"configure,checked", "configure,runtime", "configure,error",
      "option,checked", "option,runtime", "option,error"})
  void provisionalChannelClosesOnEverySetupFailure(String stage, String kind) {
    try (var channel = new SetupChannel(stage, kind)) {
      Throwable failure = catchThrowable(() -> AgentCoreRegistrationHttpServer.configureAccepted(channel));
      assertThat(failure).isSameAs(channel.failure);
      assertThat(channel.isOpen()).isFalse();
      assertThat(channel.closes).isEqualTo(1);
      assertThat(channel.configures).isEqualTo(1);
      assertThat(channel.options).isEqualTo(stage.equals("option") ? 1 : 0);
      assertThat(channel.isRegistered()).isFalse();
    } catch (IOException impossible) {
      throw new AssertionError("SYNTHETIC_CLOSE_FAILED", impossible);
    }
  }

  @Test
  void successfulSetupLeavesChannelOwnedByCallerUntilItIsTrackedOrClosed() throws Exception {
    var channel = new SetupChannel("none", "checked");
    try (channel) {
      AgentCoreRegistrationHttpServer.configureAccepted(channel);
      assertThat(channel.isOpen()).isTrue();
      assertThat(channel.isBlocking()).isFalse();
      assertThat(channel.configures).isEqualTo(1);
      assertThat(channel.options).isEqualTo(1);
      assertThat(channel.closes).isZero();
    }
    assertThat(channel.closes).isEqualTo(1);
  }

  private static final class SetupChannel extends SocketChannel {
    final String stage;
    final Throwable failure;
    int configures;
    int options;
    int closes;

    SetupChannel(String stage, String kind) {
      super(SelectorProvider.provider());
      this.stage = stage;
      failure = switch (kind) {
        case "checked" -> new IOException("SYNTHETIC_SETUP_FAILURE");
        case "runtime" -> new IllegalStateException("SYNTHETIC_SETUP_FAILURE");
        case "error" -> new AssertionError("SYNTHETIC_SETUP_FAILURE");
        default -> throw new AssertionError("UNKNOWN_SYNTHETIC_FAILURE");
      };
    }

    private void fail(String currentStage) throws IOException {
      if (!stage.equals(currentStage)) return;
      if (failure instanceof IOException checked) throw checked;
      if (failure instanceof RuntimeException unchecked) throw unchecked;
      throw (Error) failure;
    }

    @Override protected void implConfigureBlocking(boolean block) throws IOException {
      assertThat(block).isFalse(); configures++; fail("configure");
    }
    @Override public <T> SocketChannel setOption(SocketOption<T> name, T value) throws IOException {
      assertThat(name).isEqualTo(StandardSocketOptions.SO_LINGER);
      assertThat(value).isEqualTo(-1); options++; fail("option"); return this;
    }
    @Override protected void implCloseSelectableChannel() { closes++; }
    @Override public SocketChannel bind(SocketAddress local) { throw unexpected(); }
    @Override public SocketChannel shutdownInput() { throw unexpected(); }
    @Override public SocketChannel shutdownOutput() { throw unexpected(); }
    @Override public Socket socket() { throw unexpected(); }
    @Override public boolean isConnected() { throw unexpected(); }
    @Override public boolean isConnectionPending() { throw unexpected(); }
    @Override public boolean connect(SocketAddress remote) { throw unexpected(); }
    @Override public boolean finishConnect() { throw unexpected(); }
    @Override public SocketAddress getRemoteAddress() { throw unexpected(); }
    @Override public SocketAddress getLocalAddress() { throw unexpected(); }
    @Override public int read(ByteBuffer destination) { throw unexpected(); }
    @Override public long read(ByteBuffer[] destinations, int offset, int length) { throw unexpected(); }
    @Override public int write(ByteBuffer source) { throw unexpected(); }
    @Override public long write(ByteBuffer[] sources, int offset, int length) { throw unexpected(); }
    @Override public <T> T getOption(SocketOption<T> name) { throw unexpected(); }
    @Override public Set<SocketOption<?>> supportedOptions() { throw unexpected(); }
    private static AssertionError unexpected() { return new AssertionError("UNEXPECTED_SYNTHETIC_CHANNEL_OPERATION"); }
  }
}
