# Verifier-only simulator executor foundation

This runbook describes the simulator-only TP-MVP-11 foundation. It is for
development and automated qualification, not a real AWS/Jira deployment.

## Mental model

A permit issuer and an executor need different capabilities:

- the **issuer** holds the Ed25519 private key and can mint narrowly bound,
  short-lived authority after policy or approval;
- the **executor** holds only the public key, verifies the exact canonical action,
  claims one use durably and invokes an operator-allowlisted adapter;
- a source attestation says where a request came from; it does not replace a
  permit or authorize execution.

This separation limits one compromise direction: an executor with only a public
key cannot create new permits. It does not make the executor harmless. An
executor still sits on the target path and therefore needs process/container
isolation, least-privilege credentials, egress restrictions and monitoring before
any real adapter is considered.

## Build and offline check

Use Java 21 and the repository's configured Gradle environment:

```bash
cd java
source ../scripts/configure-java-environment.sh
./gradlew :executor-simulator:installDist
```

Copy `config/verifier-only-executor.example.json` and create its referenced
files in the same private directory. The public key is X.509/SPKI Ed25519 bytes;
tests generate key pairs at runtime. Do not copy a private signing key into the
executor directory. The configuration, public-key file and database-password
file must be regular, owner-readable files with mode `0400` or `0600`.

```bash
chmod 600 executor.json permit.spki database.secret
java/executor-simulator/build/install/tracepermit-verifier-only-executor/bin/tracepermit-verifier-only-executor \
  --check /absolute/private/path/executor.json
```

Expected output identifies `verifier_only_simulator`, `EdDSA`, the configured
key ID/fingerprint, audience and route count. The check performs no database,
IdP, cloud or target network call.

## One-shot execution protocol

Normal execution requires an already initialized, compatible PostgreSQL schema.
The executor never migrates, repairs or silently initializes it.

```json
{
  "schemaVersion": "1.0",
  "permit": "<compact signed permit>",
  "action": { "<exact canonical action>": "..." }
}
```

Pipe exactly one document to `--execute`. Trust inputs in the request—such as a
key, issuer, audience, allowlist, credential or `verified` flag—are rejected.
Success returns only:

```json
{
  "schemaVersion": "1.0",
  "status": "succeeded",
  "mode": "simulated",
  "eventId": "...",
  "permitId": "...",
  "system": "aws",
  "operation": "aws.ec2.update_security_group",
  "resultHash": "sha256:..."
}
```

The permit, raw arguments and raw simulator result are never returned. A result
hash is not encryption and must not be used to claim that low-entropy data is
secret.

## Qualification

```bash
cd java
source ../scripts/configure-java-environment.sh
./gradlew :executor-simulator:test --rerun-tasks
```

The suite covers success, strict parsing/configuration, injected trust fields,
mutation, wrong key/key ID/issuer/audience/algorithm, expiry, replay, target
rejection, secret-safe errors, database unavailability, concurrent in-process
claims, concurrent one-shot JVMs and replay after process restart. PostgreSQL is
a disposable Testcontainer. Keys are generated at runtime.

## Stop conditions and remaining gates

Do not add a provider SDK or credential to this profile. Before a real adapter,
complete ADR 0056's credential, egress, target-identity, durable attempt/evidence,
uncertain-outcome recovery and deployment-isolation gates. A consumed permit is
never restored merely because the process returned an error or timed out.
