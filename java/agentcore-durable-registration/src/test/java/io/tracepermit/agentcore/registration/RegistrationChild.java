package io.tracepermit.agentcore.registration;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import io.tracepermit.adapters.remote.agentcore.AgentCoreRegistrationIngress;
import io.tracepermit.identity.InMemoryWorkloadIdentityReplayStore;
import io.tracepermit.persistence.operations.JdbcBusinessOperationRepository;

/** Test-only child protocol: secrets/configuration use inherited anonymous pipes, never argv/files/logs. */
public final class RegistrationChild {
  private static final java.util.concurrent.atomic.AtomicInteger CALLS = new java.util.concurrent.atomic.AtomicInteger();
  private RegistrationChild() {}

  static String call(RegistrationDatabases db, String token, byte[] body, boolean nonDurableControl) throws Exception {
    return call(db, token, body, nonDurableControl, false);
  }

  static String callHttp(RegistrationDatabases db, String token, byte[] body) throws Exception {
    return call(db, token, body, false, true);
  }

  private static String call(
      RegistrationDatabases db, String token, byte[] body, boolean nonDurableControl, boolean http)
      throws Exception {
    int ordinal = CALLS.incrementAndGet();
    String classpath = System.getProperty("tracepermit.registration.childClasspath");
    if (classpath == null) throw new AssertionError("SYNTHETIC_CHILD_CLASSPATH_MISSING");
    // Same JVM envelope as the installed worker; request/child/cleanup deadlines stay unchanged.
    Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-Xms16m", "-Xmx64m", "-XX:ActiveProcessorCount=2",
        "-cp", classpath, RegistrationChild.class.getName())
        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
    var reader = Executors.newFixedThreadPool(2);
    try {
      var output = reader.submit(() -> child.getInputStream().readNBytes(257));
      var input = reader.submit(() -> {
        try (var pipe = new DataOutputStream(child.getOutputStream())) {
          for (String value : new String[] {db.replay.url, db.replay.user, db.replay.password,
              db.operations.url, db.operations.user, db.operations.password}) {
            write(pipe, value.getBytes(StandardCharsets.UTF_8));
          }
          write(pipe, RegistrationIdentity.publicKey()); write(pipe, token.getBytes(StandardCharsets.UTF_8));
          write(pipe, body); pipe.writeBoolean(nonDurableControl); pipe.writeBoolean(http);
        }
        return null;
      });
      input.get(10, TimeUnit.SECONDS);
      if (!child.waitFor(30, TimeUnit.SECONDS)) throw new AssertionError(
          "SYNTHETIC_CHILD_TIMEOUT[call=" + ordinal + ",mode=" + (http ? "HTTP" : "DIRECT") + "]");
      byte[] receipt = output.get(5, TimeUnit.SECONDS);
      if (child.exitValue() != 0 || receipt.length > 256) throw new AssertionError(
          "SYNTHETIC_CHILD_FAILED[call=" + ordinal + ",mode=" + (http ? "HTTP" : "DIRECT")
              + ",exit=" + child.exitValue() + ",bytes=" + receipt.length + ",phase="
              + failurePhase(receipt) + "]");
      String result = new String(receipt, StandardCharsets.UTF_8).strip();
      if (!java.util.Set.of("CREATED", "EXISTING", "REPLAY_REJECTED").contains(result)) {
        throw new AssertionError("SYNTHETIC_CHILD_RECEIPT_INVALID[call=" + ordinal
            + ",mode=" + (http ? "HTTP" : "DIRECT") + ",bytes=" + receipt.length + "]");
      }
      return result;
    } finally {
      child.getOutputStream().close(); child.destroy();
      if (!child.waitFor(5, TimeUnit.SECONDS)) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); }
      child.getInputStream().close(); child.getErrorStream().close();
      reader.shutdownNow();
      if (!reader.awaitTermination(5, TimeUnit.SECONDS) || child.isAlive()) {
        throw new AssertionError("SYNTHETIC_CHILD_CLEANUP_FAILED");
      }
    }
  }

  public static void main(String[] args) {
    String phase = "INPUT";
    int status = 0;
    try (var pipe = new DataInputStream(System.in)) {
      if (args.length != 0) throw new IOException();
      String[] configuration = new String[6];
      for (int i=0;i<6;i++) configuration[i]=new String(read(pipe,4096),StandardCharsets.UTF_8);
      var publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(read(pipe,1024)));
      String token = new String(read(pipe,16391),StandardCharsets.UTF_8);
      byte[] body = read(pipe,32768); boolean nonDurable = pipe.readBoolean();
      boolean http = pipe.readBoolean();
      if (nonDurable && http) throw new IOException();
      var replay = new BoundedJdbcSource(configuration[0],configuration[1],configuration[2]);
      var operations = new BoundedJdbcSource(configuration[3],configuration[4],configuration[5]);
      var verifier = RegistrationIdentity.verifier(publicKey);
      AgentCoreRegistrationIngress.Receipt receipt;
      try {
        if (http) {
          phase = "HTTP_START";
          try (var server = DurableAgentCoreRegistrationHttpServer.create(
              verifier, RegistrationIdentity.enrollment(), RegistrationIdentity.CLOCK,
              replay, operations, 128, Duration.ofSeconds(2), 0,
              io.tracepermit.agentcore.http.AgentCoreRegistrationHttpServer.Limits.defaults())) {
            phase = "HTTP_SEND";
            var response = RegistrationHttpTestClient.send(server.port(), token, body);
            phase = switch (response.outcome()) {
              case RESPONSE -> "HTTP_STATUS";
              case TIMEOUT -> "HTTP_TIMEOUT";
              case SOCKET_CLOSED -> "HTTP_SOCKET_CLOSED";
              case EMPTY_RESPONSE -> "HTTP_EMPTY_RESPONSE";
            };
            status = response.status();
            if (response.status() == 201) System.out.println("CREATED");
            else if (response.status() == 200) System.out.println("EXISTING");
            else if (response.status() == 409
                && response.body().equals("{\"error\":\"AGENTCORE_REGISTRATION_REJECTED\"}")) {
              System.out.println("REPLAY_REJECTED");
            } else throw new IOException();
          }
          return;
        } else if (nonDurable) {
          phase = "CONTROL_REGISTER";
          // Deliberately wrong TEST control, absent from the production factory/API.
          receipt = new AgentCoreRegistrationIngress(verifier,new InMemoryWorkloadIdentityReplayStore(),
              new JdbcBusinessOperationRepository(operations),RegistrationIdentity.CLOCK,RegistrationIdentity.enrollment())
              .register(token,null,body);
        } else {
          phase = "DIRECT_REGISTER";
          receipt = DurableAgentCoreRegistration.create(verifier,RegistrationIdentity.enrollment(),RegistrationIdentity.CLOCK,
              replay,operations,128,Duration.ofSeconds(2)).register(token,null,body);
        }
        if (receipt.executionEnabled()) throw new IOException();
        System.out.println(receipt.created() ? "CREATED" : "EXISTING");
      } catch (AgentCoreRegistrationIngress.IngressException refused) {
        if (!"AGENTCORE_REGISTRATION_REJECTED".equals(refused.code())) throw new IOException();
        System.out.println("REPLAY_REJECTED");
      }
    } catch (Throwable failed) {
      // Never render the exception, stdin, configuration, key, body or token.
      System.out.println("SYNTHETIC_CHILD_FAILED:" + phase + ":" + status); System.exit(2);
    }
  }

  static String failurePhase(byte[] receipt) {
    String text = new String(receipt, StandardCharsets.UTF_8).strip();
    String prefix = "SYNTHETIC_CHILD_FAILED:";
    if (!text.startsWith(prefix)) return "UNOBSERVED";
    String[] fields = text.substring(prefix.length()).split(":", -1);
    if (fields.length != 2 || !java.util.Set.of("INPUT", "HTTP_START", "HTTP_SEND", "HTTP_STATUS",
        "HTTP_TIMEOUT", "HTTP_SOCKET_CLOSED", "HTTP_EMPTY_RESPONSE",
        "CONTROL_REGISTER", "DIRECT_REGISTER").contains(fields[0]) || !fields[1].matches("[0-9]{1,3}")) {
      return "UNOBSERVED";
    }
    return fields[0] + ":" + fields[1];
  }

  private static void write(DataOutputStream output, byte[] bytes) throws IOException {
    output.writeInt(bytes.length); output.write(bytes);
  }
  private static byte[] read(DataInputStream input, int maximum) throws IOException {
    int length=input.readInt(); if(length<1 || length>maximum) throw new IOException();
    byte[] bytes=input.readNBytes(length); if(bytes.length!=length) throw new IOException(); return bytes;
  }
}
