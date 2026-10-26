# Cloud1: source-free GitHub pilot operator diagnostics

Status: dispatched to the existing Cloud1 chat; new isolated writer branch.
All PRs remain draft/unmerged. The standing coordinator reviews actual code and
exact-head evidence before assigning another task; it does not authorize merges
or live actions.

## Ownership and starting point

- Repository: `https://github.com/hemantkgupta/tracepermit`.
- Verify clean state and PR105 at `c52ba244463ff7d363d8e30a21b76dd2a50892e4`.
  Stop/report unexpected ownership or head changes.
- New branch: `codex/github-pilot-operator-diagnostics`, from that exact head.
- Draft stacked base: `codex/github-app-installation-credentials`.
- New module: `java/pilot-operator`, with only required additive settings/build
  hooks and the existing remote adapter dependency. Reserve ADR0081 after checking
  uniqueness. Do not modify producer branches or existing runtime behavior.
- Mac owns durable authority/PR108; Gemini owns `java/persistence-identity` and
  ADR0080. Cloud2 owns the separate registration HTTP module. Separate branches'
  localized settings additions require later explicit integration.

## Deliverable and gates

Implement a Java21-only source-free operator **diagnostic**, not an executor.
Read AGENTS, specifications, real PR105 credential/preflight APIs and existing
distribution conventions first. Derive configuration from actual types.

1. Offline `--check` validates bounded strict UTF-8 JSON, operator pins and
   private regular-file/key inputs, rejecting duplicate/unknown/trailing,
   malformed and oversized values. It performs zero DNS/network/credential
   minting/signing/target calls. Prove the absence of transport/provider calls.
2. Explicit `--preflight` composes the existing concrete installation credential
   provider and exact target preflight only. Preserve fixed origin, platform TLS,
   DNS pinning, response/sequence deadlines, repository/installation/ref/SHA pins,
   main/draft restrictions and least permissions. Qualification uses synthetic
   transports and runtime-generated test keys only, never live GitHub.
3. No caller-derived authority, origin/proxy/TLS override, fallback credential,
   execution command, service, operation approval/admission/permit or dispatch.
   A preflight result is not durable authority or target-absence proof.
4. Private bounded configuration, no secret argv, echoed bodies or unsafe
   exceptions/logs. Fixed diagnostic codes, finite process/request/cleanup bounds,
   deterministic exits and no automatic retries. Close/cancellation failure must
   not become success. Never retain raw causes/suppressed sensitive diagnostics.
5. Archive includes a space-safe host launcher, manifest/source provenance, exact
   dependency hashes, checksums, license/notice and setup runbook. Java21 suffices
   after extraction; no source/Node/pnpm/Gradle dependency. Prove genuinely offline
   `--check` and minimal PATH behavior. Claim macOS only unless independently tested.
6. Test private/symlink/oversized/duplicate/malformed configuration, source-free
   paths with spaces, permission/repository/SHA mismatches, cleanup, redaction,
   zero-network checks and selected deliberate guard-removal controls.

If an unowned runtime prerequisite is required, stop with a precise finding.
Do not broaden adapters, enforcement, identity, canonicalization, databases,
migrations or another worker's scope. Update scoped ADR/specifications/learning,
requirements traceability and HANDOFF without whole-ID completion claims.

## Qualification and handoff

Fresh focused tests, then serialized `pnpm check`, `pnpm format:check`,
documentation links/flows and relevant archive gates. Report exact executed,
cached, skipped and failed results; do not reduce test workload, concurrency,
deadlines or coverage, bypass unavailable gates or retry to green. At most two
broad attempts without a diagnosed repair; stop after three identical no-progress
attempts or a missing trust/security/credential decision. Never rebuild the
environment or restart/prune other workers' Docker.

Commit/push a coherent result, draft stacked PR, exact-head CI receipt and the
standard TRACEPERMIT HANDOFF with explicit writer release. No merge, force-push,
rebase, reset/clean, live provider/target/AWS/customer call, public listener/tunnel,
paid fallback, private customer planning access or secrets in Git/logs/chat.
