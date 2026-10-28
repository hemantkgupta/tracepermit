# ADR0075: pure business-operation binding to exact GitHub pilot targets

Status: implemented internal data-binding library; executor integration pending.

Requirements: FR-002/010/012; SEC-001/002/003/005/009/012;
REL-003/005/008; PORT-001; OPS-002/004. No whole-requirement status promotion.

## Context

ADR0068 freezes generic business-operation identities and digests. ADR0073 pins
the GitHub installation/repository/owner identity and exact head/base refs/SHAs.
Neither contract defines how its resource/precondition hash corresponds to the
other. Passing two independent validators is not proof that they describe the
same target. In particular, the generic synthetic vector has a short repository,
`resourceType: repository` and placeholder resource hashes: it must not be
silently reinterpreted as an executable GitHub action.

The existing permit binds canonical action/event/subject/audience and policy
ID/version. It does not bind business-operation ID, workspace, recovery generation,
resource/precondition hashes or policy content/artifact digests. This slice must
not pretend that validating a data join closes the composed authority transaction.

## Decision

Add pure `GitHubBusinessOperationBinding` in `adapters-remote.github`, which already
depends on application/contracts. Do not add an application-to-adapter dependency,
HTTP/MCP route, execution overload, database write, signing or credential call.

`bind` receives separately supplied `BusinessOperationScope`, a stored snapshot
and service/operator-owned `ExpectedProfile`. Construction is not authentication;
no public body may be deserialized into that profile or trusted scope. The profile
pins adapter `github-draft-pr-v1`, one explicit `dev`/`test`/`staging` environment,
positive safe recovery generation, exact policy ID/version/content hash, complete
artifact-kind/digest set and immutable `GitHubPilotTargetBinding`.

Copy the input before validation, enforce the existing 262,144-byte UTF-8 snapshot
ceiling, then revalidate generic hashes/history. Compare exact scope, adapter,
generation, policy and artifact set. Artifact array order does not grant authority;
profile hashing orders kinds. Strictly validated integral policy versions compare
by exact safe numeric value, not Jackson IntNode/LongNode width. A real parsed-JSON
positive regression failed before that narrow comparison repair; no string/number
coercion or global canonical-profile change was introduced.

The bridge accepts only HTTP `github.pull_request.create`, `github`, the selected
non-production environment and `pull_request`, with exact full repository in both
arguments and target account. Only `repository`, `title`, `head`, `base`, `draft`
and optional textual `body` arguments are allowed. Draft must be true. The existing
adapter parser still validates bounds, but its interpretation must equal the stored
title/head/base/repository: trimming/case repair and split owner/repo aliases are
rejected, not normalized after hashing. Body retains exact decoded string content;
absent and empty bodies remain distinct stored action data.

## Versioned hash recipe

Every new hash material includes `profile: tracepermit-github-draft-pr-binding-v1`
and the indicated `kind`. Use unchanged canonical JSON v1 and prefixed SHA-256.

1. `resource_identity`: `system: github`, installation ID, immutable repository ID,
   immutable owner ID and full repository. Existing positive safe-integer limits
   apply; wider values are not rounded into an accepted identity.
2. `resource_precondition`: the resource identity hash, head/base refs and both
   commit SHAs. Store its hash as `precondition.valueHash` with explicit
   `kind: resource_state_digest`. No `none`, single-SHA or alternate kind is inferred.
3. Expected resource binding includes system, exact environment, `pull_request`,
   identity hash and that precondition. The generic validator independently
   verifies the enclosing `resourceBindingHash`.
4. `expected_profile`: adapter, environment, recovery generation, exact policy,
   sorted artifact map and the existing target-binding fingerprint.
5. `bound_operation_data`: trusted scope fingerprint, full operation snapshot hash,
   expected-profile hash and existing target-binding fingerprint.

The separate [golden recipe](../../contracts/vectors/github-business-operation-binding-v1.json)
names its unchanged base vector and exact transformation and contains independently
calculated hashes. It is test data, not a public request schema or signed artifact.
The frozen generic vector/schema and valid generic hashes remain unchanged.

## Data is not authority

Return defensive snapshot/action copies, original operation ID/generation/action
hash/target key, immutable target binding and profile/data digests. No permit or
claim is returned. Valid `awaiting_approval`, `approved`, `claimed`, `executing`,
`unknown`, `succeeded`, `failed` or `cancelled` labels are still only validated
data. A rehashed changed title or distinct operation can produce distinct bound
data; this does not prove it is the persisted/approved record. A database adapter
and future trusted executor must independently load and authorize it.

Keep ADR0068's operation/adapter/generation target key distinct from the adapter's
process-local installation/repository/head/base key. Neither is a native GitHub
idempotency key, fencing token or exactly-once guarantee. Hashes are unkeyed and
provide no confidentiality or independent authenticity against a coherent rewrite.

All errors collapse to `GITHUB_OPERATION_BINDING_INVALID`; cause initialization,
suppression and stack storage are disabled. Profile/policy/result string forms
are redacted. Full snapshots remain sensitive internal data, not redacted export
objects. The bridge does not parse raw JSON: strict byte parsing/duplicate-key
rejection belongs to the upstream trusted store/transport, not a JsonNode tree.

## Proof and limits

Tests cover independently frozen hashes, parsed numeric widths, exact positive
profiles and body handling, generically valid rehashed mismatch cases, target
identity/SHA changes, scope/generation/policy/artifact substitution, unknown fields,
normalization refusal, safe-integer neighbors, defensive copies, UTF-8 bounds,
fixed diagnostics and lifecycle labels as data. No transport, credential, signing,
database or target effect is constructed by the bridge tests.

Still pending: authenticated AgentCore/OBO admission, current enrollment/policy/
independent approval rechecks, atomic permit/claim/evidence transaction, durable
target dispatch and restart-safe reconciliation, MariaDB authority composition,
credential minting, S3/EKS/RDS/live sandbox qualification and older-backup fencing.
Do not wire `BoundOperationData` directly to the existing adapter execute method
and advertise durable-operation enforcement. The future executor must join those
boundaries without redispatching an uncertain side effect or calling a target
under a database lock. Rollback removes this unused library/vector/docs only;
there is no migration or stored-data deletion.

## Organization sandbox profile addendum

The organization sandbox candidate adds a second closed profile without changing
the original `tracepermit-github-draft-pr-binding-v1` recipe, adapter ID, hashes or
constructors. `GitHubOperationProfile`, `GitHubTargetBinding` and
`BoundGitHubOperation` are sealed and permit only the original Track E types and
the new organization types. Registration and the V4–V7 durable lifecycle depend
on those sealed contracts; request data cannot supply another implementation.

`GitHubOrganizationTargetBinding` additionally pins App ID and the exact minimum
permission map (`metadata:read`, `contents:read`, `pull_requests:write`) alongside
installation/repository/organization-owner IDs, exact repository, refs and SHAs.
`GitHubOrganizationOperationBinding` uses adapter `github-org-draft-pr-v1` and
profile `tracepermit-github-org-draft-pr-binding-v1`. It retains only explicit
`dev`/`test`/`staging`, draft-only composition and exact action equality. Permit
codecs now receive the expected adapter from trusted composition; their legacy
constructor still pins `github-draft-pr-v1` and preserves existing bytes.

This is a library-only candidate. It does not authorize the existing process-local
adapter for the organization profile or add a real durable target. Exact credential
custody, permit-key custody, ingress/sender identity, organization inputs and EKS
containment remain approval gates in the
[composition contract](../design/okta-github-org-sandbox-composition.md).
