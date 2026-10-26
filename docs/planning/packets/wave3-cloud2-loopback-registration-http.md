# Cloud2: loopback-only execution-disabled registration HTTP adapter

Status: dispatched to the existing Cloud2 chat; new isolated writer branch.
This closes a callable-library transport gap, not public AgentCore deployment,
human OBO or operation execution. All PRs remain draft/unmerged.

## Ownership and starting point

- Repository: `https://github.com/hemantkgupta/tracepermit`.
- Verify clean state and PR111 at `317a1be331b46292699a1e5bbf2e824537a27eb8`.
  Stop/report unexpected ownership or head changes.
- New branch: `codex/agentcore-loopback-registration-http`, from that exact head.
- Draft stacked base: `codex/agentcore-jdbc-registration-qualification`.
- New module: `java/agentcore-registration-http`, with only required additive
  settings/dependencies. Reserve ADR0082 after checking uniqueness. Existing
  routes, default launchers, ingress/verifier/repository/migrations stay unchanged.
- Mac owns PR108/authority; Gemini owns durable replay/ADR0080; Cloud1 owns
  `java/pilot-operator`/ADR0081. Separate branches' localized settings additions
  require later explicit integration. Do not touch producer branches.

## Deliverable and gates

Read AGENTS/specifications, the exact PR109 ingress API and PR111 composition
fixtures. `AgentCoreRegistrationIngress.register` remains the only registration
composition; trusted operator construction supplies its real verifier, enrollment,
repository and replay ports.

1. New server/adapter library only, no executable/default wiring. Hardcode numeric
   `127.0.0.1` binding; ephemeral test ports are allowed. No host override,
   public listener/tunnel, TLS bypass or AWS/Okta compatibility claim. Private
   HTTPS edge and real deployment are future gates.
2. One narrowly versioned POST registration route. Exact method/path and no
   ambiguous query/path handling. Reject unsupported encoding/type. Require one
   unambiguous Authorization value; duplicates, mixed/cookie/forwarded identity
   and unsupported sender binding cannot grant authority. Any human/delegation
   input retains the existing fixed contract-required rejection. Two tokens are
   not OBO. No body-supplied scope/tenant/principal/policy/generation/verified facts.
3. Stream-bound input to the ingress32KiB ceiling before allocation. Ingress keeps
   strict UTF-8/JSON validation. Enforce finite header/body/overall request bounds,
   worker/queue capacity and cleanup/cancellation. Genuinely test slow/partial
   bodies and exhaustion. Do not assume a Java HTTP API provides missing
   timeouts or introduce an unbounded cached pool. Report an unowned prerequisite
   instead of shipping falsely bounded transport.
4. Return only created/operationId/`executionEnabled:false`, never snapshots,
   credential fingerprints or raw exceptions. Fixed safe error/status mapping;
   no auth/header/body/URL logging, echo, CORS, redirects or auto-retry. Lost
   acknowledgement is uncertainty, not false success. Registration cannot admit
   or execute, and a retry lookup does not establish execution authority.
5. Real signed HTTP-to-JDBC qualification on PostgreSQL16.4, MariaDB11.4.13 and
   MariaDB10.6.25, with synthetic local JWKS/runtime keys. Cover auth/enrollment,
   body tamper, replay, fresh-token original-return retry, request-key conflict,
   commit acknowledgement uncertainty, no snapshot leak and zero execution/
   permit/target side effects. Test duplicate headers, slow/oversized bodies,
   queue bounds, close/cleanup/redaction and deliberate guard-removal controls.
6. Existing replay limits remain explicit. Do not implement/wire Gemini's shared
   replay store or infer cross-instance security from a process-local fixture.
   No target credentials/ports, public service or authority routes.

If a prerequisite requires existing runtime changes outside this ownership,
stop/report. Add scoped ADR/runbook/learning/specifications/traceability, not
whole-requirement or production-readiness promotions.

## Qualification and handoff

Fresh focused and three-engine tests, serialized `pnpm check`, formatting,
documentation links/flows and relevant module gates. Preserve workload,
concurrency, deadlines, coverage and required gates. Report exact fresh/cached/
skipped/failed outcomes. At most two broad attempts without diagnosed repair;
stop after three identical no-progress attempts or a missing authority/security
decision. No environment rebuild or other workers' Docker restart/prune.

Commit/push a coherent result, draft stacked PR, exact-head CI receipt and the
standard TRACEPERMIT HANDOFF with explicit writer release. No merge, force-push,
rebase, reset/clean, live provider/GitHub/AWS/customer call, public listener/tunnel,
paid fallback, private customer planning access or secrets in Git/logs/chat.
