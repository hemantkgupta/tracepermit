# GitHub pilot target preflight — synthetic qualification

Status: adapter foundation only; no live GitHub App/target, AgentCore ingress,
durable operation executor or production deployment has been qualified here.
Requirements: FR-001/002/008/009/012/015; SEC-001/002/003/012/015/019;
REL-001/003/004/005/008/009; OPS-002/004. See
[ADR0073](../architecture/0073-github-pilot-target-preflight.md).

## Trust inputs and stop conditions

The trusted operator supplies a `GitHubPilotTargetBinding` separately from the
action: installation ID, immutable repository ID, immutable owner ID, existing
allowlisted full repository name, one head, base `main`, and exact 40-character
lowercase head/base commit SHAs. No default IDs or inferred current refs exist.
The private repository must be enabled, unarchived and non-fork, and the token
must expose only this single dedicated repository in its installation listing.
Production uses only `https://api.github.com`, public pinned DNS and platform
TLS. There is no custom host/port/trust or localhost override.

`GitHubAppCredentialProvider` must be trusted to bind the short-lived opaque
credential to the exact installation and minimal repository permissions. The
adapter compares its installation ID to the operator record and checks expiry
before network work and each request, including immediately before POST.
The installation repository list independently checks repository access, not
the token's installation ID. No token minting or installation service is shipped.
Do not substitute user access tokens or an App JWT for this provider contract.

Missing bindings, optional/null permit authority, repository alias conflicts,
unexpected identity/status/ref, token expiry/mismatch, unavailable preflight,
pagination, oversized bodies and unsafe destinations stop dispatch. Legacy
constructors without a binding fail closed. The only HTTP loopback transport
factory is package-private, explicitly synthetic and unavailable from public
production constructors; it adds no runtime switch or endpoint.

Do not load an arbitrary business-operation snapshot and infer these trusted
inputs from it. The frozen generic resource/precondition hash contract has no
qualified bridge to this legacy adapter. The record/GET preflight is the narrow
foundation for future trusted composition, not that composition itself.

## Offline synthetic commands

Use Node 22+, Corepack/pnpm 11.25.0, Java 21 and Docker for the complete suite.
Run from this branch's isolated checkout. All target requests in the focused
tests go to disposable numeric-loopback MockWebServer; credentials and keys are
synthetic/runtime-generated. No external provider or target call is needed.

```bash
corepack enable
pnpm install --frozen-lockfile
source scripts/configure-java-environment.sh
java/gradlew -p java :adapters-remote:test --tests '*github*' --console=plain
pnpm check
pnpm format:check
pnpm docs:links
pnpm docs:flows:check
git diff --check
```

Expected controls:

- Four valid GET observations precede exactly one POST for an allowed action
  and current, verified request-bound permit. No GET failure permits a POST.
- Positive typed IDs, URL, repository/owner, ref/SHA, draft/state and original
  content must match in a 201. Malformed/contradictory 201 is unknown, with no
  retry, repair or second POST. Known refusal remains blocked too.
- Concurrent identical calls dispatch at most once in the current process.
  A fresh valid permit does not reset unknown/failed attempts; changed content
  cannot reuse an existing logical tuple's success.
- Lookup uses only GET, revalidates preflight, follows no pagination/redirect,
  and never changes the ledger or sends a POST. Exactly one complete matching
  open draft can confirm observed logical-effect presence. Empty/duplicate/
  inaccessible/partial/historical results remain unknown. A later failed
  lookup exposes no previous cached confirmation.
- Time, decoded-byte, credential-redaction, cross-origin and resource-cleanup
  checks pass. No response body, token, provider error/exception cause or raw
  request content is printed as a diagnostic.

Guard-removal controls must fail a focused regression when the tested guard is
temporarily removed, then restore the guard and rerun the focused test before
the full gate. Keep mutation patches/logs outside Git. Never relax a gate or
leave a mutation in the branch.

## Interpretation and future operator work

Successful lookup proves only that the exact desired open draft was observed
at that time. It does not prove that the earlier attempt created it, that no
other historical PR existed, or that authorization is current. Absence from a
list cannot prove that an uncertain POST failed. Mutable refs can race after
preflight; GitHub's create endpoint has no documented ref CAS or idempotency key.
Do not retry an unknown write, reset it to not-started, merge a PR, or perform a
compensating deletion to manufacture a clean outcome.

No live access is authorized by this runbook or qualification packet. Future
App registration, installation, token minting, target read/write or provider
mutation needs a separately approved task. Verify actual minimum permissions,
installation provenance, private-repository draft availability and endpoint
response shapes there. A provider incompatibility is a stop condition. Do not
add paid fallback, alternate credentials, public ingress, proxy/tunnel or
allowlist expansion to make a test pass.

Only retain bounded private evidence: source commit, test commands/counts, fixed
case/result codes, request method/counts, restored-guard outcome and cleanup
observations. Never retain tokens, credential fingerprints, headers, private
keys, raw target payloads, repository/customer IDs, operator configuration or
environment dumps. Synthetic fixtures are not customer qualification evidence.

## Official API sources

Reviewed GitHub's official OpenAPI description at
[`755ee33b4a81d51f6f07a902cce26cbec3b822f2`](https://github.com/github/rest-api-description/blob/755ee33b4a81d51f6f07a902cce26cbec3b822f2/descriptions/api.github.com/api.github.com.json).
It documents these endpoints, pagination parameters and response schemas:

- [Installation repositories](https://docs.github.com/rest/apps/installations#list-repositories-accessible-to-the-app-installation):
  installation-token access and paginated repository visibility.
- [Get repository](https://docs.github.com/rest/repos/repos#get-a-repository):
  repository/owner identity and private/archived/disabled state.
- [Get reference](https://docs.github.com/rest/git/refs#get-a-reference):
  exact `heads/<branch>` ref and object type/SHA; missing ref returns 404.
- [Create pull request](https://docs.github.com/rest/pulls/pulls#create-a-pull-request):
  head/base/draft request fields and typed PR response. No documented ref-CAS
  parameter or idempotency key; private draft availability depends on plan.
- [List pull requests](https://docs.github.com/rest/pulls/pulls#list-pull-requests):
  `owner:ref` head, base, state and page bounds. Pagination or eventual visibility
  cannot establish authoritative absence.

These are source facts, not evidence of live permission/installation readiness.
The restricted cloud network allowed the official GitHub source mirror; no
network policy, provider setting or paid API was changed.
