# ADR 0001 — Intercept executable actions and preserve evidence boundaries

- Status: accepted for POC
- Date: 2026-10-08

## Context

The product must support unmodified ChatGPT and Claude web clients as well as richer CLI, API, and scheduled origins. These origins expose different provenance. A design that promises identical attribution from every path would be misleading.

## Decision

TracePermit's primary policy enforcement point is a remote MCP/API gateway immediately before a downstream tool adapter. Source hooks may enrich the envelope, and target-side adapters prevent bypass. The canonical contract separates:

1. observed and authenticated facts;
2. client assertions;
3. gateway-derived classification;
4. policy decision and evidence.

The POC stores token hashes and lineage links, never bearer tokens. Later delegation tokens will be short-lived, audience-bound, action-scoped, and linked by parent token ID.

## Consequences

- Web demos are realistic because the provider UI remains unmodified.
- Attribution starts at connector OAuth for web traffic; unavailable upstream internals remain explicitly unknown.
- CLI/API integrations can provide stronger trigger and workload evidence.
- The gateway is enforceable only when agents cannot retain a direct target credential or bypass route.
- The fast path can classify known schemas and evaluate cached policy without a secondary LLM.
