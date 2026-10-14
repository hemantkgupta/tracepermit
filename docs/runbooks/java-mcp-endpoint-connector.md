# Java MCP endpoint connector development runbook

**Scope:** loopback development only

**Real product status:** configuration example only; model-provider CLI
compatibility with this connector has not been established end-to-end.

## Safety boundary

This runbook starts only the local Java backend, Java connector, and safe
simulator. Do not add AWS, Jira, OAuth, model-provider, or enterprise
credentials. The local file signing key and environment Bearer token are
development mechanisms: another same-user process may be able to inspect them.
They are not managed enrollment or protected key custody.

The connector refuses non-loopback gateway URLs, redirects, and arbitrary MCP
methods. The gateway trusts only a public Ed25519 key configured before
startup. No request can enroll a key.

## Prerequisites and build

- Node.js 22 or newer
- pnpm 11.25.0 through Corepack
- Java 21
- OpenSSL for the manual development-key example

From the repository root:

```bash
corepack enable
pnpm install --frozen-lockfile
pnpm build:java-endpoint
```

The executable distribution is created at
`java/endpoint-connector/build/install/endpoint-connector`. The repository
launcher is `scripts/run-java-endpoint-connector.sh`.

## Automated safe demonstration

Run the production-shaped subprocess test:

```bash
pnpm test:endpoint-connector-e2e
```

It generates an Ed25519 key at runtime, starts the Java loopback MCP server with
only the public key, obtains a short-lived development CLI token, launches the
real stdio connector path, and sends the supported JSON-RPC sequence.
It asserts:

- the bounded simulated log read is allowed and executed by the simulator;
- the known simulated EC2 termination reaches policy and is denied before the
  executor;
- an unknown tool is rejected by the MCP boundary; and
- captured gateway/connector output contains neither the Bearer token nor the
  private-key encoding.

The test closes stdin and requires a clean connector exit. Signing keys are
generated in memory and are never committed.

## Manual loopback composition

Create temporary development signing material outside the repository:

```bash
TRACEPERMIT_DEMO_DIR="$(mktemp -d)"
openssl genpkey -algorithm ED25519 -outform DER \
  -out "$TRACEPERMIT_DEMO_DIR/endpoint-key.pk8"
chmod 600 "$TRACEPERMIT_DEMO_DIR/endpoint-key.pk8"
TRACEPERMIT_ENDPOINT_PUBLIC_KEY_SPKI_BASE64="$(
  openssl pkey -inform DER \
    -in "$TRACEPERMIT_DEMO_DIR/endpoint-key.pk8" \
    -pubout -outform DER | base64 | tr -d '\n'
)"
export TRACEPERMIT_DEMO_DIR TRACEPERMIT_ENDPOINT_PUBLIC_KEY_SPKI_BASE64
```

In the backend terminal, configure only public enrollment metadata. The Java
bootstrap always binds to loopback:

```bash
export TRACEPERMIT_ENDPOINT_ISSUER=tracepermit-endpoint:device-simulated-local
export TRACEPERMIT_ENDPOINT_KEY_ID=endpoint-key-local
export TRACEPERMIT_ENDPOINT_DEVICE_ID=device-simulated-local
export TRACEPERMIT_ENDPOINT_PUBLIC_KEY_SPKI_BASE64
pnpm dev:backend
```

Partial or malformed enrollment configuration stops startup. No supported
configuration permits a non-loopback bind.

In the client terminal, obtain the short-lived CLI profile without printing it
and configure the connector:

```bash
export TRACEPERMIT_CONNECTOR_BEARER_TOKEN="$(
  curl --silent --show-error --fail \
    --request POST \
    --header 'content-type: application/json' \
    --data '{"grant_type":"urn:tracepermit:params:oauth:grant-type:development-profile","profile":"cli-readonly"}' \
    http://127.0.0.1:8080/dev/oauth/token |
    node -e 'let value=""; process.stdin.on("data", chunk => value += chunk); process.stdin.on("end", () => process.stdout.write(JSON.parse(value).access_token));'
)"
export TRACEPERMIT_GATEWAY_URL=http://127.0.0.1:8080/mcp
export TRACEPERMIT_ENDPOINT_ISSUER=tracepermit-endpoint:device-simulated-local
export TRACEPERMIT_ENDPOINT_KEY_ID=endpoint-key-local
export TRACEPERMIT_ENDPOINT_DEVICE_ID=device-simulated-local
export TRACEPERMIT_ENDPOINT_PRIVATE_KEY_PKCS8_PATH="$TRACEPERMIT_DEMO_DIR/endpoint-key.pk8"
```

Do not add the Bearer token or private key to source files, shell scripts, CLI
configuration, logs, or evidence. Restarting the backend invalidates the
development token.

## MCP client configuration example — not product-tested

The command below illustrates stdio registration for Codex CLI. The connector
subprocess and native macOS process/terminal observation are tested, but a
Codex model session against this connector has not been qualified. This is a
configuration example, not a compatibility claim:

```bash
codex mcp add tracepermit -- "$(pwd)/scripts/run-java-endpoint-connector.sh"
```

Launch that CLI from the client terminal where the connector environment above
is already present. Do not use `--env` to persist the Bearer token or private
key material in client configuration. Confirm the product actually inherits
the required variables and uses newline-delimited stdio before declaring the
version supported.

Remove the development registration after testing:

```bash
codex mcp remove tracepermit
```

## Protocol and diagnostics

The connector accepts one UTF-8 JSON object per newline, up to 64 KiB, for
`initialize`, `notifications/initialized`, `tools/list`, and `tools/call`.
It is sequential and preserves string/integer IDs. Blank lines, CRLF, a final
frame without newline, and EOF are handled deterministically. Stdout is JSON-
RPC only. Stderr contains bounded `TracePermit endpoint connector: CODE`
diagnostics and never raw requests, responses, credentials, or proofs.

## Cleanup

Stop the gateway and client, unset the development variables, and remove the
temporary directory using its exact printed path. Do not reuse this key for a
later run.

## Production gaps

Before a managed pilot, replace the environment credential and local file key
with authenticated enterprise identity and protected signing; add device/key
enrollment, revocation, durable distributed replay, executable allowlisting and
platform signature checks, multi-tenant isolation, telemetry, and target
credential/egress controls. Independently qualify each MCP product/version.
