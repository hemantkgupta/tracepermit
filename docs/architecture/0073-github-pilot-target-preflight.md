# ADR 0073: GitHub target preflight and conservative outcome observation

Status: Implemented synthetic adapter foundation. Supersedes ADR0069's transport,
permissive confirmation and empty-list absence behavior; live target and durable
executor composition remain pending.

Requirements: FR-001/002/008/009/012/015; SEC-001/002/003/012/015/019;
REL-001/003/004/005/008/009; OPS-002/004.

## Decision

Production transport accepts only the exact operator-pinned GitHub.com API
origin `https://api.github.com` (optional root slash), platform TLS trust, and
one bounded, public-only DNS answer set pinned through connect. Userinfo,
paths, query, fragments, alternate hosts/ports, localhost and custom trust are
refused before credential dispatch. Numeric `127.0.0.1` HTTP is available only
through an explicitly named package-private synthetic factory. No public
constructor switches to test transport. There is no production environment
flag, custom origin, proxy, redirect or retry fallback.
One-exchange request tags also refuse an HTTP-library follow-up before a second
network request, including retry-advertising status responses.

Connect, read and write timeouts are bounded; a single monotonic budget of at
most ten seconds includes DNS and the complete preflight/write or lookup
sequence. Each response reads at most the decoded byte limit plus one overflow
byte, including chunked bodies. Responses, connections and the per-sequence
client executor close on every path. JSON rejects duplicate fields and trailing
documents. Diagnostics use fixed messages/codes and omit dependency causes.

Require a separate, trusted `GitHubPilotTargetBinding`: positive installation,
repository and owner IDs no greater than `9,007,199,254,740,991`, exact existing repository/base allowlists, one head,
and explicit 40-character head/base commit SHAs. It is operator input, not a
field added to the canonical action, a caller assertion, or execution authority.
Legacy construction without this record and optional upstream-only permit
verification no longer dispatch: a binding and request-bound permit authority
are mandatory. Existing unknown argument rejection and permit consumption stay
in force. Parsing and verification use one private action copy; the local ledger
retains the verified permit ID rather than its raw token. Contradictory
repository aliases/target identity are refused.

After current credential installation/expiry validation, claim the existing
process-local attempt, then observe with GET only:

1. `/installation/repositories?per_page=100&page=1`: require exactly one visible
   repository and no Link header. The installation token must be restricted to
   this dedicated repository; broader visibility is conservatively refused.
2. `/repos/{owner}/{repo}`: require exact immutable repository/owner IDs and
   names; private, unarchived, enabled and non-fork status. Validate the same
   identity in the installation repository response.
3. `/repos/{owner}/{repo}/git/ref/heads/{head}` and the base ref: require exact
   ref/type/commit SHA against the operator record. Recheck credential time
   immediately before the POST.

The credential provider remains a trusted port. GitHub's installation repository
list proves that the supplied token can see the repository; it does **not**
independently return/prove its installation ID. Binding that opaque credential
to the configured installation is the trusted provider's responsibility. No
App-JWT/token-minting/installation workflow is implemented or impersonated by
using an endpoint requiring another credential type.

A 201 confirms success only with positive integer IDs (no coercion), exact
GitHub HTML URL, `open`, explicit boolean `draft:true`, no contradictory merged
metadata, exact title/body, and both head/base ref, SHA and full repository/owner
identities. An unreadable, incomplete, oversized or contradictory confirmation
is `unknown`: a POST might have committed. Explicit 401/403/404/422 rejection
remains failed; redirect/other unexpected status remains unknown. Neither state
automatically re-executes. Preflight failure, before any POST, is failed locally.

Lookup revalidates credentials and the same GET preflight. It queries pulls
with explicit head/base/state/page bounds and never follows a Link. Only one
unpaginated, complete, open draft with exact pinned identities, SHAs and original
title/body confirms the **desired logical effect is present**. Empty, duplicate,
paginated, mismatching, historical/closed, unavailable or invalid responses
remain unknown, never failed. This observation does not attribute authorship
or causality to the timed-out POST. It is not a signed receipt, current permit,
or exactly-once proof. The resolver does not mutate/reset the execution ledger;
a durable, scope/lease-bound integration remains separate. Unbound legacy
lookup metadata cannot certify a result. Registration is immutable, and a
same-tuple changed payload cannot borrow cached success. An unsuccessful lookup
does not expose an older cached confirmation.
Cached receipts also bind the full operator target record and canonical action
payload hashes, preventing a reconstructed resolver or changed SHA/identity
configuration from borrowing an earlier receipt. These local comparison hashes
are neither the ADR0068 bridge nor independently authenticated evidence.

The explicit ID ceiling is required because the frozen canonical JSON v1
numeric profile converts through double precision. IDs `9007199254740992` and
`9007199254740993` previously aliased in the target fingerprint, allowing a
changed repository/owner binding to borrow an old cached receipt after resolver
reconstruction. Reject every operator ID outside the safe-integer range before
construction/dispatch. The largest allowed value and its neighbor remain distinct;
HTTP-confirmed cache regressions cover both repository and owner changes there.
This deliberately narrows the adapter profile; larger GitHub IDs require a
separately versioned exact-identifier design, not a fallback or global canonical
profile change. Fingerprints and cache comparisons for already permitted IDs
keep their existing representation.

## Frozen-contract and residual boundaries

ADR0068's generic `resourceIdHash`/`precondition.valueHash` does not define how
this legacy adapter's installation/owner/repository/SHA tuple is encoded, and
the adapter's closed canonical arguments cannot accept those new fields.
Therefore this slice provides the explicit record/preflight boundary, refuses
unbound execution, and does not invent a business-operation hash bridge or edit
the Mac-owned application/persistence lifecycle. That composition must be
designed and verified independently before dispatching stored operations.

GitHub create-PR has no documented native idempotency key or ref CAS. Refs,
repository state, permissions and visibility can change after GET. Post-write
SHA checks detect some races but cannot undo an already created PR. Matching
an observed logical effect is not proof of one unique historical creation.
The ledger and lookup metadata are process-local, unbounded-lifetime memory;
restart/replica deduplication, durable recovery, retention and production
capacity controls are unimplemented. No public ingress, operation API,
AgentCore wiring, live credential, live target or production readiness is added.

## Qualification

`GitHubDraftPullRequestAdapterTest` uses numeric-loopback MockWebServer peers,
runtime-generated permit keys and certificate material. It exercises positive
preflight/confirmation, strict negative identity/credential/destination/ref
cases, zero-POST rejection, ambiguous confirmation, conservative lookup,
chunked size bounds, cleanup, no cross-origin authorization and concurrent
single dispatch. Guard-removal positive controls are recorded in the PR; guards
are restored before mandatory gates. Official source facts and operator limits
are in the [dedicated runbook](../runbooks/github-pilot-target-preflight.md).
