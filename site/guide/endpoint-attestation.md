---
title: Endpoint Source Attestation
description: Independent parent-process and device verification for CLI agents.
---

# Endpoint Source Attestation

For prerequisites, current capabilities and the two-terminal Codex setup, start
with [Endpoint connector for Codex](/guide/endpoint-connector). This page explains
the source-proof mechanism; it is not a remote-backend installation guide.

A verified bearer credential identifies an authenticated request context; it
does not by itself identify which local executable proposed the request.
Caller-supplied labels such as `X-User-ID` cannot fill that gap without a
trusted verification path. TracePermit adds **request-bound endpoint source
attestation**, verified separately by the gateway at development assurance.

---

## How It Works

For requests routed through its configured stdio server, the **Java Endpoint
Connector** probes the local OS runtime before forwarding to the numeric-loopback
gateway. It does not intercept every request leaving a workstation or CI runner.
The named native-client qualification is Codex on macOS, not Cursor or generic CI:

```
[ Native Codex CLI on macOS ]
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
- **Terminal Evidence:** Measured terminal attachment. Absence of a terminal
  leaves trigger mode unknown; it does not prove a scheduled job. Attachment
  does not prove that a human chose or approved the action.
- **Device Identity:** A configured development device/key trust tuple. The
  POC does not include managed hardware-backed enrollment.

When the gateway receives the request, it verifies the Ed25519 signature,
exact request and credential binding, time, and replay state before dispatch.
This is not a guarantee against a compromised endpoint or a production EDR
replacement.
