package io.tracepermit.executor.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.persistence.postgres.PostgresDurableEnforcementState;
import io.tracepermit.persistence.postgres.PostgresPermitConsumptionStore;
import io.tracepermit.security.ExecutionPermitAuthority;
import io.tracepermit.security.ExecutionPermitException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.security.KeyPair;
import java.time.Clock;
import java.nio.file.Path;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresVerifierOnlyExecutorTest {
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16.4-alpine")
          .withPassword("runtime-generated-test-password");
  @TempDir Path directory;

  @BeforeAll
  static void startPostgres() {
    POSTGRES.start();
    PostgresDurableEnforcementState.migrate(
        dataSource(), PostgresDurableEnforcementState.MIGRATION_LOCATION);
  }

  @AfterAll
  static void stopPostgres() {
    POSTGRES.stop();
  }

  @Test
  void independentExecutorInstancesShareDurableSingleUseState() throws Exception {
    KeyPair keys = ExecutorTestFixtures.keyPair();
    JsonNode event = ExecutorTestFixtures.action();
    String permit = ExecutorTestFixtures.signer(
        keys, Clock.systemUTC(), new AtomicInteger())
        .issue(event, ExecutorTestFixtures.evaluation(event), ExecutorTestFixtures.AUDIENCE);
    AtomicInteger calls = new AtomicInteger();
    var first = executor(keys, calls);
    var second = executor(keys, calls);
    CountDownLatch start = new CountDownLatch(1);
    List<Callable<Boolean>> attempts = IntStream.range(0, 32)
        .mapToObj(index -> (Callable<Boolean>) () -> {
          start.await();
          try {
            (index % 2 == 0 ? first : second).execute(permit, event);
            return true;
          } catch (ExecutionPermitException rejected) {
            return false;
          }
        }).toList();

    long winners;
    try (var pool = Executors.newFixedThreadPool(16)) {
      var futures = attempts.stream().map(pool::submit).toList();
      start.countDown();
      winners = 0;
      for (var future : futures) if (future.get()) winners++;
    }

    assertThat(winners).isOne();
    assertThat(calls).hasValue(1);
    assertThatThrownBy(() -> executor(keys, calls).execute(permit, event))
        .isInstanceOf(ExecutionPermitException.class)
        .hasMessageContaining("single-use");
    assertThat(calls).hasValue(1);
  }

  @Test
  void concurrentOneShotProcessesHaveOneWinnerAndRestartCannotReplay() throws Exception {
    KeyPair keys = ExecutorTestFixtures.keyPair();
    JsonNode event = ExecutorTestFixtures.action();
    var signer = new ExecutionPermitAuthority(
        ExecutorTestFixtures.ISSUER,
        ExecutorTestFixtures.KEY_ID,
        keys,
        60,
        Clock.systemUTC(),
        () -> "permit-process-" + java.util.UUID.randomUUID(),
        new io.tracepermit.security.InMemoryPermitConsumptionStore());
    String permit = signer.issue(
        event, ExecutorTestFixtures.evaluation(event), ExecutorTestFixtures.AUDIENCE);
    Path config = ExecutorTestFixtures.configuration(
        directory,
        keys,
        POSTGRES.getFirstMappedPort(),
        POSTGRES.getDatabaseName(),
        POSTGRES.getUsername(),
        POSTGRES.getPassword());
    byte[] request = request(permit, event);

    Process first = startProcess(config, request);
    Process second = startProcess(config, request);
    ProcessResult firstResult = finish(first);
    ProcessResult secondResult = finish(second);

    assertThat(List.of(firstResult.exitCode(), secondResult.exitCode()))
        .containsExactlyInAnyOrder(0, 4);
    assertThat(List.of(firstResult.body().path("status").asText(),
        secondResult.body().path("status").asText()))
        .containsExactlyInAnyOrder("succeeded", "failed");
    assertThat(firstResult.output()).doesNotContain(permit, "groupId", "cidr");
    assertThat(secondResult.output()).doesNotContain(permit, "groupId", "cidr");

    ProcessResult replay = finish(startProcess(config, request));
    assertThat(replay.exitCode()).isEqualTo(4);
    assertThat(replay.body().path("code").asText()).isEqualTo("EXECUTION_REJECTED");
    assertThat(replay.output()).doesNotContain(permit);
  }

  @Test
  void responseFailureAfterDispatchReportsUnknownAndNeverRestoresAuthority() throws Exception {
    KeyPair keys = ExecutorTestFixtures.keyPair();
    JsonNode event = ExecutorTestFixtures.action();
    String permitId = "permit-output-" + java.util.UUID.randomUUID();
    var signer = new ExecutionPermitAuthority(
        ExecutorTestFixtures.ISSUER,
        ExecutorTestFixtures.KEY_ID,
        keys,
        60,
        Clock.systemUTC(),
        () -> permitId,
        new io.tracepermit.security.InMemoryPermitConsumptionStore());
    String permit = signer.issue(
        event, ExecutorTestFixtures.evaluation(event), ExecutorTestFixtures.AUDIENCE);
    Path config = ExecutorTestFixtures.configuration(
        directory,
        keys,
        POSTGRES.getFirstMappedPort(),
        POSTGRES.getDatabaseName(),
        POSTGRES.getUsername(),
        POSTGRES.getPassword());

    int status = VerifierOnlyExecutorMain.run(
        new String[] {"--execute", config.toString()},
        new java.io.ByteArrayInputStream(request(permit, event)),
        new java.io.OutputStream() {
          @Override public void write(int value) throws IOException { throw new IOException("closed"); }
        });

    assertThat(status).isEqualTo(5);
    assertThat(PostgresPermitConsumptionStore.openExisting(dataSource())
        .isConsumed("tenant-acme", permitId)).isTrue();
    assertThatThrownBy(() -> executor(keys, new AtomicInteger()).execute(permit, event))
        .isInstanceOf(ExecutionPermitException.class);
  }

  private static VerifierOnlySimulatorExecutor executor(KeyPair keys, AtomicInteger calls) {
    var store = PostgresPermitConsumptionStore.openExisting(dataSource());
    var authority = new ExecutionPermitAuthority(
        ExecutorTestFixtures.ISSUER,
        ExecutorTestFixtures.KEY_ID,
        keys.getPublic(),
        60,
        Clock.systemUTC(),
        store);
    return new VerifierOnlySimulatorExecutor(
        authority,
        ExecutorTestFixtures.AUDIENCE,
        List.of(ExecutorTestFixtures.route()),
        action -> {
          calls.incrementAndGet();
          return action;
        });
  }

  private static PGSimpleDataSource dataSource() {
    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setUrl(POSTGRES.getJdbcUrl());
    source.setUser(POSTGRES.getUsername());
    source.setPassword(POSTGRES.getPassword());
    return source;
  }

  private static byte[] request(String permit, JsonNode event) throws IOException {
    var root = io.tracepermit.contracts.JsonSupport.MAPPER.createObjectNode();
    root.put("schemaVersion", "1.0");
    root.put("permit", permit);
    root.set("action", event);
    return io.tracepermit.contracts.JsonSupport.MAPPER.writeValueAsBytes(root);
  }

  private static Process startProcess(Path config, byte[] request) throws IOException {
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    String classpath = System.getProperty("tracepermit.executorRuntimeClasspath");
    Process process = new ProcessBuilder(
        java,
        "-cp",
        classpath,
        VerifierOnlyExecutorMain.class.getName(),
        "--execute",
        config.toString())
        .redirectErrorStream(true)
        .start();
    process.getOutputStream().write(request);
    process.getOutputStream().close();
    return process;
  }

  private static ProcessResult finish(Process process) throws Exception {
    String output = new String(process.getInputStream().readAllBytes(),
        java.nio.charset.StandardCharsets.UTF_8);
    int exit = process.waitFor();
    JsonNode body = io.tracepermit.contracts.JsonSupport.MAPPER.readTree(output);
    return new ProcessResult(exit, output, body);
  }

  private record ProcessResult(int exitCode, String output, JsonNode body) {}
}
