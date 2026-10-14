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

Unknown simulator outcomes remain marked unknown until read-only
reconciliation observes a confirmed result. The worker never replays the
original effect merely because an acknowledgement was lost.

See the [evidence architecture decision](https://github.com/hemantkgupta/tracepermit/blob/main/docs/architecture/0006-append-only-evidence-and-external-anchors.md)
for the binding and fail-closed behavior. No regulatory certification or
compliance guarantee is claimed by the POC.
