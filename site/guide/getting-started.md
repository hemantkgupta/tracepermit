---
title: Local POC quickstart
description: Run the supported Java backend, React console, and simulator scenarios from source.
---

# Local POC quickstart

On a second Mac? Use the [fresh MacBook evaluation](/guide/fresh-macbook-evaluation)
for prerequisite checks and a complete synthetic browser request/review/execute
walkthrough. See [what works today](/guide/working-today) to choose a profile.

TracePermit currently runs as a **loopback-only, simulator-only proof of
concept**. There is no published `tracepermit/gateway:latest` image or
production Compose stack. The website is documentation, not a public gateway.

## Prerequisites

- Java 21 JDK
- Node.js 22 or newer, Corepack, and pnpm 11.25.0
- Docker for the PostgreSQL-backed approval scenario and durable tests

## Start the qualified local demo

```bash
git clone https://github.com/hemantkgupta/tracepermit.git
cd tracepermit
corepack enable
pnpm install --frozen-lockfile
pnpm check
pnpm demo
```

Keep that terminal running. The launcher starts the Java backend on loopback,
the React console at `http://127.0.0.1:5173`, and ephemeral local demo trust
material. In a second terminal:

```bash
pnpm demo:run
```

The runner exercises bounded simulated reads, blocked destructive calls,
unknown-tool rejection, endpoint-attested CLI behavior, and the read-only
console. It does not contact a real AWS or Jira account. Run
`pnpm demo:scenario:connector` and `pnpm demo:scenario:cli` for slower,
presenter-led origin and enforcement flows.

See the [numbered scenario runbook](https://github.com/hemantkgupta/tracepermit/tree/main/docs/demo-scenarios)
for exact commands, expected outcomes, and extra requirements.

To make Codex propose the read/block calls through the Java endpoint connector,
follow [Endpoint connector for Codex](/guide/endpoint-connector). That guide
separates endpoint runtime, source tooling and backend requirements; the current
connector is local-only, not a connection to a hosted TracePermit service.

## Hosted ChatGPT qualification

The separate [OAuth-attributed hosted-client
scenario](https://github.com/hemantkgupta/tracepermit/blob/main/docs/demo-scenarios/06-oauth-attributed-hosted-client.md)
requires your own test IdP configuration and OpenAI Secure MCP Tunnel setup.
One Auth0/ChatGPT/tunnel composition was tested with simulated allow and block
actions; this is not an automatic part of the local quickstart and does not
qualify Claude web or production traffic.

Do not point demo tools at real cloud accounts or paste credentials into the
repository, prompts, or screenshots.
