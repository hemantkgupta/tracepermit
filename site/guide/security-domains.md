---
title: Security Domain Map
description: Nine security domains mapped to what the TracePermit simulator POC implements today, its optional in-tree foundations, and its future deployment gates.
---

# Security Domain Map

TracePermit's [security threat model](https://github.com/hemantkgupta/tracepermit/blob/main/docs/specs/10-security-threat-model.md) maintains a register of adversarial threats (T-001 through T-044), trust-boundary controls, a fail-safe matrix, and release gates. That register is a **security-requirements baseline**: it states the controls each boundary is designed to need and the residual concern that remains. It is not a status report. For what actually runs, the [current-state baseline](https://github.com/hemantkgupta/tracepermit/blob/main/docs/specs/03-current-state-baseline.md), the source, and the tests are the authority — a target requirement is not an implementation claim.

This page maps nine security domains to three things: what the simulator POC **implements today** (with the enforcing component and test evidence), what exists only as an **optional in-tree foundation**, and what stays a **future deployment gate**. Each domain ends with its single most important residual limit.

**As of:** main `3ba8e09` (2026-10-19). The [traceability matrix](https://github.com/hemantkgupta/tracepermit/blob/main/docs/specs/12-traceability-matrix.md) maps requirements to code and tests.

## Status vocabulary

- **Implemented (tested):** code in the public tree with passing automated tests for the named simulator composition. A passing test proves that composition — not deployment to an enterprise network, and not control over arbitrary agent tools.
- **Optional foundation:** an in-tree library or adapter with tests that default launchers do not compose and that no operated service exposes.
- **Live-qualified (named profile):** one stated third-party client/identity profile exercised on a stated date; no broader compatibility is implied.
- **Future gate:** a designed control that must exist before production use; not implemented for real targets.

## The nine domains at a glance

| #   | Domain                             | Implemented now (simulator POC)                                                                | Optional foundation                                            | Future deployment gate                                                    |
| --- | ---------------------------------- | ---------------------------------------------------------------------------------------------- | -------------------------------------------------------------- | ------------------------------------------------------------------------- |
| 1   | Identity, tokens & delegation      | Dev/browser/hosted-profile token verification; endpoint source proofs; local delegation chains | Hardened remote-edge certificate binding (tests, no lifecycle) | Managed enrollment/key custody; production OIDC/IdP breadth               |
| 2   | Request binding & canonicalization | Versioned canonical action hash bound to policy, approval, and single-use permits              | Language-neutral contract artifacts and vectors                | Cross-language canonicalization qualification for future profiles         |
| 3   | Replay, concurrency & atomicity    | Atomic permit/approval claims (durable profile); bounded process-local replay stores           | PostgreSQL durable stores; transactional outbox with leasing   | Durable, shared, cross-restart replay and abuse state                     |
| 4   | Fail-safe design                   | Readiness gates and fail-closed limiters on tested ports; required pre-dispatch telemetry      | Component fail-safe library behavior                           | Production health probes and shared abuse-state availability              |
| 5   | API & network attack surface       | Numeric-loopback transport; SSRF-safe configured destinations; hardened-edge ports (tested)    | Remote-edge rate/readiness ports (loopback demo)               | Host-wide egress enforcement; real credential broker; shared abuse state  |
| 6   | LLM/agent-specific security        | Structured-action evaluation; deterministic injection classification; frozen tool registry     | One allowlisted sanitizer transformer                          | Reviewed production transformer registry; live-model qualification        |
| 7   | Audit, evidence & non-repudiation  | Bounded redaction; append-only hash-linked evidence; outbox receipts (tested)                  | Receipt-verification and checkpoint interfaces                 | Independently operated anchor service; KMS/HSM signing custody; retention |
| 8   | Session & browser security         | Local same-origin browser approval profile; requester/reviewer separation with atomic claim    | Browser profile (synthetic IdP)                                | Public TLS edge; external browser IdP; host-wide anti-bypass              |
| 9   | Threat modeling & security testing | Automated suites for the named compositions; two named live-qualified profiles (dated)         | Threat register and release-gate process                       | Penetration test, authorization matrices, per-adapter threat review       |

## 1. Identity, tokens & delegation

**Implemented (tested):**

- The hosted MCP origin runs a Java OAuth/JWKS verifier — issuer, audience, client, tenant, scope, and time checks plus protected-resource discovery — with server-owned action normalization. One Auth0 + ChatGPT + private-tunnel profile was **live-qualified on 2026-10-11**; other identity providers, Claude web, and public production runtimes are not qualified. Evidence: [OidcAccessTokenVerifierTest](https://github.com/hemantkgupta/tracepermit/blob/main/java/identity/src/test/java/io/tracepermit/identity/OidcAccessTokenVerifierTest.java), [OAuthProtectedResourceMetadataTest](https://github.com/hemantkgupta/tracepermit/blob/main/java/identity/src/test/java/io/tracepermit/identity/OAuthProtectedResourceMetadataTest.java).
- The browser approval profile signs in with authorization code + PKCE + state + nonce against a synthetic, test-classpath-only identity provider on loopback. Evidence: [BrowserIdTokenVerifierTest](https://github.com/hemantkgupta/tracepermit/blob/main/java/identity/src/test/java/io/tracepermit/identity/BrowserIdTokenVerifierTest.java).
- Scheduled-origin composition verifies an operator-trusted signed workload identity and a complete, parent-linked, monotonically narrowing Ed25519 delegation chain — a child can never broaden scope, resources, audience, or lifetime (T-004, T-026). The qualified path is a local simulator composition; the trust and replay stores are in memory. Evidence: [ScheduledWorkloadVerifierTest](https://github.com/hemantkgupta/tracepermit/blob/main/java/identity/src/test/java/io/tracepermit/identity/ScheduledWorkloadVerifierTest.java).
- The endpoint connector issues request-bound, short-lived Ed25519 source proofs that the gateway verifies (T-019, T-020). The development key provider and process observer derive these facts; **a source proof does not authorize an action**, and it is not managed-device or hardware-backed attestation. Evidence: [SourceAttestationVerifierTest](https://github.com/hemantkgupta/tracepermit/blob/main/java/identity/src/test/java/io/tracepermit/identity/SourceAttestationVerifierTest.java). See [Endpoint Source Attestation](endpoint-attestation.md).

**Optional foundation:** the hardened remote edge verifies RS256 tokens plus an issuer-signed RFC 8705 certificate thumbprint matched against the direct TLS peer before parsing (T-003, T-030). Automated tests cover the matching; certificate custody, revocation, and connector lifecycle remain future work.

**Residual limit:** same-Mac development observation is not protected managed attestation and not universal interception — a same-user process can read the same environment and files the connector reads, and no production enrollment exists (T-001's residual concern is trusted-adapter compromise).

## 2. Request binding & canonicalization

**Implemented (tested):** every action crosses the adapter boundary as a **versioned canonical action** — tool, normalized arguments, target/resource, tenant, subject, actor, trigger, and lineage. Policy identity rides **outside** the action payload: the strict intercepted-action v1 schema is closed (`additionalProperties: false`) and has no policy field, so no supported contract path adds or changes a policy version inside the action. Instead, the execution permit signs the canonical action hash and the decision's `policyId`/`policyVersion` as separate claims, and the durable approval binding records policy version alongside the action hash. The policy decision, approval, and permit all bind to that canonical hash, and the executor recomputes it before consuming authority (T-005). The language-neutral contract artifacts and frozen v1 canonicalization vectors keep Java and the checked-in contracts aligned; an integer-format change cannot silently alter signed v1 bytes (T-044). Evidence: [PortableContractsTest](https://github.com/hemantkgupta/tracepermit/blob/main/java/conformance/src/test/java/io/tracepermit/conformance/PortableContractsTest.java), [LanguageNeutralContractArtifactsTest](https://github.com/hemantkgupta/tracepermit/blob/main/java/conformance/src/test/java/io/tracepermit/conformance/LanguageNeutralContractArtifactsTest.java), [ExecutionPermitConformanceTest](https://github.com/hemantkgupta/tracepermit/blob/main/java/conformance/src/test/java/io/tracepermit/conformance/ExecutionPermitConformanceTest.java), and the [canonicalization contracts](https://github.com/hemantkgupta/tracepermit/tree/main/contracts/canonicalization).

What the canonical contract binds — and what it does not: normalization happens server-side at the trusted adapter. The hash is **not byte-for-byte identity of the whole MCP wire request**; wire framing and parser behavior are separate boundary concerns. In the durable profile, minting and cryptographic verification happen **before** the claim, not inside it: the service issues and verifies the permit first, then one PostgreSQL transaction atomically persists the single-use consumption together with the idempotency key, execution attempt, and bound evidence state; target I/O runs only after that claim commits, and completion is recorded separately. In default profiles the permit store is process-local (T-006). See [Cryptographic Permits](permits.md).

**Residual limit:** single-use authority does not by itself resolve crash-window uncertainty or every distributed check-to-use gap — a lost completion leaves consumed authority, and uncertain outcomes go to explicit lookup-only reconciliation, never an automatic retry.

## 3. Replay, concurrency & atomicity

**Implemented (tested):**

- In the PostgreSQL durable profile, permit, approval, idempotency, attempt, and evidence state are claimed **atomically in one transaction**, so a stolen permit cannot be consumed twice (T-006). Evidence: [PostgresDurableEnforcementStateTest](https://github.com/hemantkgupta/tracepermit/blob/main/java/persistence-postgres/src/test/java/io/tracepermit/persistence/postgres/PostgresDurableEnforcementStateTest.java), [SecureExecutionServiceConcurrencyTest](https://github.com/hemantkgupta/tracepermit/blob/main/java/application/src/test/java/io/tracepermit/application/SecureExecutionServiceConcurrencyTest.java).
- Default development profiles use **bounded process-local replay stores** (at most 65,536 entries with bounded cleanup): single-use within one process, not durable across restart and not shared across replicas ([ADR 0054](https://github.com/hemantkgupta/tracepermit/blob/main/docs/architecture/0054-bounded-local-replay-stores.md)).
- Evidence publication runs through a **transactional outbox** with a stable idempotency key and `SKIP LOCKED` leasing; the reconciler is lookup-only and appends a terminal outcome only when confirmed (T-033, T-034, T-012). Evidence: [EvidenceOutboxPublisherTest](https://github.com/hemantkgupta/tracepermit/blob/main/java/application/src/test/java/io/tracepermit/application/EvidenceOutboxPublisherTest.java), [ExecutionReconciliationWorkerTest](https://github.com/hemantkgupta/tracepermit/blob/main/java/application/src/test/java/io/tracepermit/application/ExecutionReconciliationWorkerTest.java), [PostgresCompletionFailureRecoveryTest](https://github.com/hemantkgupta/tracepermit/blob/main/java/persistence-postgres/src/test/java/io/tracepermit/persistence/postgres/PostgresCompletionFailureRecoveryTest.java).
- Abuse-control admission is atomic per new key, so concurrent first requests cannot exceed configured capacity (T-043).

Two distinctions worth reading twice: the outbox and idempotency key do **not** universally guarantee zero duplicate publication — delivery to the external sink is at-least-once and relies on receiver-side deduplication; a non-idempotent sink can observe duplicate calls (T-033's residual concern). And `SKIP LOCKED` leasing belongs to the outbox/reconciliation workers — it is a different mechanism from the atomic permit/approval claim, not a second lock in front of it.

**Residual limit:** no default profile has durable, cross-restart, cross-replica replay or abuse state; shared production state is an explicit release gate.

## 4. Fail-safe design

The threat model's fail-safe matrix is the design rule — **no cell defaults to allow merely to improve availability**. The implemented cells, on tested ports:

- Readiness gates plus fail-closed limiter errors stop traffic when a security dependency fails (T-013, T-031). Evidence: [SecurityReadinessTest](https://github.com/hemantkgupta/tracepermit/blob/main/java/application/src/test/java/io/tracepermit/application/SecurityReadinessTest.java).
- A required pre-dispatch telemetry event failure returns 503 with zero dispatch (T-036). Evidence: [SecurityTelemetryEventTest](https://github.com/hemantkgupta/tracepermit/blob/main/java/application/src/test/java/io/tracepermit/application/SecurityTelemetryEventTest.java), [OpenTelemetrySecurityTelemetryTest](https://github.com/hemantkgupta/tracepermit/blob/main/java/observability-otel/src/test/java/io/tracepermit/observability/otel/OpenTelemetrySecurityTelemetryTest.java).
- Post-send timeouts are bounded I/O budgets ([ADR 0057](https://github.com/hemantkgupta/tracepermit/blob/main/docs/architecture/0057-endpoint-io-deadlines.md)): a timeout surfaces as `GATEWAY_UNAVAILABLE` and cancels the exchange — it is **not** proof of zero target effects and never authorizes a retry (T-012). See [Core Action Flows](core-flows.md).

**Residual limit:** fail-closed is an availability trade-off by design — required-state failures deny service, and a stale health signal can still mislead a gate.

## 5. API & network attack surface

**Implemented (tested):**

- The development transport accepts only an explicit numeric IPv4-loopback HTTP `/mcp` URI with exact raw path — no `localhost`, no userinfo/query/fragment, no redirects, `NO_PROXY` set (T-022). Evidence: [LoopbackHttpGatewayTransportTest](https://github.com/hemantkgupta/tracepermit/blob/main/java/endpoint-connector/src/test/java/io/tracepermit/endpoint/LoopbackHttpGatewayTransportTest.java), [ADR 0032](https://github.com/hemantkgupta/tracepermit/blob/main/docs/architecture/0032-direct-loopback-endpoint-transport.md).
- JWKS and remote-destination fetching is SSRF-safe **for operator-configured URIs**: exact allowlisted HTTPS, global-address DNS policy with request-pinned resolution, no redirect/proxy/retry, bounded refresh and response (T-015, T-027). Evidence: [SsrfSafeHttpsJwkSetSourceTest](https://github.com/hemantkgupta/tracepermit/blob/main/java/adapters-remote/src/test/java/io/tracepermit/adapters/remote/SsrfSafeHttpsJwkSetSourceTest.java), [CachingOidcJwkResolverTest](https://github.com/hemantkgupta/tracepermit/blob/main/java/identity/src/test/java/io/tracepermit/identity/CachingOidcJwkResolverTest.java).
- The hardened remote-edge profile adds direct-TLS sender constraints, exact Host/Origin checks, independent pre- and post-authentication rate-limit ports, readiness, and bounded telemetry, with automated tests (T-028, T-018). This is a loopback demo composition — not a managed distributed edge.
- Durable stores carry tenant in identity, keys, queries, and claims (T-014), and gateway-owned history bounds rapid sequences of individually allowed reads (T-039) — that sequence store is process-local and resets on restart.

These controls constrain **configured** destinations. They are not host-wide egress enforcement, and no real AWS or Jira credential broker or egress-isolation layer exists in the tree ([release gates](https://github.com/hemantkgupta/tracepermit/blob/main/docs/specs/10-security-threat-model.md)).

**Residual limit:** an agent that keeps its own credentials or egress path bypasses TracePermit entirely (T-008) — anti-bypass custody is a future gate, not a present control.

## 6. LLM/agent-specific security

TracePermit evaluates the **structured action about to execute**, never the natural-language prompt.

**Implemented (tested):**

- Tool-result text and declared goals remain lower-trust data. A proposal derived from them crosses the same adapter and policy boundary, where a deterministic classifier re-evaluates it and issues no permit when it matches injection or exfiltration patterns (T-041). Evidence: [SyntheticPromptInjectionSimulatorTest](https://github.com/hemantkgupta/tracepermit/blob/main/java/simulator/src/test/java/io/tracepermit/simulator/SyntheticPromptInjectionSimulatorTest.java) and the [prompt-injection scenario](https://github.com/hemantkgupta/tracepermit/blob/main/docs/demo-scenarios/README.md). The classifier runs against a deterministic emulator — no live model is in the loop.
- The MCP dispatcher works from a frozen snapshot of a trusted registration path that rejects duplicate and reserved tool names; tool metadata and annotations grant no authority (T-042). See [MCP Gateway](mcp-gateway.md).
- Sanitization transforms an immutable original through an allowlisted transformer and triggers full re-evaluation with a distinct event and hash (T-040). One POC transformer exists; a reviewed production registry is future work. See [Action Sanitization](sanitization.md).

**Residual limit:** deterministic classification of tested patterns is **not detection of all prompt injection** or all malicious natural language — novel shapes and unmediated tools remain a risk, and code-defined tool implementations stay inside the trusted computing base.

## 7. Audit, evidence & non-repudiation

**Implemented (tested):**

- A bounded redaction library applies its default profile to approval-response action projections; evidence stores credential fingerprints, not secrets, and preserves original request hashes (T-009, T-023). Evidence: [EvidenceRedactorTest](https://github.com/hemantkgupta/tracepermit/blob/main/java/contracts/src/test/java/io/tracepermit/contracts/redaction/EvidenceRedactorTest.java), [EvidenceRedactionBoundsTest](https://github.com/hemantkgupta/tracepermit/blob/main/java/contracts/src/test/java/io/tracepermit/contracts/redaction/EvidenceRedactionBoundsTest.java), [ADR 0043](https://github.com/hemantkgupta/tracepermit/blob/main/docs/architecture/0043-bounded-evidence-redaction.md).
- Evidence is append-only and hash-linked within the application's store; publication goes through the outbox with an independently verified signed receipt and lookup-only reconciliation (T-032's binding mechanics, T-033, T-034). See [Audit Evidence & Anchoring](audit-evidence.md).
- Telemetry uses a typed event allowlist with bounded values and cardinality (T-035), and the connector's stdio framing is pre-allocated and bounded with protocol-only stdout and code-only stderr (T-023).

**Optional foundation, not operated services:** the receipt-verification and external-checkpoint interfaces exist with tests, but there is **no independently operated external anchor service** behind them today, and **no KMS or HSM signing custody is deployed** — the signing-oracle constraints of T-011 are design targets (T-010, T-032's anchor trust). Signatures and hash links provide tamper-evidence **within** the existing trust base; they do not prove truth or non-repudiation if the producer and the anchor trust are compromised together.

**Residual limit:** redaction covers tested sinks and fields — it cannot guarantee that arbitrary caller-controlled text can never carry a secret, and sensitive business arguments remain an open concern.

## 8. Session & browser security

**Implemented (tested), local profile only:**

- The optional same-origin browser approval profile: authorization code + PKCE + state + nonce with required response issuer, separate signed token verification, server-held short-lived tokens, opaque expiring cookies, per-mutation CSRF checks, exact Host/Origin, no CORS, and text-only rendering. Evidence: [approval-history-browser.test.mjs](https://github.com/hemantkgupta/tracepermit/blob/main/scripts/approval-history-browser.test.mjs), [ADR 0036](https://github.com/hemantkgupta/tracepermit/blob/main/docs/architecture/0036-local-browser-approval-boundary.md). A synthetic browser profile is qualified; no external browser identity provider is.
- Approvals bind an enrolled human reviewer distinct from the requester (T-025). The reviewer's decision commits in **its own transaction** and can leave a request approved and not yet executed; a later explicit execution step revalidates the approved request against the current policy and binding, and only then atomically consumes the approval together with the execution claim in one transaction. Approval is not execution. Evidence: [HumanApprovalServiceTest](https://github.com/hemantkgupta/tracepermit/blob/main/java/application/src/test/java/io/tracepermit/application/HumanApprovalServiceTest.java), [PostgresDurableEnforcementStateTest](https://github.com/hemantkgupta/tracepermit/blob/main/java/persistence-postgres/src/test/java/io/tracepermit/persistence/postgres/PostgresDurableEnforcementStateTest.java). See [Dual-Control Human Approvals](approvals.md).

**Residual limit:** this is local HTTP, not public TLS — same-UID compromise, another local port, or a leaked bearer (which acts as its enrolled subject) stays outside the boundary; host-wide anti-bypass and approval-flooding protection are future gates.

## 9. Threat modeling & security testing

The threat register is maintained per boundary and revisited for every real adapter; the [traceability matrix](https://github.com/hemantkgupta/tracepermit/blob/main/docs/specs/12-traceability-matrix.md) maps requirement IDs to actual code and tests. The threat model's [release gates](https://github.com/hemantkgupta/tracepermit/blob/main/docs/specs/10-security-threat-model.md) — ten checks from tenant isolation through a validated kill switch — are obligations before any real AWS or Jira credential is introduced, not statements about today's tree.

What has actually run: the automated suites linked throughout this page, the [Gate C presenter qualification](https://github.com/hemantkgupta/tracepermit/blob/main/docs/demo-scenarios/README.md), PostgreSQL durability qualifications, and the source-free distribution qualification ([simulator-approval-distribution.test.mjs](https://github.com/hemantkgupta/tracepermit/blob/main/scripts/simulator-approval-distribution.test.mjs)). Two named profiles are live-qualified with dated evidence: hosted OAuth MCP (2026-10-11) and the native endpoint + model-driven approval rehearsal (2026-10-15) — for those profiles only.

**Residual limit:** current suites prove the named compositions only — the tree is not penetration-tested, has no full authorization matrix for control-plane roles, and is not production-certified; mutation and property-based testing exists for schemas and canonicalization but not everywhere.

## How to verify from source

```bash
pnpm install --frozen-lockfile
pnpm check                # console build/type/test + Java suite
pnpm test:java-postgres   # durable PostgreSQL qualifications
pnpm test:distribution    # source-free distribution qualification
pnpm docs:links && pnpm site:build
```

If this page and the source, tests, or [current-state baseline](https://github.com/hemantkgupta/tracepermit/blob/main/docs/specs/03-current-state-baseline.md) disagree, the code wins — file a documentation issue. The [Deployment Status](deployment.md) page tracks implementation status, and [The Security Invariant](security-invariants.md) states the design axioms this map grounds in code.
