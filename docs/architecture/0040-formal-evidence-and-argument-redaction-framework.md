# ADR 0040 — Formal field-level evidence and argument redaction framework

- Status: Accepted for Java backend foundation
- Date: 2026-10-16
- Requirements: SEC-012, DATA-003, FR-007, FR-010

## Context

TracePermit enforces Agentic Visibility & Control: no agent touches an enterprise resource without an intercept, canonicalization, policy evaluation, and cryptographic execution permit. During operation, agent proposals and tool calls often carry sensitive data: passwords, API keys, client secrets, bearer tokens, private keys, financial identifiers (credit card numbers), and government identifiers (SSNs).

Earlier POC components implemented ad-hoc redactions or omitted raw arguments entirely from audit evidence records. However:

1. **SEC-012** requires: _"Logs, metrics, traces, errors, and audit events MUST redact credentials and sensitive arguments according to policy."_
2. **DATA-003** requires: _"Sensitive tool arguments SHALL support field-level redaction or irreversible transformation before persistence."_

Without a formal redaction engine:

- Human reviewers viewing pending approval requests via loopback APIs or the React inbox could inadvertently view raw passwords or bearer tokens contained in action proposals.
- Diagnostic logs and error strings could leak intercepted bearer headers or tokens during exception formatting.
- Audit systems had no deterministic way to verify argument equality across independent events without storing sensitive values in cleartext.

Critically, any redaction framework must maintain TracePermit's **central cryptographic invariant**:

- The versioned canonical request hash (`actionHash`) MUST be computed over the unmodified canonical proposal before redaction.
- Approvals, permits, and dispatch verifications remain bound to that exact `actionHash`.
- Redacted projections and persisted audit artifacts preserve that hash while sanitizing the displayed or stored field values.

## Decision

We introduce a formal, thread-safe redaction framework in `io.tracepermit.contracts.redaction`:

### 1. Composable Redaction Strategies (`RedactionStrategy`)

- `MASK`: Replaces the sensitive value with a static indicator: `[REDACTED:<ruleName>]`.
- `IRREVERSIBLE_HASH`: Replaces the sensitive value with a deterministic SHA-256 fingerprint (`sha256:<64-hex-chars>`). This fulfills **DATA-003** by allowing auditors to verify whether two records contained the identical parameter without ever disclosing the cleartext secret.
- `DROP`: Completely omits the property from JSON objects.

### 2. Multi-Criteria Redaction Rules (`RedactionRule`)

A rule matches through three orthogonal criteria:

- **Field names (case-insensitive):** e.g., `password`, `secret`, `apiKey`, `apiToken`, `privateKey`, `accessToken`, `authorization`, `cvv`.
- **Field paths (exact hierarchy):** e.g., `action.arguments.secretKey`, `/payment/billing/cvv`.
- **Value patterns (regex):** Matches embedded or freeform patterns across strings, including:
  - Bearer authorization headers (`\bbearer\s+[a-zA-Z0-9_\-\.~+/]+=*`)
  - Compact JWT tokens (`\bey[a-zA-Z0-9_\-]+\.ey[a-zA-Z0-9_\-]+\.[a-zA-Z0-9_\-]+`)
  - PEM private keys (`-----BEGIN[ A-Z0-9_-]+PRIVATE KEY-----[\\s\\S]*?-----END...`)
  - Credit card numbers (`\b(?:\d{4}[ -]?){3}\d{4}\b`)
  - US Social Security Numbers (`\b\d{3}-\d{2}-\d{4}\b`)

### 3. Standard and Specialized Redaction Profiles (`RedactionProfile`)

- `enterpriseDefault()`: Standard enterprise baseline masking common secrets, credentials, tokens, and PII.
- `irreversibleHash()`: Applies SHA-256 irreversible transformation for all matching fields and patterns (satisfying DATA-003).
- `strictDrop()`: Drops sensitive fields completely from object representations.
- Profiles enforce a configurable `maxDepth` limit (default 32) that fails closed if a deeply nested or circular payload is encountered, mitigating recursion denial-of-service attacks.

### 4. Recursive Engine (`EvidenceRedactor`)

- `redact(JsonNode)`: Deeply traverses arbitrary JSON documents (objects, arrays, primitives) and returns an immutable, sanitized copy without modifying the original input.
- `redactActionArguments(JsonNode)`: Safely sanitizes `action.arguments` in a canonical action envelope while preserving the root `eventId`, `traceId`, and tool metadata.
- `redactText(String)`: Sanitizes freeform exception messages, log lines, and trace details using configured value patterns.

### 5. Ingress & Projection Integration

- `SimulatorApprovalServer.projection()` now routes the action snapshot through `EvidenceRedactor.enterpriseDefault().redact(event.path("action"))`.
- Reviewers browsing pending requests see clear `[REDACTED:credentials]` indicators for sensitive parameters rather than cleartext passwords.
- The underlying PostgreSQL storage and `exactAction()` check continue to verify `CanonicalJson.sha256(event)` against `approval.binding().actionHash()`, ensuring cryptographic integrity is never compromised.

## Consequences

- **Fail-closed security:** Malformed inputs, nulls, or recursion depth violations fail safely without leaking unredacted data.
- **Cryptographic non-repudiation:** Original canonical action hashes remain untouched; approvals and permits verify against pre-redaction hashes.
- **Audit utility:** Irreversible SHA-256 transformation allows correlation and equality checks across compliance records without storing plain secrets.
- **Boundaries:** The framework operates within the Java application memory boundary; it does not replace operating-system memory protections or protect against compromised JVM processes.
