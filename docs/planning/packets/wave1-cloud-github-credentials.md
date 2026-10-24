# Cloud: GitHub App installation credential-provider foundation

Read and obey [the shared protocol](wave1-shared-protocol.md). Use a prepared
cloud environment with Java21/Node22+/pnpm11.25.0 and source-control publication
access. Do not spend the task repeatedly repairing missing environment tooling;
that work belongs to the separate setup packet.

Base: `f23dc22f056058d7977715e0878c284728d10d17`.
New branch: `codex/github-app-installation-credentials`.
Read AGENTS/specs README, ADR0069/0073 and GitHubAppCredentialProvider,
GitHubAppCredentials, GitHubApiTransport and the target-preflight tests/runbook.
Use official GitHub App documentation for the exact current JWT/token-exchange
contract; record supporting URLs and distinguish verified facts from design.

## Goal and scope

Implement a real Java credential-provider foundation behind the existing port,
qualified only against synthetic transports. Preserve the port signature and
existing adapter/preflight/action behavior. Runtime files belong in the new
`java/adapters-remote/src/main/java/io/tracepermit/adapters/remote/github/credentials/`
subpackage; matching tests have the same subpackage. Reserve ADR0076 and add a
dedicated runbook. A dependency addition needs explicit necessity and review;
reuse existing signing, TLS and test facilities first.

Operator-owned immutable configuration binds the App identity/key source,
installation, exact allowlisted repository identity and minimum permissions.
No public body or model chooses a host, installation, credentials or authority.
Bound App-JWT lifetime/clock handling and installation-token expiry to verified
provider semantics. Read private signing material only from an explicit private
operator source, never a committed fixture. Runtime-generated keys in tests only.

Use a production factory constrained to GitHub's fixed HTTPS API origin, normal
certificate/hostname validation and the repository's DNS/SSRF/no-redirect/no-retry
boundaries. Keep test transport injection visibly separate; never create a
production localhost/cleartext override. Fetch only the configured installation's
repository/permission-restricted token. Strictly validate response types, bounds,
expiry and repository/permission confirmation; fail closed on missing or ambiguous
assurance rather than treating request parameters as provider confirmation.
If GitHub does not expose a required confirmation, report the trust-contract gap
instead of pretending it was checked or weakening preflight.

Raw JWT/key/token and request/response fragments must never enter logs, exceptions,
causes, suppression, evidence, files or string representations. Emit fixed error
codes and fingerprints only. No background refresh loop, broad cache, public
endpoint, real credential installation or automatic retry is needed. Prefer no
cache for the foundation; any cache must be explicitly scoped and expiry-bounded.

## Required proof

Test exact restricted exchange, expiry/clock boundaries, wrong App/installation/
repository/permissions, malformed/duplicate/trailing/oversized/scalar JSON,
unexpected status/redirect, transport and cleanup failures, concurrency isolation,
key-read/sign failures and sentinel-secret redaction through nested/suppressed
exceptions. Retain positive controls. Assert no PR creation or unrelated request.
Do not add operation lifecycle, binding-bridge code, target dispatch, policy or
AgentCore code; those are other lanes.

Run fresh focused tests and existing GitHub adapter/transport regressions, then
mandatory full/format/docs/flow gates. State what Docker-dependent gates actually
ran. Push a coherent draft PR, attach exact-head checks and the shared handoff,
release ownership and stop. The result is an unwired synthetic-qualified library,
not a live GitHub integration, exactly-once guarantee or production release.
