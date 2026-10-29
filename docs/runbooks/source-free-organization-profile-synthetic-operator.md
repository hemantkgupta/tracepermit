# Source-free organization-profile synthetic operator qualification

Status: **implemented; validation pending**.

Requirements: FR-001/008/009/012/015; SEC-001/002/003/006/007/008/012/019;
REL-001/002/004/005/008; OPS-002/004/005. This is local synthetic evidence only.

## Candidate and ownership

The working branch is `codex/source-free-organization-operator`. It preserves
published qualification commit `8108598a658121735dbd59d5a44053b3ff22da7a`
and the implementation checkpoint below. Accepted main through PR133 and PR134
is `dfb7b3b11ed7af3b4d0230fae9adf6e514289ba5`, tree
`0a9012fdedb7a2db72aba37de93e2adc891df48b`. The normal integration merge is
`878013d68e90815fde57d792cf239f86338ae505`, tree
`b228fd7a12e9a63f9c1b912751e8d7e79c098e4d`, with ordered parents
`317e333d17b79ae5c64a11efcea366ab9ade9b0e` then accepted main. Do not rebase,
cherry-pick, reset or force-update this history.

The first human focused attempt at preparation HEAD `e78cde9` stopped during
test compilation, before tests or containers, because one direct frame assertion
did not declare `IOException`. The compilation-repaired candidate is
`77b3b0f48e59c68f94d88125718f4084bab91407`, tree
`d30e5a41e81633c7aeae267b31cfbbe170ec03c6`, parent `e78cde9`. Its only repair
is the checked-exception declaration on that existing test method.

The second focused attempt at preparation HEAD `de19b75` compiled and ran all
16 selected cases; two receipt cases and all four installed-process cases failed.
They exposed one shared invariant defect: the decoder/supervisor's required
fail-closed `UNKNOWN` receipt was rejected before worker launch. The current
source candidate is `040a4882a2ee46017836aca4580ad1f746e3a367`, tree
`55abed077aaefa9a7a146aceea39bf755a670e29`, parent `de19b75`. It narrows the
usage guard so `UNKNOWN` remains a valid fail-closed fallback without admitting
`USAGE` for a known command.

Focused validation then passed all 16 cases with zero failures, errors or skips.
The first repository attempt at preparation HEAD `2791b1f` passed the console
build/typecheck/124 tests and 152 Java-environment scenarios, then stopped in
the durable-registration module. Its PostgreSQL16.4 and MariaDB11.4.13 suites
each failed only the legacy public-surface assertion because the approved
additive `organization` factory was absent from its exact name set. The current
source candidate is `34a2bd66896829805b9b406148be2655fa43751f`, tree
`2a7b5cc01627e7201541e4f55524e001fe379b1a`, parent `2791b1f`. Its test-only
repair requires exactly `create`, `organization` and `register`; repository
validation then reached the complete aggregate with every test task passing.
The unchanged coverage gate stopped it at 92% line versus the required 94%.
Installed child JVMs prove the extracted contract but do not contribute their
instructions to the Gradle test JVM's JaCoCo data. The current source candidate
is `2bcfd4a2c7b249df77101c2e6d7d17ee47141d78`, tree
`82cd525a6506117c3ebba1f1ec8017bd1eb7166a`, parent `5296583`. It adds
in-process equivalents for valid, denied, UNKNOWN, reconciliation and no-resend
runtime paths plus protocol, receipt, entrypoint and finite-supervisor boundary
tests. No production exclusion, threshold, instrumentation hook or runtime
behavior changed. The next complete aggregate passed every test task and reached
19,104/20,324 covered lines (93.997245%) with 76.588884% branch coverage: one
additional covered production line was required. The current source candidate is
`42f2f811a5a8e175d6447d2f4402d79d29ab4ac1`, tree
`3fa2bd18dc4a7ea0db4f24bd7528f97cbf2eca2c`, parent `87aee36`. Its existing
boundary case now invokes `OrganizationFailure.initCause` and asserts that the
redacted failure retains no private cause. Counts, thresholds and production
behavior remain unchanged. That candidate's focused 24/24 cases then passed and
the repository check passed at 94.012006% line/76.588884% branch. Formatting
stopped on three Markdown files: one missing heading separator and two table
alignments. The repository formatter changed no prose. Because those files and
the runner do not alter the pinned source manifest, repo mode may reuse this
exact green repository/coverage receipt after a fresh focused receipt; it
refuses reuse across any other path, candidate or manifest. Remaining
documentation and diff validation is pending.

The next focused attempt at preparation HEAD `c63c917` again executed all 24
cases. Its installed eight-contender case produced one acknowledged success,
six explicit rejections and one conservative `UNKNOWN`. PostgreSQL may expose
a concurrent pre-effect admission write as storage unavailable; the operator
already maps that uncertainty to a nonzero, redacted, no-retry receipt. Requiring
every loser to be `REJECTED` incorrectly excluded that fail-closed contract. The
current test-only candidate is
`aff6f73fd542e941a5e58b4349d5505445223c28`, tree
`aefbe614e4394b55811c6b171101a909f7abe10c`, parent `c63c917`. It retains the
maximum-one-success and exact receipt-count assertions while requiring every
loser to be only `REJECTED` or `UNKNOWN`, exit nonzero and carry no operation or
dispatch metadata. Production behavior, process deadlines and contention remain
unchanged. Because Java test bytes changed, the earlier repository/coverage
receipt cannot be reused; focused and full repository validation are pending.

Focused validation at preparation HEAD `95a3bfe` then passed all 24 cases. Its
repository run passed the console and Java-environment stages before the
pre-existing MariaDB11.4 installed operation-lifecycle contention case rejected
three conservative `UNKNOWN` losers. The human explicitly approved the narrow
test-only lifecycle fixture correction. The current repository candidate is
`288058c3bdc2221995181391b39e20722bea0c95`, tree
`4e09f57f440fc8f5c03985c2e7e557773384abd0`, parent `95a3bfe`. It preserves
the maximum-one-success, nonzero-loser, one-admission, delivery-event and
reconciliation assertions while allowing each redacted non-success receipt to
be only `REJECTED` or conservative `UNKNOWN`. No production behavior, workload,
deadline, grant or target-call assertion changed. Because repository test bytes
changed, validation must restart from focused mode and perform a fresh complete
repository run; both are pending for this candidate.

The source/documentation implementation checkpoint is
`a9270e4651d39d4825b2ef3efcaccce684760aa7`, tree
`f3ad45469efddf2f6edf6b4fdd65622db2219651`, with the integration merge as its
sole parent. Its parent is the earlier accepted-main/qualification merge
`5da2b6d48ba592537f1b9cf23dae753ddb604feb`; later normal merges preserve it.
This is an unvalidated local checkpoint, not a qualification receipt or
publication. The current candidate's relevant source manifest is
`e5bfaf67d9b98fad39a2ec25760fb271dd061ebee5bcf9fe00311af8fed40312`.

This lane owns the new executable module, its tests/docs and the additive durable
organization factory. It does not own EKS, installed COMMIT-loss injection,
artifact/identity verification or credential-custody corrections. PR134 owns
ADR0094 and retires MariaDB10.x integration qualification. Retain its historical
9/9 library receipt as history, preserve PostgreSQL16.4 and MariaDB11.4.13, and
do not add an installed 10.6 launcher or another exclusion.

## Closed installed contract

The executable is `bin/tracepermit-synthetic-organization-operator`; the profile
is `tracepermit-installed-github-org-synthetic-operator-v1`. Input is big-endian
length-prefixed frames followed by EOF:

| Command                            | Frames in exact order                                                  |
| ---------------------------------- | ---------------------------------------------------------------------- |
| `check-config`                     | configuration                                                          |
| `register`                         | configuration, requester compact JWT, registration request             |
| `inspect`, `evaluate`, `reconcile` | configuration, requester compact JWT, operation command                |
| `review`                           | configuration, reviewer compact JWT, operation command                 |
| `deliver-synthetic`                | configuration, requester compact JWT, Ed25519 PKCS8, operation command |

The root configuration has exactly `schemaVersion`, `profile`, `replay`,
`operations`, `replayCapacity`, `replayDmlMillis`, `scope`,
`organizationTarget`, `authority`, `requesterProvider`, `reviewerProvider`,
`requesterEnrollment` and `reviewerMemberships`. Both stores are distinct
numeric-loopback PostgreSQL16.4 stores; capacity is exactly 64 and the replay DML
budget exactly 2,000ms. Unknown fields, type coercion, duplicate keys, trailing
data, malformed UTF-8, unsupported engines and substituted enrollment/profile/
target/authority pins refuse.

Frame limits are configuration 65,536 bytes, JWT 16,384, registration 32,768,
private key 128, command 1,024 and receipt 2,048. A command gets 60 seconds total,
owned cleanup five seconds and receipt writing one second. Installed test cases
retain a 240-second ceiling; boundary tests retain 30 seconds, container startup
60 seconds, JDBC connect/query/lock five seconds, socket ten seconds and eight
contenders.

## Focused manual validation

Do not run the runner automatically or syntax-check it as part of preparation.
The human runs
`/Users/hemantkgupta/code-all-air/tracepermit/scripts/validate-source-free-organization-operator.sh`
first with mode `focused`, then with mode `repo`, using the exact preparation
HEAD supplied in the current handoff. The runner creates mode-separated private
logs beneath `/private/tmp`, writes numeric exit files for every stage and stops
on the first non-zero exit. It refuses a dirty or different checkout, source
drift, missing prepared caches/images, a reused receipt directory or repository
mode without a passing focused receipt.

Focused mode executes one cache-disabled, forced Gradle invocation with one
Gradle worker and seven exact selectors: four protocol, two receipt, four
identity, two archive, four boundary, two supervisor and six PostgreSQL cases
(24 total). It does not alter test-internal concurrency, deadlines, grants,
workloads or cleanup.
The installed selector retains denial, independent review, substitutions,
UNKNOWN/restart/no-resend behavior and eight contenders. The actual archive
used by that invocation is immediately copied into the private receipt; its
SHA256, tar inventory, outside-checkout extraction path, extracted inventory
and unique production class origins are captured before any repository stage.
No database fact is relabeled as an externally observed target invocation.

The installed contract uses a tar extracted outside all checkouts, a Java-only
`PATH`, production class origins, runtime-generated RSA/Ed25519 material,
restricted grants and separate processes for register → inspect → evaluate →
independent approve → deliver → reconcile. Denial, restart, recorded unknown,
no-resend and eight contenders remain active. Do not describe database events or
missing receipts as an observed target invocation count.

## Separate repository and coverage gates

Repository mode preserves all pre-existing Java build outputs in the private
receipt before removing them from the checkout. With no Java outputs and Gradle
build/configuration caches disabled, unchanged `pnpm check` performs the single
fresh complete aggregate and enforces the unchanged 94% line/75% branch floors.
There is no second `checkAll` invocation. The runner records the newly generated
aggregate XML and requires its timestamp to follow repository-check start.
Separate stages then run `pnpm format:check`, documentation link/helpers,
13-flow/drift checks, the four accepted CI-policy helper suites and committed,
working-tree and index diff checks. PR134's five coverage shards remain policy
evidence; this runner does not start hosted CI.

After a later documentation-only preparation commit, the optional
`TRACEPERMIT_REUSE_REPOSITORY_RECEIPT` may identify a prior private `repo`
receipt. Reuse succeeds only when repository check and aggregate coverage both
passed, candidate and source-manifest provenance match, the stored coverage XML
matches its SHA256, and every intervening path is the runner, this runbook,
`HANDOFF.md` or the traceability matrix. The runner copies those immutable
receipts into the new private directory and continues at formatting; otherwise
it stops before accepting the prior result.

Keep 94% line and 75% branch coverage floors, every assertion, timeout, grant and
workload active. Preserve and diagnose the first failure before any new command.

## Qualification boundary

A green run would qualify only the extracted local PostgreSQL synthetic operator
candidate and repository gates at its exact source/tree. It would not qualify a
live Okta/GitHub integration, credential custody, EKS containment, remote ingress,
deployment, external target-call count, target-side exactly-once behavior or
production readiness. Publication remains a later human instruction; keep any
future PR draft and unmerged.
