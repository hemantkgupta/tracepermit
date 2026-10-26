# Implementation packet — installed requester public sign-in

**Status: active implementation packet; automated phases 1–4 in progress;
owner-operated live phase 5 remains pending.**

Design contract:
[installed requester public sign-in](../../design/installed-requester-public-sign-in.md).

Requirements: FR-003/007/012; SEC-002/003/004/009/011/012/015;
REL-001/004/006/009; OPS-001/002/004/005.

## 1. Objective and evidence boundary

Implement the smallest source-free, same-Mac flow that obtains a short-lived
requester bearer through Native/public Authorization Code + S256 PKCE, verifies
it against operator-pinned trust, confirms exact requester-only enrollment, and
launches Codex without placing the token in shell text, argv or persistent Codex
configuration.

The implementation must remain simulator-only, numeric-loopback and bearer-only.
It must not claim prompt authorship, Codex executable identity, endpoint
attestation, production compatibility or general Auth0 support. Automated tests
use runtime-generated signing keys and owned local HTTP/TLS peers. The Mac owner,
not a cloud worker, owns real provider registration and interactive qualification.

The current synthetic token fixture remains test-only. It may inform tests but
must not be imported into production source or packaged as an issuer.

## 2. Decisions already made

These decisions are inputs, not implementation choices to reopen silently:

| Topic                       | Required implementation                                                                                                                                                              |
| --------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Public application          | Reuse the existing configured Native/public app and current single client ID. Register a second exact callback. Do not add a second trusted client.                                  |
| Callback                    | Fixed operator-pinned `http://127.0.0.1:<port>/requester/callback`; one flow/attempt; 120-second lifetime; fail on bind conflict.                                                    |
| OAuth/OIDC                  | Code + S256 PKCE; state, nonce and callback `iss`; no secret; exact pinned endpoints/JWKS/audience/client/org/subject; independently verify access and ID tokens.                    |
| Requester authority         | `openid` plus only `approvals.read approvals.request approvals.execute`; API permission set excludes decide/cancel; backend enrollment role set must be exactly `{REQUESTER}`.       |
| Enrollment proof            | New read-only bearer route `GET /api/v1/requester-qualification`; server reuses current verifier/membership and returns `204` only for the exact profile.                            |
| Credential lifetime/custody | Persist only the access token and signed expiry, max 15 minutes, minimum 60 seconds remaining; owner-only no-follow/no-replace storage; no refresh.                                  |
| Codex handoff               | Java launcher reads and consumes the file, then injects a fixed child-only environment variable through `ProcessBuilder`; token never enters shell/argv/config/stdin/output.         |
| Expiry during review        | Stop; explicit re-login as the same subject/client/org; explicit status then optional resume by saved ID/hash. Never recreate or retry the request automatically.                    |
| Incompatibility             | Return a bounded unsupported-profile failure and require an owner decision. Never weaken token/callback/org/type validation or fall back to a confidential/device/password grant.    |
| Packaging                   | Extend the existing source-free simulator approval distribution; do not add Node/pnpm/Gradle as installed prerequisites and do not use the endpoint-attestation connector as issuer. |

## 3. Dependencies and start gate

Before creating the implementation branch, the integration owner must:

1. approve and merge the design contract or record requested changes;
2. allocate the paths below to one writer and confirm no active branch owns them;
3. fetch the then-current `origin/main` and record its exact SHA/divergence;
4. confirm the simulator approval distribution, public browser profile and strict
   OIDC verifier tests are green on that base; and
5. keep unrelated PRs 70/72/73/74 out of the branch unless they have independently
   merged into `main`. None is a functional dependency of this packet.

Use a fresh branch such as
`codex/installed-requester-public-sign-in-implementation`. Do not rebase, reset,
force-push, merge another producer branch or repair unrelated failures.

No new third-party runtime dependency is expected. Use Java 21, Jackson, Nimbus
JOSE and the existing bounded OkHttp transport already present in the approval
runtime. A proposed dependency addition requires a separate supply-chain review.

## 4. Proposed exclusive path allocation

This list is a proposed future allocation, **not an active reservation while this
design PR is under review**. Once approved, only the assigned implementation
writer may edit these paths:

```text
java/adapters-http/src/main/java/io/tracepermit/adapters/http/SimulatorApprovalServer.java
java/adapters-http/src/main/java/io/tracepermit/adapters/http/requester/**
java/adapters-http/src/test/java/io/tracepermit/adapters/http/InstalledRequester*.java
java/adapters-http/src/test/java/io/tracepermit/adapters/http/requester/**
java/adapters-http/src/main/scripts/tracepermit-requester-auth
java/adapters-http/build.gradle.kts
scripts/simulator-approval-distribution.test.mjs
config/installed-requester-sign-in.example.json
docs/design/installed-requester-public-sign-in.md
docs/architecture/0065-installed-requester-public-sign-in.md
docs/runbooks/installed-requester-public-sign-in.md
docs/specs/02-system-requirements.md
docs/specs/03-current-state-baseline.md
docs/specs/04-high-level-design.md
docs/specs/08-interface-contracts.md
docs/specs/09-sequence-flows.md
docs/specs/10-security-threat-model.md
docs/specs/11-quality-and-acceptance.md
docs/specs/12-traceability-matrix.md
HANDOFF.md
```

The integration owner must renumber the ADR if `0065` is taken before work
starts. Do not create competing edits to shared specs or build files. Avoid
editing `SimulatorApprovalConfiguration`: this slice intentionally preserves its
single-client trust model and existing schema. If implementation proves a schema
change is necessary, stop and return that concrete finding for design review.

## 5. Runtime contracts

### 5.1 Proposed launcher

Package one additional supported launcher:

```text
bin/tracepermit-requester-auth --help
bin/tracepermit-requester-auth --check
bin/tracepermit-requester-auth --login
bin/tracepermit-requester-auth --codex
bin/tracepermit-requester-auth --cleanup
```

Exactly one flag is required. The launcher clears ambient JVM diagnostic-option
variables before Java starts, checks Java 21 and package layout, and dispatches
only a fixed main class. It accepts no token, callback value, child command or
provider identifier as an argument. The private config path comes only from
`TRACEPERMIT_REQUESTER_CONFIG`; output is code-only and redacted.

`--check` is genuinely offline: no DNS, sockets, browser, child, database or file
creation. `--login` is the only browser and credential-creation mode. `--codex`
may contact only the pinned JWKS destination to revalidate the credential, then
starts only the configured absolute Codex executable. `--cleanup` removes only
the exact verified credential path and safe temporary residue owned by this
profile.

### 5.2 Closed requester configuration

Implement the exact shape proposed in the design, replacing the provisional
schema string with final `1.0`. Reject duplicate, trailing, unknown and missing
fields. Reject every secret field, refresh/offline scope, dynamic discovery URI,
non-public/provider-relative trust input, relative local path and non-numeric
loopback URI.

Require:

- same canonical issuer origin for authorization, token and JWKS paths matching
  the existing qualified public candidate;
- exact organization syntax and expected subject;
- fixed callback path `/requester/callback` and qualification path
  `/api/v1/requester-qualification`;
- MCP path `/mcp` on the same numeric-loopback approval origin as qualification;
- owner-only `0600` config and `0700` credential directory with no symlinks; and
- an absolute regular executable owned by the current user or root. Pinning its
  path is configuration hygiene, not executable/source attestation.

### 5.3 Read-only qualification route

Add `GET /api/v1/requester-qualification` to the existing approval server. It:

- is reached only after the existing one-header bearer authentication and
  membership lookup;
- rejects query, Origin, Cookie, source-attestation header, body, transfer
  encoding and non-GET methods;
- requires human membership with roles exactly `{REQUESTER}`;
- requires the complete requester API permission set, permits requested
  non-authorizing `openid`, and rejects every other scope;
- requires at least 60 seconds of signed credential lifetime remaining;
- returns `204 No Content` with `Cache-Control: no-store`; and
- performs no database, policy, approval, evidence, permit, target or provider
  mutation and returns no identity or trust data.

Keep existing authentication/membership checks on every MCP method. This route is
only a pre-storage qualification; it is not a session and grants no new authority.

### 5.4 Authorization and validation core

Implement a small package under `...http.requester` with separated units for:

1. strict private configuration;
2. random state/nonce/verifier and authorization URI construction;
3. fixed numeric-loopback one-shot listener;
4. exact public token exchange through the existing safe transport;
5. current `OidcAccessTokenVerifier` and `BrowserIdTokenVerifier` composition plus
   the tighter requester subject/scope checks;
6. bodyless backend qualification;
7. no-replace credential storage/cleanup; and
8. fixed Codex child launch.

Do not generalize into a public OAuth library. Do not reuse browser cookies or
make package-private browser session internals public merely for convenience.
Small duplicated orchestration is safer than coupling the installed credential
flow to server-held browser state; common cryptographic verifiers and hardened
egress should be reused.

All HTTP clients must use finite connect/read/call limits, no proxy, no redirect
and no retry. All response bodies and local files are bounded before parsing.
All public errors map to the categories in the design without exception causes.

### 5.5 Credential store and child launch

Use create-new/no-follow operations in an already verified `0700` directory.
Publish a fully written/forced `0600` file with an atomic no-replace primitive;
macOS create-new hard-link publication is acceptable after verifying the
temporary inode. Refuse platforms/filesystems that cannot provide the required
semantics. Refuse an existing target rather than inspect-and-overwrite it.

The final envelope contains exactly schema version, raw access token and signed
expiry and is at most 20 KiB. Token/hash contents never enter diagnostics,
telemetry, evidence, tests snapshots or handoff. Tests use sentinels and assert
their absence.

For `--codex`, revalidate file and token, consume/unlink the file before child
start, put the token only in `TRACEPERMIT_REQUESTER_BEARER` in the child
environment, and pass an invocation-scoped MCP override containing the exact MCP
URL and `bearer_token_env_var` name. The helper accepts no arbitrary executable or
arguments. It inherits interactive terminal streams and the child's exit code but
does not parse/retry its MCP traffic. A launch failure never republishes the
credential.

## 6. Phased work plan

### Phase 0 — baseline and test plan

- Record branch/base/cleanliness and open-PR non-dependencies.
- Read the approved design and affected requirements completely.
- Convert design test IDs IPS-001 through IPS-034 into a test inventory before
  production code.
- Confirm the exact current Codex CLI configuration contract from official OpenAI
  documentation; treat installed-version behavior as a later manual gate.

Exit: no tracked runtime change yet; path allocation confirmed.

### Phase 1 — offline config, listener and OAuth core

- Add closed configuration and offline checker.
- Add deterministic injectable clock/random/browser/network boundaries.
- Implement one-shot listener and authorization/token flow.
- Compose strict existing verifiers and requester-specific subject/scope checks.
- Cover IPS-001 through IPS-015 with unit/integration tests using generated keys,
  owned sockets/TLS and no provider.

Exit: no backend route, credential publication or Codex launch yet.

### Phase 2 — enrollment qualification

- Add the bodyless 204 route and exact requester-only role/scope/time checks.
- Test absent/cross-tenant/wrong-client/wrong-subject enrollment, every role
  combination, non-human enrollment, extra/missing scopes, expiry and malformed
  transport before any file write.
- Demonstrate zero approval/database/permit/evidence/target mutations around the
  route.

Exit: IPS-016 through IPS-019 pass.

### Phase 3 — credential custody and Codex launcher

- Implement no-replace file publication, strict consumption and cleanup.
- Implement fixed pinned Codex child launch with child-only bearer environment.
- Add race, interruption, link/path, permission, expiry and redaction tests.
- Add an injected fake child for automated argv/environment assertions; never
  pass a usable provider token.

Exit: IPS-020 through IPS-029 and IPS-031 pass.

### Phase 4 — source-free packaging and repository contract

- Add the launcher, synthetic `.example.invalid` config and installed runbook to
  the existing clean-source distribution.
- Update manifest/inventory and distribution tests for permissions, working
  directory, missing Java/config/layout, source exclusion and secret scanning.
- Add ADR/spec/flow/threat/acceptance/traceability/HANDOFF updates that distinguish
  automated implementation from pending live qualification.
- Do not mark Auth0 compatibility, production identity or broad MVP completion.

Exit: IPS-030 passes and repository checks are green.

### Phase 5 — manual Mac qualification

- Build from the exact clean implementation commit and follow design section 11.
- Run IPS-032 through IPS-034 with human-entered provider credentials, distinct
  subjects, dedicated database and the source-free artifact.
- Commit only sanitized evidence if the repository evidence policy permits it;
  otherwise record the exact gate as local/manual and pending.

Exit: one bounded Mac/provider/Codex profile may be labeled qualified. This phase
does not authorize production or another provider/platform.

## 7. Required tests and checks

Run focused tests while iterating, then one coherent final validation. Expected
commands may be adjusted to the final test class names without weakening scope:

```bash
java/gradlew -p java --no-daemon :adapters-http:test \
  --tests 'io.tracepermit.adapters.http.InstalledRequester*' \
  --tests 'io.tracepermit.adapters.http.requester.*'

pnpm test:distribution
pnpm check
pnpm format:check
pnpm docs:links
git diff --check
```

In addition to IPS-001–IPS-034, preserve all existing public-browser, approval
MCP, OIDC verifier and source-free distribution tests. Required concurrency
coverage includes simultaneous callback replay and simultaneous credential
publication; assertions must establish exactly one winner without sleeps as the
correctness mechanism.

Test reports distinguish fresh execution, Gradle up-to-date/cache reuse, skipped
Docker/macOS/provider gates and not-run manual steps. A cloud inability to run
Docker, a browser or macOS is disclosed, not repaired by weakening tests. After
two unrelated environment failures, stop with evidence rather than broadening
the branch.

## 8. Requirement-to-evidence obligations

| Requirement | Implementation evidence                                                                                                |
| ----------- | ---------------------------------------------------------------------------------------------------------------------- |
| FR-003      | Protected qualification and MCP calls authenticate before dispatch; exact public-client flow                           |
| FR-007      | Re-login continuity preserves subject/client/tenant/request hash/expiry/policy/single-use binding                      |
| FR-012      | Only simulator actions and generated identities/keys; no real target                                                   |
| SEC-002     | Unknown config/version/state/role/scope/provider/token/path fails closed                                               |
| SEC-003     | No committed secret, client secret, refresh token, raw-token output or test key                                        |
| SEC-004     | Independent strict access/ID-token signature, issuer, audience, time, type, client, org and scope verification         |
| SEC-009     | Exact tenant/subject/client enrollment and owned approval continuity                                                   |
| SEC-011     | Numeric loopback listener/MCP/qualification only; development/synthetic credentials remain non-production              |
| SEC-012     | Code-only errors and sentinel tests for token/code/verifier/cookie/path/provider-response redaction                    |
| SEC-015     | Exact egress allowlist, DNS/address pinning, no redirect/proxy/retry, bounded time and bytes                           |
| REL-001/009 | Dependency, auth, token, enrollment, expiry and uncertain outcomes fail safe with no automatic request/resume replay   |
| REL-004     | One-shot callback and credential no-replace races have deterministic concurrency tests                                 |
| REL-006     | One separate launcher/config/route can be removed without changing existing browser/MCP contracts; documented rollback |
| OPS-001     | Bounded code-only health/errors; no credentials in logs or telemetry                                                   |
| OPS-002     | Distribution manifest/source commit and config/profile schema identify active versions                                 |
| OPS-004     | Installed runbook covers offline check, setup, expiry/re-login, cleanup and recovery                                   |
| OPS-005     | Mac/provider/Codex live gate and same-UID/provider/revocation limitations remain explicit                              |

## 9. Stop conditions

Stop and return a design finding instead of implementing a fallback if:

- current main no longer has the one-client or token-validation assumptions in
  the approved design;
- a second client ID is required;
- the provider requires a secret, device/password/client-credentials grant,
  refresh/offline scope, wildcard/`localhost` callback or missing signed claim;
- the callback cannot be exact numeric IPv4 loopback;
- the helper would need browser cookies, reviewer tokens, backend secrets or
  dynamic discovery;
- no safe atomic no-replace primitive is available on the target filesystem;
- Codex cannot consume an environment-variable-referenced bearer without putting
  the value in argv/config/output;
- tests reveal automatic retry/replay behavior that cannot be disabled; or
- implementation needs real target/provider mutation or a public listener.

## 10. Delivery and handoff

Use one implementation branch, small reviewable commits by phase, and one draft
PR. Do not merge. The handoff must report:

- exact base/head/branch/PR and source-free package commit;
- requirement IDs and final config/CLI/route/token/callback contracts;
- all allocated files materially changed;
- fresh test counts, cached/skipped/manual gates and failures;
- absence of real identifiers, credentials, keys and target effects;
- Mac-only provider/Codex steps still pending or exact sanitized results;
- remaining same-UID, bearer, revocation, provider, executable/prompt/source-proof
  and production limits; and
- the next exact command, environment and branch owner.

No runtime work continues after the PR handoff until the owner reviews the
contract and evidence.
