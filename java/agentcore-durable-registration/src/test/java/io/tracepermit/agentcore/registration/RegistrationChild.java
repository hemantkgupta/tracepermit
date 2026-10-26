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
  private RegistrationChild() {}

  static String call(RegistrationDatabases db, String token, byte[] body, boolean nonDurableControl) throws Exception {
    String classpath = System.getProperty("tracepermit.registration.childClasspath");
    if (classpath == null) throw new AssertionError("SYNTHETIC_CHILD_CLASSPATH_MISSING");
    Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
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
          write(pipe, body); pipe.writeBoolean(nonDurableControl);
        }
        return null;
      });
      input.get(10, TimeUnit.SECONDS);
      if (!child.waitFor(30, TimeUnit.SECONDS)) throw new AssertionError("SYNTHETIC_CHILD_TIMEOUT");
      byte[] receipt = output.get(5, TimeUnit.SECONDS);
      if (child.exitValue() != 0 || receipt.length > 256) throw new AssertionError("SYNTHETIC_CHILD_FAILED");
      String result = new String(receipt, StandardCharsets.UTF_8).strip();
      if (!java.util.Set.of("CREATED", "EXISTING", "REPLAY_REJECTED").contains(result)) {
        throw new AssertionError("SYNTHETIC_CHILD_RECEIPT_INVALID");
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
    try (var pipe = new DataInputStream(System.in)) {
      if (args.length != 0) throw new IOException();
      String[] configuration = new String[6];
      for (int i=0;i<6;i++) configuration[i]=new String(read(pipe,4096),StandardCharsets.UTF_8);
      var publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(read(pipe,1024)));
      String token = new String(read(pipe,16391),StandardCharsets.UTF_8);
      byte[] body = read(pipe,32768); boolean nonDurable = pipe.readBoolean();
      var replay = new BoundedJdbcSource(configuration[0],configuration[1],configuration[2]);
      var operations = new BoundedJdbcSource(configuration[3],configuration[4],configuration[5]);
      var verifier = RegistrationIdentity.verifier(publicKey);
      AgentCoreRegistrationIngress.Receipt receipt;
      try {
        if (nonDurable) {
          // Deliberately wrong TEST control, absent from the production factory/API.
          receipt = new AgentCoreRegistrationIngress(verifier,new InMemoryWorkloadIdentityReplayStore(),
              new JdbcBusinessOperationRepository(operations),RegistrationIdentity.CLOCK,RegistrationIdentity.enrollment())
              .register(token,null,body);
        } else {
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
      System.out.println("SYNTHETIC_CHILD_FAILED"); System.exit(2);
    }
  }

  private static void write(DataOutputStream output, byte[] bytes) throws IOException {
    output.writeInt(bytes.length); output.write(bytes);
  }
  private static byte[] read(DataInputStream input, int maximum) throws IOException {
    int length=input.readInt(); if(length<1 || length>maximum) throw new IOException();
    byte[] bytes=input.readNBytes(length); if(bytes.length!=length) throw new IOException(); return bytes;
  }
}
