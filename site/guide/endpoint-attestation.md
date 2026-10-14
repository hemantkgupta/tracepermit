---
title: Endpoint Source Attestation
description: Independent parent-process and device verification for CLI agents.
---

# Endpoint Source Attestation

Most API gateways and proxies authenticate requests solely based on an HTTP header (such as a Bearer token or an `X-User-ID` header).

However, in an agentic environment, an attacker running malicious code inside a container or script can easily forge HTTP headers or reuse leaked tokens.

TracePermit introduces **Independent Endpoint Source Attestation**.

---

## How It Works

Before any Model Context Protocol (`tools/call`) frame leaves a developer's workstation or CI runner, the **Java Endpoint Connector** independently probes the local OS runtime:

```
[ Developer Terminal / Cursor ]
               │ (Invokes MCP tool via stdio)
               ▼
┌──────────────────────────────────────────────┐
│       Java Endpoint Connector                │
│                                              │
│  1. OS process observation:                  │
│     • Parent PID & executable path stability │
│     • Executable SHA-256 hash                │
│     • Process start time                     │
│     • Terminal (TTY) attachment              │
│                                              │
│  2. Ed25519 Cryptographic Attestation:       │
│     • Signs facts using local endpoint key   │
│     • Mints Compact JWS Attestation Token    │
└──────────────────────────────────────────────┘
               │
               ▼ (POST /mcp with Attestation Header)
[ TracePermit Gateway Boundary ]
```

---

## The Attestation Claims

The development attestation binds observed facts to one MCP request and
authenticated connector credential. It supports these claims at their
measured assurance level:

- **The Observed Executable:** Name, path, and fingerprint of the observed
  process. A hash alone does not prove vendor signing or genuine app identity.
- **Environment Assurance:** Whether the command was executed in an interactive developer terminal or an unattended automated loop.
- **Device Identity:** A configured development device/key trust tuple. The
  POC does not include managed hardware-backed enrollment.

When the gateway receives the request, it verifies the Ed25519 signature,
exact request and credential binding, time, and replay state before dispatch.
This is not a guarantee against a compromised endpoint or a production EDR
replacement.
