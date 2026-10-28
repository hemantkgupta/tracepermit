---
title: Bounded evidence and external anchoring
description: What the POC records and what an independently verified receipt proves.
---

# Bounded evidence and external anchoring

TracePermit records bounded observed-action, policy, permit, execution, and
outcome events without storing reusable bearer credentials or raw target
results in the console read model. Hash-linked evidence makes local
alteration detectable when the chain is verified; it is not itself an
enforcement control.

The Java PostgreSQL profile includes a leased evidence outbox and an
independently verified Ed25519 receipt bound to the tenant, outbox item,
stream, event, and hash. The publisher and anchor are injected simulator/test
ports in this POC: **no production external audit sink or anchor key is
configured**. A receipt proves the configured signer acknowledged that exact
item; it does not prove a real AWS/Jira side effect occurred.

## The Leased Evidence Outbox Lifecycle

To decouple runtime agent responsiveness from external audit service latency or outages, TracePermit implements the **Transactional Outbox Pattern** ([ADR 0019](https://github.com/hemantkgupta/tracepermit/blob/main/docs/architecture/0019-leased-outbox-and-external-evidence-receipts.md)):

```
Synchronous Path:
   Agent MCP Tool Dispatch
             │
             ▼
   Local PostgreSQL Transaction
      ├── 1. Append evidence event
      └── 2. Insert outbox row (status: ready, secret-free payload hash)
             │
             ▼ (Transaction commits in ~2ms. Zero WAN network locks held.)

Asynchronous Path:
   EvidenceOutboxPublisher (Background Worker)
             │
             ├── 1. Claim batch of outbox rows with bounded lease (<= 5 min)
             ├── 2. Publish secret-free hash to External Anchor Port (KMS/HSM/WORM)
             │      (Stable outboxId acts as idempotency key)
             ├── 3. Receive signed Ed25519 EvidenceAnchorReceipt
             ├── 4. Verify signature against independent external public key
             └── 5. Commit transition: mark outbox row published with receipt
```

### Key Integrity Invariants:

1. **Decoupled Availability:** An external audit sink downtime never prevents legitimate agent executions from proceeding locally.
2. **Zero Secret Leakage:** The external anchor receives only bounded event IDs, sequence numbers, and canonical SHA-256 hashes (`payloadHash`). Raw credentials and tool arguments are never transmitted externally.
3. **Receipt Binding:** The receipt must explicitly bind `tenantId`, `sequenceNumber`, `eventId`, and `payloadHash`. A row is never marked published without a cryptographically verified receipt.

Unknown simulator outcomes remain marked unknown until read-only
reconciliation observes a confirmed result. The worker never replays the
original effect merely because an acknowledgement was lost.

See the [evidence architecture decision](https://github.com/hemantkgupta/tracepermit/blob/main/docs/architecture/0006-append-only-evidence-and-external-anchors.md)
for the binding and fail-closed behavior. No regulatory certification or
compliance guarantee is claimed by the POC.
