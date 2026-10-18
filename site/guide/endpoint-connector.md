---
title: Endpoint connector for Codex
description: What runs on your Mac, how the Java connector reaches the local TracePermit backend, and how to verify simulator-only allow and block results with Codex.
aside: false
---

# Endpoint connector for Codex

The TracePermit endpoint connector is a **Java 21 MCP stdio application** that
Codex starts on your workstation. It observes the calling process, signs a
request-bound source proof, and forwards the request to the TracePermit backend.
The backend—not the connector or model—makes the policy decision before any
simulated effect.

[Requirements](#is-java-the-only-requirement) · [Setup](#1-prepare-the-mac) ·
[Configuration](#configuration-reference) · [Troubleshooting](#troubleshooting-and-shutdown)

::: warning Current release boundary
This is a **source-based, same-Mac, simulator-only evaluation**. The connector
accepts only `http://127.0.0.1:<explicit-port>/mcp`; the supplied Codex launcher
uses port `8080`. Remote backend URLs, including HTTPS URLs, are rejected.
`tracepermit.org` serves documentation, not an enforcement API.
:::

## Where it fits

<div class="flow-diagram" tabindex="0" role="region" aria-label="Endpoint connector and backend responsibilities, horizontally scrollable">

![Codex starts the Java connector over stdio. The connector observes and signs, then sends a credential and source proof over direct loopback HTTP to the Java gateway. Only a backend policy allow and verified permit can reach the safe simulator. A block has no dispatch; the React console shows bounded evidence.](/images/endpoint-connector-overview.svg)

</div>

[Open full-size diagram](/images/endpoint-connector-overview.svg)

The connector is not a system-wide network proxy, an EDR agent, or a replacement
for the backend. It does not intercept other MCP servers, shell commands, or
direct cloud calls. Credential custody and egress controls are still necessary
to prevent an independently credentialed client from bypassing TracePermit.

## Is Java the only requirement?

**For the connector's built application, Java is the runtime. For today's
supported Codex setup, Java alone is not enough.**

| Component                                | What is needed today                                                                                                                                        |
| ---------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Connector application                    | Java 21, its generated launcher and the complete `lib/` directory; macOS process observation also calls `/bin/ps`                                           |
| Source build and supplied Codex launcher | Java 21 **JDK**, Node.js 22+, Corepack, pnpm **11.25.0**, Git, Bash and curl; the checked-in Gradle wrapper builds the Java application                     |
| Local backend/demo                       | The same checkout and toolchain; OpenSSL, lsof and ordinary macOS utilities; `pnpm demo` builds the connector and starts the Java backend and React console |
| Codex client                             | A native Codex CLI executable, an available model, and your own Codex account sign-in; model use may incur your normal provider costs                       |
| Docker / PostgreSQL                      | **Not needed for this read/block path.** Needed for separate durable-approval and PostgreSQL qualification paths                                            |

The build creates
`java/endpoint-connector/build/install/endpoint-connector/` with `bin/` and
`lib/`. It does not bundle a JVM. The repository launcher refuses an unbuilt
connector with `BUILD_REQUIRED`.

This is not yet a published, independently qualified connector installer.
Copying the built directory does not reproduce the supplied Codex preflight,
backend enrollment or token setup. The separate **simulator approval
distribution** is a backend package, not this connector's installer.

## What works, and what is not claimed

| Capability                       | Current boundary                                                                                                                                                                  |
| -------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Local process attribution        | Measures the immediate parent executable and its ancestor, hashes executables, observes start time and terminal attachment, and rechecks the caller's stability before forwarding |
| Request-bound source proof       | Ed25519 proof binds canonical MCP `method` + `params` and the connector credential fingerprint; JSON-RPC request ID is deliberately excluded                                      |
| Independent backend verification | Gateway verifies the configured issuer/key/device trust tuple, signature, audience, time, request/credential binding and single use before dispatch                               |
| Protocol mediation               | Sequential, newline-delimited JSON-RPC: `initialize`, `notifications/initialized`, `tools/list`, `tools/call`; bounded input and output, not arbitrary MCP passthrough            |
| Codex evaluation                 | Named macOS rehearsal: Codex CLI **0.162.1**, `gpt-5.6-sol`, on **2026-10-15**; not a claim for every client, model, version or OS                                                |
| Key and credential handling      | Owner-only development key file; short-lived synthetic bearer token in the child environment, not saved config or command arguments; same-user compromise remains a risk          |
| Remote / managed deployment      | Remote connector transport, managed enrollment/revocation, protected key custody, distributed replay and full anti-bypass are **not provided by this local profile**              |

A terminal attachment is an observed signal, not proof that a human approved
each action. An executable hash is not vendor-signature verification. OS,
device, model-provider and enterprise-account identities are different claims.
See [source-attestation details](/guide/endpoint-attestation).

## 1. Prepare the Mac

Install the prerequisites above and sign in to Codex separately. Use a native
CLI executable, not a JavaScript MCP forwarding process. A newer CLI can be
tried, but must be requalified; the dated version above is the recorded result.

```bash
git clone https://github.com/hemantkgupta/tracepermit.git
cd tracepermit
corepack enable
pnpm install --frozen-lockfile
```

Use one checkout for both terminals. If Java discovery selects another version,
set `TRACEPERMIT_JAVA_HOME` to your actual JDK 21 installation in **both**
terminals. Do not copy another machine's path or private key. Initial dependency
downloads and Codex model access require network connectivity; this is not an
offline client qualification.

## 2. Start the TracePermit backend

In **Terminal A**, from that checkout:

```bash
pnpm demo
```

Leave it running until it reports ready. It builds the connector, creates an
ephemeral Ed25519 key, and gives the backend only its public key and matching
issuer/key/device metadata. The private key and session descriptor stay in the
checkout's owner-only `.cache/demo/` directory.

| Address                        | Purpose                                                             |
| ------------------------------ | ------------------------------------------------------------------- |
| `http://127.0.0.1:8080/mcp`    | Connector → Java gateway; authenticated requests with source proofs |
| `http://127.0.0.1:8080/ready`  | Readiness, not a tool endpoint                                      |
| `http://127.0.0.1:8090/api/v1` | Local Java control-plane API                                        |
| `http://127.0.0.1:5173`        | Read-only React action/evidence console                             |

This fixture uses simulated AWS tools and in-memory demo state. It is not the
durable approval inbox, and it needs no real AWS account or cloud credentials.

## 3. Connect Codex

In **Terminal B**, open an attached interactive terminal in the **same checkout**:

```bash
pnpm demo:endpoint:codex --check
pnpm demo:endpoint:codex
```

`--check` makes online, read-only readiness/runtime requests. It checks the
local session/key, matching Git build, Java 21 backend and installed native
client; it issues no token and starts no model or MCP tool. These convenience
checks are not cryptographic backend authentication.

The second command obtains one **300-second synthetic `cli-readonly` token**
and starts Codex with invocation-only settings. It does not write global
`~/.codex/config.toml`, call a tool automatically, refresh tokens, or retry tool
calls. The launcher currently selects `gpt-5.6-sol`; stop if it is unavailable
rather than treating another model as already qualified.

Your Codex account login is separate from this TracePermit synthetic identity.
No Gmail login, enterprise SSO, real AWS credential or TracePermit hosted
account is required by this fixture. If Codex itself requires sign-in, complete
that through Codex—not in a prompt or TracePermit configuration.

In Codex, run:

```text
/mcp verbose
```

Confirm `tracepermit_endpoint` is connected with these **two tools**:

- `aws.logs.filter_log_events`
- `aws.ec2.terminate_instances`

`Auth: Unsupported` describes the local stdio client's OAuth surface. It does
not remove gateway authentication: Java sends the bearer credential upstream.
Other installed MCP servers are not disabled. Select the exact server and tool;
if its identity is ambiguous, stop instead of guessing. A catalog is not an
execution result.

### Configuration reference

The supported launcher supplies these values; you do **not** need to export
them manually for the path above.

| Connector environment variable                | Supplied value or role                                                                                 |
| --------------------------------------------- | ------------------------------------------------------------------------------------------------------ |
| `JAVA_HOME`                                   | Selected Java 21 directory, explicitly forwarded to the child                                          |
| `TRACEPERMIT_GATEWAY_URL`                     | `http://127.0.0.1:8080/mcp`; numeric loopback only, no redirects or ambient proxy                      |
| `TRACEPERMIT_ENDPOINT_ISSUER`                 | `tracepermit-endpoint:demo-local`                                                                      |
| `TRACEPERMIT_ENDPOINT_KEY_ID`                 | `endpoint-key-demo-local`                                                                              |
| `TRACEPERMIT_ENDPOINT_DEVICE_ID`              | `device-demo-local`                                                                                    |
| `TRACEPERMIT_ENDPOINT_PRIVATE_KEY_PKCS8_PATH` | Owner-readable Ed25519 PKCS#8 DER key at `.cache/demo/endpoint-key.pk8`; never publish its contents    |
| `TRACEPERMIT_CONNECTOR_BEARER_TOKEN`          | Fresh synthetic bearer, passed only through the environment; never paste it into TOML, prompts or logs |

The backend is configured separately with the same issuer/key/device and
`TRACEPERMIT_ENDPOINT_PUBLIC_KEY_SPKI_BASE64`. Client requests cannot enroll a
key. Do not point a connector at a different checkout's gateway.

The launcher's Codex mapping is:

- MCP server: `tracepermit_endpoint`, **stdio**, command
  `scripts/run-java-endpoint-connector.sh` resolved to an absolute path.
- `env_vars`: forwards the names above; no inline secret values.
- `required=true`, `startup_timeout_sec=30`,
  `default_tools_approval_mode="prompt"`; read-only command sandbox.

These settings follow [OpenAI's MCP configuration reference](https://learn.chatgpt.com/docs/extend/mcp#stdio-servers).
Codex's tool confirmation is separate from TracePermit policy and is not a
durable reviewer approval. Do not replace this path with a direct Codex HTTP
connection: that would omit this connector's local process observation and
source proof. For advanced manual composition, use the
[Java connector runbook](https://github.com/hemantkgupta/tracepermit/blob/main/docs/runbooks/java-mcp-endpoint-connector.md).

## 4. Verify one allowed read

Prepare prompts before launching; the token lasts five minutes. Submit:

```text
Use only the tracepermit_endpoint MCP server.
Call aws.logs.filter_log_events exactly once with:
- logGroupName: /app/staging
- filterPattern: ERROR
- limit: 2

Report the exact policy outcome, reason code, execution status and simulated
flag returned. Include eventId/traceId only if present; invent no fields.
Do not retry, run commands, call the termination tool or any other tool.
Do not display credentials or source proofs. If anything fails, stop and
report the exact error. Stop after reporting the result.
```

On the configured fixture, expect:

| Field            | Expected returned value |
| ---------------- | ----------------------- |
| Policy outcome   | `allow`                 |
| Reason code      | `BOUNDED_LOG_READ`      |
| Execution status | `executed`              |
| Simulated        | `true`                  |

Zero matching log events is valid. This is a simulator result, not a live
CloudWatch query.

## 5. Verify one blocked termination

This is a **simulator-only negative test**, not a request to contact AWS:

```text
Use only the tracepermit_endpoint MCP server.
This is a simulator-only policy-block test.
Call aws.ec2.terminate_instances exactly once with:
- instanceIds: ["i-simulated-native-codex"]
- region: us-east-1

Report the exact policy outcome, reason code, execution status and simulated
flag returned. Do not assume the expected outcome. Do not retry, run commands,
contact real AWS or call another tool. Do not display credentials or source
proofs. If anything fails, stop and report the exact error.
Stop after reporting the result.
```

Expect `block`, `AGENT_EC2_TERMINATION_BLOCKED`, `denied`, `simulated=true`.
Codex may label this structured policy rejection **Failed**; inspect the
returned decision rather than treating it as a broken connection.

Open the React console and find the **fresh CLI-origin** events, not seeded
history. Its execution projections are `succeeded` for the allowed read and
`not_executed` for the policy block. A console projection is not an independent
live dispatch counter. Record the build, client/model version and returned
fields if retaining proof; exclude credentials and raw signed proofs.

## Troubleshooting and shutdown

| Symptom                             | Safe next step                                                                                                                                                                       |
| ----------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `BUILD_REQUIRED`                    | Build with `pnpm build:java-endpoint`, then start the normal demo; do not run a stale binary                                                                                         |
| `BACKEND_BUILD_MISMATCH`            | Stop Terminal A's demo with Ctrl+C and restart from the same checkout; a new commit requires restart                                                                                 |
| `EXPIRED_ACCESS_TOKEN`              | Authentication failed **before policy**. Exit Codex with `/exit`, relaunch Terminal B's command, then explicitly choose the next call; no automatic retry                            |
| `ENDPOINT_OBSERVATION_UNAVAILABLE`  | Check the native executable, JDK 21 and real attached terminal. Do not fabricate process/TTY evidence or weaken the observer                                                         |
| Zero tools or ambiguous tools       | Verify the same-checkout `pnpm demo` profile and exact `tracepermit_endpoint` catalog. Do not use `pnpm demo:approvals:codex`, which exposes a separate three-tool approval workflow |
| Port already in use / stale session | Stop only the fixture you started. Inspect the exact stale files if necessary; do not kill all listeners or reuse keys from another run                                              |

Exit Codex first, then press Ctrl+C in Terminal A. Normal shutdown removes the
ephemeral signing key and session descriptor; backend restart invalidates the
old development token. Do not archive `.cache/demo/` as proof. Optional process
observation capture is off by default and has separate retention rules in the
[private capture runbook](https://github.com/hemantkgupta/tracepermit/blob/main/docs/runbooks/native-endpoint-observation-rehearsal.md).

## Evidence and the next product boundary

The [dated Codex proof](https://github.com/hemantkgupta/tracepermit/blob/main/docs/demo-scenarios/evidence/codex-native-endpoint-2026-10-15.md)
records the named model-selected read/block pair. Automated
`pnpm test:endpoint-launcher` and `pnpm test:endpoint-connector-e2e` checks
exercise launcher/preflight and actual Java stdio-to-gateway behavior. The
automated test uses a controlled client/process fixture, not a new Codex model
qualification on your Mac.

The release direction is **same laptop first, remote backend next**. To make
the connector independently installable for others, the next bounded
milestone is a versioned connector bundle with a no-Node runtime launcher,
checksums, safe configuration validation and a fresh-Mac qualification. Remote
customer-owned backends need a **separate** reviewed TLS/authentication,
enrollment, key-custody and replay design. Neither is claimed by this page.
