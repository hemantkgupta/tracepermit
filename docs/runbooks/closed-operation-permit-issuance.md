# Closed operation permit issuance — not admission

Requirements: FR-007/008/009/012; SEC-001/002/003/004/005/006/009/010/012;
REL-001/002/004/005; DATA-001/004; OPS-002/004. This is partial library evidence,
not completion of these requirements or customer-sandbox readiness.

## Exact boundary

`JdbcGitHubOperationPermitIssuer` is an opt-in Java library on the existing V4
operations schema. It loads actual closed approved history itself; callers
cannot provide approved JSON, proposed claims, a verification flag, caller clock,
signer callback or target. No endpoint, default launcher or service is wired.

Issuance does not consume a permit, allocate a durable attempt, advance execution,
write an outbox or authorize a target call. There is no admission API in this
checkpoint. The legacy simulator permit format is unchanged and cannot be used
as the new operation format. No live provider or target is contacted.

## Configuration and compatibility

Explicit `BusinessOperationAdmissionSettings` require operator issuer, key ID,
audience, standard Ed25519 public key and a positive configured lifetime through
60 seconds, with nanosecond precision and no default. Key material is pinned by
a domain-separated fingerprint of canonical Ed25519 SubjectPublicKeyInfo, not
just its label. Ed448, missing/invalid keys and a mismatching signing private key
refuse with fixed diagnostics.

The eight-argument `BusinessOperationAuthorityProfile` creates profile v3, pinning
all admission and review settings before evaluation. Existing six/seven-argument
v1/v2 fingerprint recipes are unchanged. Their approvals remain readable but
are ineligible for this issuer. A different current profile cannot silently
promote an old approval or replace same-generation authority. Normal operator
replacement requires a newer generation and explicit activation; obtain a new
operation under that profile rather than rewriting approved history.

V1-V4 migration bytes, default V2 installation, current V4 journal vocabulary,
registration semantics and runtime grants are unchanged. No migration or new
table is added by issuance. The issuer uses a writer connection and existing
row-lock privileges even though it performs no DML; it is not a database
read-only-role deployment claim.

## Observation, deadlines and signing

One READ COMMITTED connection locks the current scope/profile/generation control
first, then the actual operation head. Original registration, coherent closed
current/history, evaluation binding and exact evaluation/APPROVE facts are loaded
on that connection. Fresh database time is sampled after lock waits. The issuer
repeats exact expected whole state, independent strict GitHub action/target join,
current policy/profile and fresh exact owner/workspace `operation_requester` plus
`operations.execute` permission. Pending, denied, cancelled, stale, quarantined,
unsupported-version and mismatching-profile states refuse.

Expiry is the minimum of the configured lifetime and all human-approved bounds:
original approval deadline, original evaluator authentication expiry, recorded
reviewer authentication/membership expiries and fresh owner-execute expiry.
Equality is expired. Existing credential fingerprints need not be identical;
fresh exact-owner identity does not extend any original deadline. No live
membership/revocation lookup or reviewer re-enrollment is implied.

The read transaction rolls back and its connection is closed before private-key
signing. Failed read/rollback/close yields no returned or signed permit. Signing
delay never resamples or renews issue time or expiry: a caller may receive an
already expired signed value. It remains inadmissible; a future admission must
independently check current state and fresh database time. Issuance is not a
response lease, DB/target transaction or continuous authority fence.

## New closed wire vocabulary

The exact JWS protected header is canonical JSON with `alg: EdDSA`, explicit
`kid` and `typ: tracepermit-business-operation-permit-v1+jws`. Only Ed25519 is
accepted; no algorithm negotiation, key URLs, legacy promotion or request-selected
key exists. Base64url is unpadded and canonical. The entire token is at most
16,384 characters, payload at most 8,192 decoded bytes and signature exactly 64
bytes. Local strict decoding rejects duplicate/unknown fields, trailing tokens,
invalid UTF-8, noncanonical JSON/numbers/instants and coercible scalar labels;
global canonical/JSON contracts are not changed.

Claims bind scope/owner/operation/adapter/audience; actual approved snapshot and
all state versions/generation; authority/admission settings; canonical action,
semantic payload, policy release, artifacts, resource/precondition and target
idempotency hashes; strict GitHub profile/digest at the approved anchor; immutable
evaluation binding and exact evaluation/APPROVE fact/actor hashes; execute
identity fingerprints and all original deadlines; distinct permit/attempt IDs,
issue/expiry and `maxUses=1`.

The digest at the approved snapshot legitimately differs from the immutable
evaluating-parent digest. Both are validated at their own anchors. A single
internal recipe builds the claims from loaded state rather than copying the
strict GitHub hash recipe. Package-internal verification returns privately
constructed signature-checked data, not a public verified authority interface.

`IssuedPermit.serialized()` is sensitive in-memory transport data. Never put it,
private keys or credentials in logs, files, evidence, Pages or PR comments. Its
wrapper diagnostics are redacted. Repeated issuance can produce distinct permit
and attempt IDs without changing history: only the future atomic admission may
enforce one attempt per operation across them. `maxUses=1` alone enforces nothing.

## Qualification and retained failures

`BusinessOperationAuthorityProfileTest` checks exact settings/public-key pins,
legacy v1/v2 recipes, profile v3 and separate exact-owner execute permission.
`OperationPermitCodecTest` uses generated keys, an independent literal header
and direct JCA signature verification, malformed signed wire vectors, type and
key substitutions, strict bounds and private checked construction.

The shared `OperationReviewContract` adds issuance on PostgreSQL 16.4,
MariaDB 11.4.13 and MariaDB 10.6.25: actual loaded approved anchors, one connection
and control/head order, no DML, every deadline minimum, expired original evaluator,
real head-wait expiry, old-profile refusal, genuine operator quarantine/new
generation, fresh same-owner credential, read rollback/close faults and delayed
private-key use after cleanup. Synthetic resources publish only on 127.0.0.1.

Initial codec qualification had one bad negative vector: frozen canonical JSON
renders floating 5.0 as 5, so canonicalizing the fixture erased the intended wire
type before verification. The vector now sends actual signed 5.0 bytes and adds
a separate string-type refusal; no canonical recipe or decoder was weakened.
The first 21-case engine subset passed 18 and failed 3 because a new test expected
profile-mismatch from duplicate `stageNew` INSERT. Source and XML showed the
existing storage-unavailable mapping. The corrected test exercises actual
same-generation `stageReplacement` refusal instead of mislabeling duplicate-key
behavior; the three selected cases then passed. No production operator rule
changed to fit these expectations.

Focused issuer/codec qualification then passed 36 cases (30 actual JDBC issuer
cases and 6 codec cases); valid application output was reused. Deliberately
omitting admission profile pins and sampling issue time before the head lock
produced four expected failures: the profile-recipe assertion and all three
real head-wait tests. Both production files were restored byte-identically,
verified with SHA-256 before the final forced complete operation run. These
negative-control failures are not a successful runtime qualification.

The final forced/cache-disabled operation suite passed 409 cases/12 suites,
zero failures/errors/skips, 18 tasks executed in 7m36. Final `pnpm check` passed
124 React and 2018 recorded Java cases/156 suites, zero failures/errors and one
existing absent-archive native-PTY skip. Its operation task reused those fresh
409 cases; other valid outputs were reused or restored from cache. Gradle4m18:
9 executed/1 from cache/55 up-to-date, NOT all tests fresh. Coverage94.7211% line
and78.5906% branch passed unchanged94/75 gates. Format,1173 links/3 helpers,
13 flow/drift cases and diff checks passed. Exact-head hosted results belong
to the new checkpoint handoff, not earlier e230972 green checks.
Complete datasource checkout and private-key-provider time bounds, cross-process
issuance, atomic consumption, attempts/outbox, live configuration and target
dispatch remain unqualified. Historical reconstruction is not fresh authority
or cryptographic protection against a privileged database administrator.

## Reproduce

Use existing Java/java compiler 21, Node 22+, pnpm 11.25.0 and Docker. Serialize heavy
builds on a shared physical machine; keep internal contender counts unchanged.

```sh
bash -c 'set -euo pipefail; source scripts/configure-java-environment.sh; exec java/gradlew -p java :persistence-operations:test --rerun-tasks --no-build-cache --no-daemon --max-workers=1 --console=plain'
pnpm check
pnpm format:check
pnpm docs:links
pnpm docs:flows:check
git diff --check
```

Do not install/reconfigure environments or restart/prune another worker's Docker
to obtain a green result. Preserve failed receipts and diagnose before rerunning.
