---
title: Security Domain Map
description: The security domains exercised by the TracePermit threat model, and where each is enforced.
---

# Security Domain Map

TracePermit's [security threat model](https://github.com/hemantkgupta/tracepermit/blob/main/docs/specs/10-security-threat-model.md) maintains a register of adversarial threats (T-001 through T-044), trust-boundary controls, a fail-safe matrix, and release gates for real targets. This page maps the security domains that register draws on — a reviewer's guide to how the design reasons, and onboarding for contributors evaluating or extending the enforcement path.

> The threat model is the normative baseline. When this summary and the threat model disagree, the threat model wins.

## Domains at a glance

| #   | Domain                             | Representative threats                          | Enforced in                                        |
| --- | ---------------------------------- | ----------------------------------------------- | -------------------------------------------------- |
| 1   | Identity, tokens & delegation      | T-001, T-003, T-004, T-019, T-026               | Trusted adapters, permit & delegation verification |
| 2   | Request binding & canonicalization | T-005, T-044                                    | Canonical action hash, execution permits           |
| 3   | Replay, concurrency & atomicity    | T-006, T-012, T-033, T-034, T-043               | Permit claims, outbox, reconciliation              |
| 4   | Fail-safe design                   | T-013, T-031, T-036                             | Fail-safe matrix, readiness gates                  |
| 5   | API & network attack surface       | T-014, T-015, T-018, T-022, T-027, T-028, T-039 | Gateway boundaries, egress policy                  |
| 6   | LLM/agent-specific security        | T-041, T-042                                    | MCP dispatcher, sanitizer, classifier              |
| 7   | Audit, evidence & non-repudiation  | T-009, T-010, T-011, T-023, T-032, T-035        | Evidence store, signing service                    |
| 8   | Session & browser security         | T-025                                           | Browser approval profile                           |
| 9   | Threat modeling & security testing | all                                             | T-register, release gates, test suites             |

## 1. Identity, tokens & delegation

The foundational concepts: OAuth 2.0/OIDC mechanics (issuer, audience, and expiry validation; JWKS handling), RFC 8705 mTLS certificate binding, workload identity attestation, and capability-based security with delegation chains.

How TracePermit applies them:

- Identity is **derived by trusted adapters** from verified credentials or transport evidence — never accepted from a request body (T-001).
- Tokens are validated in Java with RS256 plus hardened RFC 8705 certificate-thumbprint binding matched to the direct TLS peer before protocol parsing (T-003, T-030).
- Endpoint source attestation is request-bound, short-lived, and asymmetric (T-019, T-020).
- Delegations are Ed25519-signed, parent-hash linked, and verified as strictly monotonically narrowing — a child can never broaden scope, resources, audience, or lifetime (T-004, T-026).

See [Cryptographic Permits](permits.md) and [Endpoint Source Attestation](endpoint-attestation.md).

## 2. Request binding & canonicalization

The concepts here — canonical serialization and hashing (the same family of ideas as AWS SigV4 canonical requests), versioned canonical forms with frozen test vectors, and TOCTOU (time-of-check to time-of-use) races — are what make a policy decision mean anything at execution time.

How TracePermit applies them:

- Every action crosses the boundary as a **versioned canonical action** whose hash binds the policy decision, approval, and permit to the exact bytes that execute (T-005).
- The executor recomputes the action hash before consuming authority; a modified action is rejected.
- Canonicalization method v1 is frozen with exact vectors, so a silent integer-format change cannot alter signed bytes (T-044).
- Single-use permits close the residual check-to-use gap (T-006).

See [Cryptographic Permits](permits.md) and [The Security Invariant](security-invariants.md).

## 3. Replay, concurrency & atomicity

Single-use credentials, idempotency keys, atomic consume-and-claim, transactional outbox, exactly-once versus at-least-once semantics, and unknown-outcome reconciliation.

How TracePermit applies them:

- Permits are claimed atomically — PostgreSQL tenant/permit unique claims, `SKIP LOCKED`, bounded leases — so a stolen permit cannot be used concurrently (T-006).
- Evidence publication uses a transactional outbox with a stable idempotency key so a worker crash neither loses nor duplicates publication (T-033).
- The reconciler is lookup-only: it never retries an effect blindly and appends a terminal outcome only when confirmed (T-012, T-034).
- Abuse-control state (rate-limit keys) uses atomic new-key admission so concurrent first requests cannot exceed configured capacity (T-043).

See [Audit Evidence & Anchoring](audit-evidence.md).

## 4. Fail-safe design

Fail-closed versus fail-open reasoning is a per-component design discipline, not a slogan. The threat model's fail-safe matrix requires that **no cell defaults to allow** merely to improve availability:

- Unavailable policy, replay store, sanitizer, or permit signer means the action is not executed.
- A required pre-dispatch telemetry event failure returns 503 with zero dispatch (T-036).
- Readiness gates plus fail-closed limiter errors prevent traffic from continuing past a failed security dependency (T-013, T-031).

See [The Security Invariant](security-invariants.md) (Fail Closed).

## 5. API & network attack surface

SSRF, Host/Origin validation, parser-confusion bypasses, pre-authentication versus post-authentication abuse controls, strict schemas and size limits, and multi-tenancy isolation.

How TracePermit applies them:

- Egress is server-owned: exact allowlisted HTTPS URIs, global-address-only DNS with request-pinned resolution, no redirects, proxies, or retries — tokens cannot select a network URL (T-015, T-022, T-027).
- Exact Host/Origin checks and strictly ordered dispatch (readiness, rate, sender checks before JSON parsing) defeat bypass attempts (T-028).
- Body limits, strict schemas, and bounded rate limits address oversized or expensive requests (T-018, T-043).
- Tenant identity is carried in identity, keys, queries, and claims to prevent cross-tenant references (T-014).
- Gateway-owned history bounds rapid sequences of individually allowed reads that would otherwise enable quiet extraction (T-039).

## 6. LLM/agent-specific security

Indirect prompt injection, tool-result text as data rather than instructions, tool registry integrity, and the confused-deputy problem as it applies to agents. TracePermit's stance: evaluate the **structured action about to execute**, never the natural-language prompt.

How TracePermit applies them:

- Tool-result text and declared goals remain lower-trust data; any resulting structured proposal crosses the same adapter and policy boundary, is re-classified in Java, and receives no permit when injection is detected (T-041).
- The MCP dispatcher takes a frozen snapshot from a trusted registration path that rejects duplicate and reserved tool names; tool metadata and annotations grant no authority (T-042).
- Sanitization transformers run on an immutable original, are allowlisted, and trigger full re-evaluation with a distinct event and hash (T-040).

See [MCP Gateway](mcp-gateway.md) and [Action Sanitization](sanitization.md).

## 7. Audit, evidence & non-repudiation

Tamper-evident logs, append-only storage, external anchoring, signing-oracle constraints, and secret hygiene: fingerprints instead of credentials, redaction, and bounded telemetry.

How TracePermit applies them:

- Evidence is append-only with external signed checkpoints beyond application and DBA authority (T-010, T-032).
- Permit signing is separated from verification, with a constrained signing context so a compromised application cannot use the KMS as a signing oracle (T-011).
- Evidence records store credential fingerprints and redacted content — never raw secrets — including through stdio framing and diagnostics (T-009, T-023).
- Telemetry uses a typed event allowlist with bounded values and cardinality, so attacker-controlled values cannot leak through observability (T-035).

See [Audit Evidence & Anchoring](audit-evidence.md).

## 8. Session & browser security

CSRF, login CSRF, session fixation and theft, cookie hygiene (HttpOnly, SameSite), CSP, and OAuth code-injection defenses (PKCE, state, nonce).

How TracePermit applies them in the optional browser approval profile:

- Sign-in uses authorization code + PKCE + state + nonce with required response issuer and separate signed token verification.
- Requests are constrained by HttpOnly SameSite cookies, exact Host/Origin, no CORS, CSP, and text-only rendering.
- Approvals bind an exact, enrolled reviewer who must be distinct from the requester, with atomic PostgreSQL consume-and-claim (T-025).

See [Dual-Control Human Approvals](approvals.md).

## 9. Threat modeling & security testing

The methodology that ties the rest together: STRIDE-style trust-boundary mapping, adversarial registers per boundary, and layered testing.

How TracePermit applies them:

- The T-register is revisited for every real adapter, and the threat model defines **ten release gates** before any real AWS or Jira credential is introduced — from tenant isolation and least-privilege review through durable replay state and a validated kill switch.
- Testing expectations include forged, expired, future, wrong-issuer, wrong-audience, and wrong-type tokens; delegation cycles and amplification; concurrent permit/approval replay; SSRF; authorization matrices for every control-plane role; and secret-scanning and redaction tests.
- Schemas and canonicalization get mutation and property-based tests.

## Honest scope

The threat model's "residual concern" column tracks exactly what the POC does not yet claim: process-local replay and abuse stores, no production targets, pending durable trust services, and same-UID compromise of local listeners. The map above describes controls as they are specified — the [Deployment Status](deployment.md) page records implementation status, and the threat model remains the authority on what is and is not proven.
