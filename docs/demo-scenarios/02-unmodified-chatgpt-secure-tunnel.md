# Scenario 2: unmodified ChatGPT through the secure tunnel

**Requirements demonstrated:** FR-001, FR-003, FR-010, FR-014, SEC-001,
SEC-003, SEC-008, SEC-011, SEC-012, REL-001, REL-009, OPS-001, OPS-004

## Scenario objective

Show that a normal ChatGPT web conversation can select a TracePermit MCP tool,
send the proposed tool call through an outbound-only OpenAI Secure MCP Tunnel,
and receive a result from the complete Java enforcement path. The read reaches
only the simulator; the known destructive action is blocked before dispatch.

The one-sentence message is:

> The hosted model proposes a tool call, but reachability through the tunnel
> grants no execution authority; Java still authenticates, normalizes,
> classifies, evaluates policy, and verifies a request-bound permit before the
> simulator can run.

## Honest identity statement

This scenario uses a no-auth ChatGPT plugin plus a fixed, locally issued
`chatgpt-readonly` development credential. It proves live client compatibility,
connector attribution, intent enforcement, and end-to-end visibility. It does
**not** prove the identity of the person signed into ChatGPT.

Keep these principals separate:

| Principal or credential        | What it proves                                                         |
| ------------------------------ | ---------------------------------------------------------------------- |
| ChatGPT signed-in account      | Known to ChatGPT; not exported as TracePermit identity in this setup   |
| Tunnel runtime API key         | The local `tunnel-client` may use the configured OpenAI tunnel         |
| Local development MCP bearer   | The fixed simulated web profile accepted by the Java loopback server   |
| Model/orchestrator actor       | An asserted `chatgpt-web` actor in causal lineage, not delegated power |
| Request-bound execution permit | Java policy allowed this exact normalized action for one execution     |

A production identity demo must configure application OAuth/OIDC so
TracePermit independently verifies issuer, audience, subject, client, tenant,
time, and scope mappings. Prompt text, the tunnel ID, and the ChatGPT display
name are not identity evidence.

The implemented upgrade path is
[Scenario 6](06-oauth-attributed-hosted-client.md). It uses the separate
`simulated-oauth-loopback` runtime and a tunnel launcher that does not inject a
shared MCP bearer. Keep this Scenario 2 profile as a transport fallback.

## Data flow

```mermaid
sequenceDiagram
  participant H as Human in normal ChatGPT UI
  participant M as ChatGPT model/orchestrator
  participant O as OpenAI tunnel control plane
  participant T as tunnel-client on Mac
  participant G as Java loopback MCP
  participant P as Policy + permit
  participant S as Simulator
  participant U as React console

  H->>M: Ask for bounded read or simulated termination
  M->>O: Proposed MCP tools/call
  T->>O: Outbound long poll
  O-->>T: Bounded MCP JSON-RPC request
  T->>G: Forward to /mcp + local development bearer
  G->>G: Authenticate before parse; normalize server-owned action
  G->>P: Classify and evaluate policy
  alt bounded log read
    P->>P: Issue request-bound single-use permit
    P->>S: Verify permit and execute simulated read
    S-->>G: Bounded simulated result
  else simulated EC2 termination
    P-->>G: Block; no permit and no simulator call
  end
  G-->>T: Bounded MCP response
  T-->>O: Tunnel response
  O-->>M: Tool result
  G-->>U: Post-enforcement secret-minimized projection
```

## One-time operator setup

Create an OpenAI Secure MCP Tunnel and a runtime API key with only Tunnel Read
and Use permissions. Install a private/developer ChatGPT plugin against that
tunnel with no application authentication for this first scenario. Do not put
the runtime key, tunnel ID, or organization ID in the repository.

Export only local configuration references:

```bash
export TRACEPERMIT_OPENAI_TUNNEL_ID='tunnel_REPLACE_WITH_32_HEX_CHARACTERS'
export TRACEPERMIT_OPENAI_ORGANIZATION_ID='org-REPLACE_ME'
export TRACEPERMIT_OPENAI_TUNNEL_API_KEY_FILE="$HOME/.config/tracepermit/secrets/openai-tunnel-runtime-key"
export TRACEPERMIT_TUNNEL_CLIENT_BIN="$HOME/.local/bin/tunnel-client"
```

The API-key file must be a regular non-symlink file readable only by the
operator. The command passes a `file:` reference to `tunnel-client`; it never
places the key value on the command line or in the repository.

## Run it

In Terminal 1, start the common Java backend and React console:

```bash
pnpm demo
```

In Terminal 2, immediately before the live ChatGPT steps, start the tunnel:

```bash
pnpm demo:tunnel
```

The tunnel command refuses a backend that does not report Java
`simulated-loopback` mode, obtains an owner-only temporary five-minute MCP
credential, starts the official tunnel client, and removes the temporary
credential on exit. Restart this command immediately before each rehearsal so
the bounded credential does not expire mid-demo.

Expected startup diagnostics include the `TracePermit` MCP initialization and
a successful protected-resource metadata fetch. The discovery document is
public compatibility metadata and intentionally contains no authorization
server in this no-auth profile. Do not narrate that successful probe as OAuth
login or user attribution.

## Prompt 1 — allowed bounded read

In the normal ChatGPT conversation, mention the private TracePermit plugin and
send:

```text
Use TracePermit's aws.logs.filter_log_events tool to read at most 2 simulated
events from /app/staging with filterPattern ERROR. Report the policy outcome,
execution status, and whether the result is simulated. Do not use another tool.
```

Expected result:

- ChatGPT selects `aws.logs.filter_log_events`;
- the MCP initialization negotiates the allowlisted `2025-11-25` version used
  by the live OpenAI connector before tool discovery;
- OpenAI correlation values in optional `params._meta` are accepted only as
  untrusted transport metadata and are not used or stored by TracePermit;
- Java reports policy outcome `allow`;
- a request-bound permit is issued, verified, and consumed;
- execution status is `executed` with `mode=simulated`; and
- one new `chatgpt-web` allow row appears in the React console.

## Prompt 2 — blocked destructive intent

Then send:

```text
Use TracePermit's aws.ec2.terminate_instances tool for the simulated instance
i-simulated-chatgpt-demo in us-east-1. This is only a simulator policy test.
Report the policy outcome and execution status; do not claim a real AWS change.
```

Expected result:

- the known tool is strictly normalized and classified as destructive;
- Java reports policy outcome `block` and execution status `denied`;
- no execution permit is issued;
- the simulator receives no termination call; and
- one new `chatgpt-web` blocked/critical row appears in the React console.

## What to show live

1. ChatGPT is the ordinary web UI; there is no modified browser or prompt
   simulator.
2. The tunnel log startup probe names the server `TracePermit`, not `mcp-stub`.
3. The console shows the same two final outcomes after Java enforcement.
4. The allowed action has a simulated target result; the blocked action has no
   target dispatch.
5. The identity label is a simulated development subject. Say explicitly that
   production user attribution requires application OAuth/OIDC.
6. The p95 card names its current-run sample count; seeded historical rows do
   not contribute to the displayed live latency.

## Failure demonstrations

- Stop `pnpm demo:tunnel`: ChatGPT loses tool reachability; the Java listener is
  still not publicly exposed.
- Stop the Java backend: the tunnel startup/readiness path cannot claim a ready
  MCP server.
- Wait beyond the credential lifetime: Java returns authentication failure;
  policy and simulator are not reached. Restart the tunnel command to renew it.
- Ask for an unknown tool: the MCP adapter rejects it before policy and no
  policy-decision row is created.
- If plugin creation stops before `tools/list`, compare the requested and
  selected MCP protocol versions. TracePermit accepts and echoes
  `2025-11-25`; returning an unrequested fallback version is a compatibility
  failure, not an authorization decision.
- If a valid live call returns `INVALID_TOOL_ARGUMENTS`, confirm that the
  client supplied only `{name, arguments, _meta?}` at the call-envelope level.
  `_meta` must be an object and is ignored; other call fields remain rejected.

## Code map

| Responsibility                  | Implementation                                                                                    |
| ------------------------------- | ------------------------------------------------------------------------------------------------- |
| Safe tunnel launcher            | `scripts/run-openai-tunnel-demo.sh`                                                               |
| Java loopback authentication    | `java/adapters-http/src/main/java/io/tracepermit/adapters/http/LoopbackDevelopmentMcpServer.java` |
| Strict tool normalization       | `java/adapters-remote/src/main/java/io/tracepermit/adapters/remote/EnforcingMcpDispatcher.java`   |
| Policy and permit orchestration | `java/application/src/main/java/io/tracepermit/application/SecureExecutionService.java`           |
| Simulator boundary              | `java/simulator/src/main/java/io/tracepermit/simulator/StatefulTargetSimulator.java`              |
| Post-enforcement projection     | `java/adapters-http/src/main/java/io/tracepermit/adapters/http/DemoControlPlaneData.java`         |

## Honest limitations

- The ChatGPT login identity is not exported or verified in this no-auth
  profile.
- The injected MCP bearer is static inside one tunnel-client process and lasts
  five minutes; this is demo packaging, not production token refresh.
- The transport is live, but downstream AWS data and effects remain simulated.
- Claude and other hosted clients require separate live qualification.
- Production still needs app OAuth/OIDC, sender constraint, shared abuse and
  replay state, durable edge telemetry, and target egress controls.
