# Endpoint connector distribution runbook

This runbook guides operators and evaluators using the packaged, source-free TracePermit
endpoint connector application (`tracepermit-endpoint-connector`).
This archive bundles the compiled Java connector and its thin host-native launcher without
requiring Node.js, pnpm, Gradle, or a source code checkout on the host workstation.

> [!IMPORTANT]
> This distribution is a **simulator-only qualification foundation**, not a production
> release or remote cloud deployment. Its gateway transport binds exclusively to numeric
> IPv4 loopback (`http://127.0.0.1:<explicit-port>/mcp`), `NO_PROXY`, and no redirects.

## Host prerequisites

- **Operating system:** macOS (ARM64 / x86_64) only. Linux and Windows are not supported in this initial distribution bundle.
- **Runtime:** Java 21 (OpenJDK / Eclipse Temurin recommended) on `PATH` or `JAVA_HOME`.
- **Standard utilities:** POSIX standard macOS shell utilities (`bash`, `cat`, `sed`, `shasum`, `tar`).
- **Tools not required:** Node.js, pnpm, Gradle, and git are **not** required to run or offline-validate this bundle.

## Archive verification and extraction

Verify archive integrity before extracting:

```bash
shasum -a 256 -c tracepermit-endpoint-connector-0.1.0.tar.gz.sha256
```

> [!NOTE]
> SHA-256 checksums verify transmission and storage integrity. They do not constitute
> an independent cryptographic release signature or third-party provenance attestation.
> Rebuilding the distribution archive produces a different archive SHA-256 due to archive
> container timestamps, though uncompressed project-JAR entries match byte-for-byte;
> this distribution does not guarantee bit-for-bit reproducible release archives.

Extract the archive into your chosen installation directory (paths containing spaces are fully supported):

```bash
tar -xzf tracepermit-endpoint-connector-0.1.0.tar.gz
cd tracepermit-endpoint-connector-0.1.0
shasum -a 256 -c SHA256SUMS
```

Inspect `manifest.json`: schema `1.1` records `sourceCommit`, `sourceCommitTimestamp` (the commit
time, not the packaging time), `sourceTree: "clean"`, required Java 21, and an exact runtime JAR
inventory.

The extracted archive layout:

```text
tracepermit-endpoint-connector-0.1.0/
├── bin/
│   └── tracepermit-endpoint-connector
├── lib/
│   └── *.jar
├── docs/
│   ├── setup-runbook.md
│   ├── LICENSE
│   └── NOTICE
├── manifest.json
└── SHA256SUMS
```

## Launcher interface

The launcher script at `bin/tracepermit-endpoint-connector` provides a thin, host-native interface:

| Command / Flag | Purpose                                                           | Exit code                   |
| -------------- | ----------------------------------------------------------------- | --------------------------- |
| `--help`, `-h` | Prints usage and environment variable documentation to `stdout`   | `0`                         |
| `--check`      | Performs strictly offline configuration validation                | `0` (valid) / `2` (invalid) |
| `--run`        | Starts the stdio Model Context Protocol (MCP) connector (default) | Process exit                |

On non-macOS platforms (e.g. Linux or Windows), `--check` and `--run` fail-closed with exit code `2` and emit:

```text
TracePermit endpoint connector: UNSUPPORTED_PLATFORM
```

Unrecognized flags or conflicting arguments exit with status `2` and emit safe, redacted diagnostics
to `stderr`:

```text
TracePermit endpoint connector: CONFIGURATION_INVALID
```

### Ambient JVM diagnostic-option sanitization

The launcher explicitly unsets ambient JVM diagnostic variables (`JAVA_TOOL_OPTIONS`, `_JAVA_OPTIONS`,
`JDK_JAVA_OPTIONS`) before invoking Java. This prevents secret sentinels present in ambient environment
variables from being echoed into process diagnostic streams or logs.

### Process parentage

The launcher replaces itself with the Java runtime using `exec java -cp "$CLASSPATH" io.tracepermit.endpoint.EndpointConnectorMain "$@"`.
This guarantees that the spawning client process (such as native Codex) is the direct parent of the
Java connector process, with no intermediate shell or Node.js forwarding process.

## Environment configuration

The connector requires all configuration and credentials to be provided via environment variables.
Never pass bearer tokens or private key contents on the command line.

| Environment variable                          | Required | Description                                                                                         |
| --------------------------------------------- | -------- | --------------------------------------------------------------------------------------------------- |
| `TRACEPERMIT_GATEWAY_URL`                     | Yes      | Numeric loopback HTTP endpoint (`http://127.0.0.1:<port>/mcp`). Remote URLs and HTTPS are rejected. |
| `TRACEPERMIT_ENDPOINT_ISSUER`                 | Yes      | Endpoint identity issuer (e.g. `tracepermit-endpoint:demo-local`).                                  |
| `TRACEPERMIT_ENDPOINT_KEY_ID`                 | Yes      | Key identifier matching the enrolled public key.                                                    |
| `TRACEPERMIT_ENDPOINT_DEVICE_ID`              | Yes      | Device identifier matching the endpoint key.                                                        |
| `TRACEPERMIT_ENDPOINT_PRIVATE_KEY_PKCS8_PATH` | Yes      | Path to an owner-readable (`0600` or `0400`) Ed25519 PKCS#8 private key. Symlinks are rejected.     |
| `TRACEPERMIT_CONNECTOR_BEARER_TOKEN`          | Yes      | Short-lived connector Bearer credential for gateway authentication.                                 |
| `TRACEPERMIT_ENDPOINT_OBSERVATION_DIRECTORY`  | Optional | Absolute path to an owner-only directory (`0700`) for opt-in process observation capture.           |

## Offline configuration validation (`--check`)

Before starting the connector under a client, validate the host environment offline:

```bash
./bin/tracepermit-endpoint-connector --check
```

When configuration is valid, the command prints:

```text
ENDPOINT_CONFIGURATION_VALID: offline only; no gateway, IdP, observer or client checked.
```

### Offline validation boundaries

The `--check` command performs purely local validation:

- Verifies Java major version is 21 or newer.
- Verifies private key file existence, non-symlink status, POSIX permissions (`0600`/`0400`), and Ed25519 PKCS#8 format.
- Verifies `TRACEPERMIT_GATEWAY_URL` is an explicit loopback HTTP `/mcp` URL.
- Verifies token format and SHA-256 fingerprintability.
- Verifies observation directory path and permissions without creating files.

> [!WARNING]
> Offline validation makes **zero** network calls and creates **zero** files. It cannot prove
> token validity with an IdP, gateway readiness, public-key enrollment on the server, human presence,
> or policy acceptance.

## Security boundaries

1. **Public-key enrollment versus private-key custody:** The simulator gateway must be pre-configured
   with the endpoint's public key (`TRACEPERMIT_ENDPOINT_PUBLIC_KEY_SPKI_BASE64`). Client requests
   cannot enroll keys; the connector performs no auto-enrollment, retry loops, or credential download service.
   Local POSIX key files (`0600`/`0400`) provide same-UID development custody on the workstation, not
   hardware-isolated or kernel-protected custody against other processes running under the same UID.
2. **Credential injection:** Bearer tokens must be injected via `TRACEPERMIT_CONNECTOR_BEARER_TOKEN`.
   Never embed secrets in configuration files or launcher arguments.
3. **No client modification:** The distribution does not modify `~/.codex` or any global client configuration.
4. **Convenience launcher vs. distribution runtime:** The repository script `scripts/run-codex-endpoint-demo.sh`
   is a Node-based developer convenience wrapper that automatically issues tokens and coordinates with an
   **already-started** local `pnpm demo` backend. In contrast, this distribution runtime is fully independent
   of Node.js and requires external credential and gateway coordination. Note that the backend (`pnpm demo`
   running on loopback), the Java runtime (Java 21), and the native client (e.g. Codex) are distinct prerequisites
   with separate lifecycles.

## Native Codex client configuration

To configure the installed endpoint connector in native Codex, specify the absolute path to the
installed launcher in your project or session configuration:

```toml
[mcp_servers.tracepermit_endpoint]
command = "/absolute/path/to/tracepermit-endpoint-connector-0.1.0/bin/tracepermit-endpoint-connector"
args = ["--run"]
env_vars = [
  "JAVA_HOME",
  "TRACEPERMIT_GATEWAY_URL",
  "TRACEPERMIT_ENDPOINT_ISSUER",
  "TRACEPERMIT_ENDPOINT_KEY_ID",
  "TRACEPERMIT_ENDPOINT_DEVICE_ID",
  "TRACEPERMIT_ENDPOINT_PRIVATE_KEY_PKCS8_PATH",
  "TRACEPERMIT_CONNECTOR_BEARER_TOKEN",
  "TRACEPERMIT_ENDPOINT_OBSERVATION_DIRECTORY"
]
required = true
startup_timeout_sec = 30
default_tools_approval_mode = "prompt"
```

Start the local gateway before initiating the Codex session. To cleanly disconnect, terminate
the client or close the stdio stream.

## Qualification scope and test boundaries

The distribution package and runtime are qualified under automated test suites with explicit requirement boundaries:

- **FR-012 (Stateful simulator composition):** Qualified via `:endpoint-connector:packageEndpointConnectorDistribution` and `scripts/endpoint-connector-distribution.test.mjs`. Scope is strictly local simulated adapters; remote cloud services remain simulated.
- **SEC-003 (Secret hygiene and exposure prevention):** Qualified via launcher unsetting of ambient JVM options (`JAVA_TOOL_OPTIONS`, `_JAVA_OPTIONS`, `JDK_JAVA_OPTIONS`), verified absence of sensitive tokens in diagnostics, and same-UID POSIX permissions check (`0600`/`0400`) on private key files. Does not provide hardware key protection.
- **SEC-011 (Loopback isolation):** Qualified via `--check` offline verification (zero sockets, zero network traffic) and strict rejection of remote or non-loopback HTTP gateway URLs.
- **SEC-014 (Source attestation verification):** Qualified via `EphemeralSimulatorGatewayMain` (protocol smoke test for transport wire-framing) and `JavaBackendEndpointConnectorE2eTest` (real backend evaluation: invalid-signature and expired JWT rejection, and fail-closed `SOURCE_TRIGGER_NOT_INTERACTIVE` enforcement when caller lacks interactive terminal attachment, preventing unapproved target execution).
- **OPS-002 (Artifact and configuration provenance):** Qualified via schema `1.1` `manifest.json` tracking clean git commit, commit timestamp, Java 21 prerequisite, and per-JAR SHA-256 hashes. Package build fails closed on dirty working trees. Installed E2E qualification requires an explicitly prepared, exact-HEAD connector archive without staged or source fallbacks. Strict provenance fails closed: missing git, non-zero git exit (including exit 73), git command timeouts, and malformed commit outputs immediately fail qualification. Gradle tracks all qualification-affecting values as explicit task inputs (`endpointDistributionArchive` file bytes, `endpointConnectorExpectedHeadCommit` tracking exact git HEAD, and `endpointConnectorRequireInstalledArchive` mode), avoiding broad forwarding of ambient credentials or unrelated system properties. An isolated temporary repository test (`git clone -s -l`) proves that advancing from commit A to commit B without changing archive bytes causes the ordinary non-forced Gradle target to execute (not UP-TO-DATE) and fail closed on stale manifest commit, with forced-failure sensitivity control. This establishes deterministic simulator qualification provenance and caching invalidation, but does not provide production code-signing attestation or external artifact custody guarantees.
- **OPS-004 (Lifecycle and clean shutdown):** Qualified via launcher replacement of shell process (`exec java`), bounded graceful shutdown (SIGTERM -> bounded wait -> SIGKILL), port release verification, and negative controls for child crashes and stalled responses. Node fixture startup and readiness waits are strictly bounded with cleanup registered beforehand, using stable error codes (`ERR_READINESS_TIMEOUT`, `ERR_READINESS_EARLY_EXIT`, `ERR_READINESS_FLOOD`, `ERR_CHILD_NOT_FOUND`, `ERR_CHILD_ALREADY_EXITED`, `ERR_CHILD_EMITTED_ERROR`), memory sliding windows (64KB), total byte flood limits (512KB), and diagnostic redaction (`redactDiagnosticText`) verified against synthetic canary timeout/early-exit probes. Java tar extraction, Linux platform refusal execution, and macOS PTY child process trees are strictly bounded using monotonic elapsed time (`System.nanoTime()`), bounded stream buffers (`BoundedByteArrayOutputStream`), and virtual thread drains. Process tree cleanup samples and tracks owned child handles while parentage exists to handle early root exit without broad host-wide process-name kills, confirms intended grandchild startup, escalates from SIGTERM to SIGKILL, and surfaces unconfirmed lingering processes as explicit failures while preserving primary test exceptions via suppressed exceptions. Note that in POSIX/macOS environments, unmonitored processes that reparent to init (PID 1) before handle observation cannot be discovered without full-host process table scanning; the harness tracks only direct owned descendants to prevent accidental host interference. These guarantees qualify local test harness safety, not production workload supervisory management.

## Related documentation

- [ADR 0055: Source-free endpoint connector distribution](../architecture/0055-source-free-endpoint-connector-distribution.md)
