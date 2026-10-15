package io.tracepermit.adapters.http;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.JsonSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

/** Private temporary synthetic operator files; never reusable account credentials. */
final class SimulatorApprovalFixture {
  final Path directory;
  final Path config;
  final ObjectNode json;
  final KeyPair key;

  SimulatorApprovalFixture(Path directory) throws Exception {
    this.directory = directory;
    config = directory.resolve("operator.json");
    key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    write("permit.pk8", key.getPrivate().getEncoded());
    write("permit.spki", key.getPublic().getEncoded());
    write("database.secret", "synthetic-local-test-password\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    json = (ObjectNode) JsonSupport.MAPPER.readTree("""
        {"schemaVersion":"1.0","port":18085,
         "postgres":{"host":"127.0.0.1","port":1,"database":"simulated_approvals",
                     "user":"simulated_operator","passwordFile":"database.secret"},
         "oidc":{"issuer":"https://idp.example.invalid","audience":"https://approval.example.invalid",
                 "jwksUri":"https://idp.example.invalid/jwks","clientId":"approval-client",
                 "tenantClaim":"org_id","tenantValue":"alpha","tenantId":"simulated-alpha"},
         "memberships":[{"subject":"alice","human":true,"roles":["REQUESTER"]},
                        {"subject":"bob","human":true,"roles":["REVIEWER"]}],
         "permit":{"keyId":"local-simulator-1","privateKeyFile":"permit.pk8","publicKeyFile":"permit.spki"}}
        """);
    save();
  }

  void write(String name, byte[] bytes) throws Exception {
    Path target = directory.resolve(name);
    if (!Files.exists(target)) Files.createFile(target, PosixFilePermissions.asFileAttribute(
        PosixFilePermissions.fromString("rw-------")));
    Files.write(target, bytes);
  }

  void save() throws Exception { write("operator.json", JsonSupport.MAPPER.writeValueAsBytes(json)); }
  SimulatorApprovalConfiguration load() throws Exception { save(); return SimulatorApprovalConfiguration.load(config); }
}
