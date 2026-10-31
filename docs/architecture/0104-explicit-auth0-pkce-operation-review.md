# ADR 0104: Explicit Auth0 PKCE operation review

Status: implemented; validation pending.

Requirements: FR-003/007/008/009/012/015; SEC-002/003/004/006/009/011/012/015;
REL-001/002/004/005; DATA-001; OPS-002/004. Partial component scope only.

The existing identity-only reviewer helper remains separate from durable
approval. A second installed launcher, `tracepermit-auth0-operation-review`,
accepts only `check-config` and explicit `review`. It consumes exactly three
length-prefixed frames and EOF before effects: reviewer trust configuration
(16,384 bytes), organization configuration (65,536 bytes), and decision intent
(1,024 bytes). There is no caller-supplied bearer, private key, callback, trusted
context or execution option. Application arguments contain only the command.

The decision intent has exactly schemaVersion, profile, operationId,
expectedSnapshotHash, decision, authorityProfileFingerprint and
expectedProfileFingerprint. Schema is 1.0; profile is
`tracepermit-auth0-operation-review-v1`; decision is APPROVE or DENY. UUID and
hashes are canonical, and both profile fingerprints must match the private
organization configuration. The pending snapshot hash is calculated by the
existing Java canonical JSON recipe from a separately obtained exact pending
snapshot, not from serialized SQL JSON text or a caller's policy proposal.

Offline preparation requires the explicit Auth0 organization profile and exact
agreement with the PKCE helper's issuer, API audience, reviewer client, expected
human roster subject, tenant mapping, key ID/SPKI and fixed namespaced claims.
Unknown or mixed provider bindings fail before opening a browser or connecting
to storage. Existing Okta constructors, token grammar and installed commands
retain their behavior. No provider settings or grants are changed by this code.

For explicit review, a controlling terminal must be available before login.
The existing code/S256/state/issuer callback and fixed HTTPS exchange are reused
without refresh or retry. The verified bearer is retained only by a package-local
closeable credential, copied briefly for the concrete review boundary, and
cleared from owned byte buffers. Java Strings and provider parsing copies cannot
be guaranteed zeroized. Identity-only login still discards the bearer and never
invokes the review facade.

The concrete review facade re-verifies raw bearer bytes through the existing
Auth0 authority, requires exact independent human enrollment, and loads only the
pinned pending operation. This permits a narrow reviewer preview of that exact
operation under operations.decide, not listing, owner impersonation or execution.
The private controlling terminal displays the canonical action and all snapshot
and profile pins with control/non-ASCII escaping. An exact decision/UUID/hash
confirmation line is required. Terminal input records intent; it is not identity
proof and never replaces OIDC verification or the independent roster.

After confirmation, the same bearer and exact snapshot are checked again. The
existing durable review transaction rechecks the complete StateToken after its
control/head locks, database time, current profile/generation, policy, approval
expiry and membership/identity deadlines. A changed snapshot is not refreshed
or silently accepted. There is one attempt per prepared plan. Commit, storage
or command uncertainty remains UNKNOWN with automaticRetryAllowed false.
Readback/reconciliation is separate; there is no automatic resend.

This new command has a 240-second total budget with six seconds reserved,
5-second input, 1-second terminal availability, existing 120-second login,
15-second preview, 30-second confirmation, existing 60-second review and
1-second receipt caps. These are absolute per-stage caps clipped to the total
remaining time. Neither the original login nor organization operator budgets
are extended. Owned daemon stage threads/descriptors end with the helper
process; a review timeout does not prove rollback or cancel an acknowledged
approval. No signer, permit issuance, admission, delivery or target callback is
exposed by the new review facade. Receipts remain execution-disabled.

The shared synthetic organization fixture and cached-image policy are moved to
test fixtures so both modules exercise identical profiles without copying trust
logic. Tests specify offline framing/binding, signed PKCE credentials, exact
terminal consent and real restricted MariaDB 11.4.13 review, denial, changed
snapshot, quarantine and unavailable-storage cases. No real provider is called
by these tests. All new compilation, tests, repository gates and coverage are
UNRUN. Interactive installed live review, authenticated remote ingress, EKS
custody and the live GitHub executor remain separate integration/acceptance work.
See the [manual runbook](../runbooks/auth0-operation-review.md).

The initial human focused attempt at d17e2465 stopped on malformed bearer input
being classified UNKNOWN. The correction handles only format rejection before
runtime construction or JDBC work. Storage/commit uncertainty and unexpected
post-attempt failures remain UNKNOWN. No assertion, deadline, grant or retry
rule is relaxed; the original failure and fail-fast skip remain recorded.
Qualification of the corrected candidate is pending.
