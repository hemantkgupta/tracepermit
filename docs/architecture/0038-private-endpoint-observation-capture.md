# ADR 0038 — Opt-in private endpoint observation capture

- Status: proposed; implemented for local review
- Date: 2026-10-16
- Requirements: FR-016/017; SEC-003/012/014; OPS-004

## Context

The named native-client allow/block record retains gateway outcomes but not a
full measured executable hash/start-time transcript. Reading arbitrary client
logs or printing source attestations would risk credentials, signed proofs and
private paths. A protocol-forwarding capture shim would also change the parent
process the Java observer measures.

## Decision

Add an optional `EndpointObservationCapture` port to the existing Java connector.
The default is a no-op. When explicitly configured, the connector invokes it
after signing and successful process-stability verification, immediately before
gateway forwarding. The actual observer, credential/signing providers, native
parent relationship and gateway verifier are unchanged.

The development `FileProcessObservationRecorder` writes a new, exclusively
created owner-only JSONL file in a pre-existing canonical owner-only POSIX
directory. It retains only version/kind/stage, sequence/time, request binding
profile/hash, platform/development assurance, observed terminal attachment and
process ID/name/hash/parent name/start time. The request hash uses the existing
`tracepermit-mcp-method-params-v1` binding; transport IDs are excluded. It never
serializes request bodies, credentials, signed proofs, signing keys, device
identifiers, command arguments, environment dumps or full executable paths.

The recorder validates ownership, exact 0700/0600 modes, file/directory identity,
canonical directory, regular-file type and single hard link before each record.
It refuses replacement, symlinks, unexpected growth and more than 32 records or
64 KiB per connector process. Capture failures stop that request before forwarding
with `ENDPOINT_CAPTURE_UNAVAILABLE`; recording never silently turns itself off.
A failed write can leave an incomplete last line. Do not repair/replay it
automatically. A close error can occur after an earlier request completed and
must not trigger a tool retry.

`pnpm demo:endpoint:codex --capture-observations` creates a fresh private capture
directory outside the ephemeral-key cleanup directory and forwards its path
through the existing stdio environment allowlist. The ordinary launch explicitly
discards an ambient capture-directory variable. `--check` creates no capture,
credential or client process. No global client settings or extra MCP shim are
introduced.

## Interpretation and limits

A record means **local stability checked before forwarding**, not verified
source acceptance, policy authorization, execution, human intent or model tool
selection. Correlate it with a separately observed gateway result. It is not a
signed/immutable evidence ledger, and the local owner can modify/delete it.
Same-user process/filesystem compromise and hostile ancestor-directory races
remain outside this development filesystem boundary. POSIX and Unix file
attributes are required when enabled; there is no permissive platform fallback.

The file can still reveal process timing, executable fingerprints and request
correlations. Keep it private and inspect it before any deliberately reviewed
export. It survives ordinary demo key cleanup and is not automatically published.
The per-process bounds are not a global storage quota across client restarts.

Automated HTTP tests exercise missing proof, changed request, different valid
credential and replay against an otherwise allowed simulated read, asserting no
additional dispatch. Their synthetic observation is not native-client or
model-driven attack evidence. Gate B stays partial until fresh native records
and the remaining independently scoped rehearsal evidence are retained.

## Verification

Tests cover default-disabled bootstrap, strict explicit configuration, allowlist
redaction, private unique files, link/replacement/permission changes, bounds,
closed capture, signed-request/hash agreement, capture-before-forward ordering,
and failed stability/capture with zero forwarding. Launcher tests use a stub
client and make no native-process claim. Existing source-proof, policy, approval
and execution checks must continue to pass.
