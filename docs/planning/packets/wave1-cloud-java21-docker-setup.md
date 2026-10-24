# Cloud: reusable Java21 and Docker development qualification

Read and obey [the shared protocol](wave1-shared-protocol.md).
Base: `f23dc22f056058d7977715e0878c284728d10d17`.
New branch: `codex/cloud-java21-docker-qualification`.
This is development infrastructure, not TracePermit customer deployment.

## Goal

Make a reproducible, fail-closed development setup and preflight for Node22+,
pnpm11.25.0, Java21, the checked-in Gradle wrapper, a responsive Docker daemon
and disposable PostgreSQL16.4/MariaDB11.4.13/MariaDB10.6.25 qualification.
Read the official current Codex cloud-environment documentation and the session's
cloud-runtime skill/network policy before installation or container execution.
The existing scripts/configure-java-environment.sh and test runners remain the
source of truth; do not create incompatible Java discovery or backend launchers.

## Scope and environment authority

Allowed new paths: `scripts/cloud/`, corresponding development-tool tests there,
and `docs/runbooks/cloud-development-environment.md`. Do not edit CI, package
locks/scripts, application code, test assertions, database migrations or another
lane's files. Reserve no ADR number. Do not alter shared caches or product secrets.

First inventory versions, available disk/memory, permitted package access,
Docker client/server and GitHub read/push capability without dumping config/env
or credentials. Missing Docker client is different from an unreachable daemon
or unsupported nesting. No unsafe privileged workaround, exposed Docker socket,
public TCP daemon, host Docker restart, TLS disable or skip-to-green. If this
runtime cannot provide Docker, stop that execution gate and report the specific
platform/operator prerequisite; still deliver tested preflight/docs where possible.

Install only development dependencies in the dedicated task/setup environment
using supported package sources or checksum-verified pinned downloads. Respect
the configured proxy/CA/domain rules; do not broaden network policy or connect
the Mac/customer network. Private customer files stay excluded before checkout.
No AWS/Okta/Auth0/GitHub App credentials or customer database access is needed.

Provide a bounded/idempotent setup recipe and a separate non-mutating preflight
with fixed diagnostic categories. Preflight must not silently install, pull images,
launch services, kill resources or skip requirements. Negative tests must cover
Java17, no compiler, wrong pnpm, absent client, unresponsive daemon and timeout;
redact raw command diagnostics. Installation/startup is explicit, not hidden in
preflight. Preserve existing environment choices when they are already compliant.

## Acceptance

1. Demonstrate the setup in a clean task environment; run preflight twice.
2. Frozen dependency installation and Java environment checks pass.
3. Run the existing forced operation-repository suite on all three exact images
   with no Docker-availability skips; record actual cases, versions and cleanup.
4. Run mandatory `pnpm check`, formatting and relevant documentation checks.
5. Explain how to save/publish the prepared environment and verify a NEW task.
   Do not claim reproducibility from one task, cached local paths or files that
   were not captured in a published environment.

You may prepare task-local tooling and a draft setup PR. Publishing/republishing
or changing a shared environment's settings/network/secrets requires the owner's
review of the setup report. If UI publication cannot be performed, name the exact
remaining owner step. Do not apply customer infrastructure or use billed APIs.
Return the shared handoff with local-only tooling, unavailable gates and exact
fresh-task follow-up. Leave the PR draft/unmerged and release ownership.
