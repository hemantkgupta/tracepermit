# Assemble the offline P1 rehearsal readiness packet

Status: **implemented; new validation UNRUN**. This is a human-run preparation
workflow over the [readiness contract](../planning/eks-p1-rehearsal-readiness.md).
It adds no cluster collector, deployment command, login, provider action or
operator administration adapter. The writer does not execute validation.

## Separate local stages

The coordinator supplies a private Bash runner pinned to the exact checkout,
branch, commit/tree, source hashes and prepared Java21/Node22+/pnpm11.25/
Gradle8.14.3 offline cache. Run only the explicitly requested stage; every stage
creates fresh0700 logs outside Git, captures stdout/stderr and numeric exits,
stops on failure and never starts the next stage. The public forms are:

```text
bash ABSOLUTE_PRIVATE_RUNNER focused
bash ABSOLUTE_PRIVATE_RUNNER packet
bash ABSOLUTE_PRIVATE_RUNNER repo
```

These are interface forms, not runnable commands. Use the coordinator's actual
absolute path and source-pinned handoff. Do not reuse PR144's runner: it is
pinned to a different branch/tree. Do not rerun a passing stage automatically.

1. **focused:** Existing54 EKS tests, including36 PR144 cases, plus installation
   of the same EKS distribution. No tests or security assertions are removed or
   added by this documentation packet. Record actual executed/up-to-date/cache
   results. Archive hooks capture the distribution before and after test
   execution; fresh XML and archive inventory must match the candidate.
2. **packet:** Requires that exact candidate's latest successful focused receipt.
   Copies only the source-free installed EKS distribution, tested archive and
   explicit public planning/template files into a new private packet. Records
   file hashes, source/dependency identity and unresolved decisions. Rejects
   symlinks/nonregular distribution content and unexpected/oversized inventory.
   Copies the generated distribution directory rather than extracting supplied
   archives. No launcher or cluster command is run. Templates stay blank.
3. **repo:** Requires matching focused and packet receipts. Runs the unchanged
   `pnpm check`, `pnpm format:check`, documentation link/helpers and13flow/drift
   checks, diff and aggregate94LINE/75BRANCH gates. Preserve inherited skips and
   actual reused results; no redundant second aggregate or CI retry.

The packet directory and receipts are owner-private, not public release assets.
The local file manifest checks copying/inventory only. It is not Cloud2's strict
archive verifier, artifact signature, approval attestation or deployment check.
The EKS distribution is distinct from Air's organization image/archive and
from the legacy operation-lifecycle archive.

## Private org-input preparation

After the local stages are reviewed, use the packet's blank campaign and input
sheet privately with the named org owners. Complete only non-secret selections
needed by the chosen stage. Keep future P2/P3 rows explicitly not applicable
with owner/reason; do not invent a client, identity provider pin or target.

Do not set a real org plan until the actual release, organization image,
preinitialized store/PVC, authority profile/generation, topology and approved
administration procedure are selected. A copied template or literal boolean
is not evidence that a named person approved anything. The campaign record is
for human review and is not consumed as an authorization token.

The runner also defines later **prepare** and **assess** offline forms. They
require all local stages and the exact copied distribution:

```text
bash ABSOLUTE_PRIVATE_RUNNER prepare ABSOLUTE_PRIVATE_PLAN
bash ABSOLUTE_PRIVATE_RUNNER assess ABSOLUTE_PRIVATE_PLAN ABSOLUTE_PRIVATE_SNAPSHOT
```

No runnable org-specific command is supplied until the actual private paths
and stage scope are reviewed. Existing Java private-file checks and closed
schemas apply. The runner hashes inputs before/after without printing bodies;
it does not create, edit or refresh observations. `prepare` writes a new local
suspended manifest and catalog, never applies them. `assess` writes only the
existing REVIEW_REQUIRED/BLOCKED result. A blocked/refused exit stops the stage;
there is no fallback, automatic retry or automatic transition to a cluster run.

## Before any cluster action

The org must provide the exact non-secret cluster/context identifiers,
compatibility/topology review, runtime/organization image digests and archive
provenance, namespace/node/storage/identity ownership, network positive-control
destination, approved input/audit custody, workload/window and go/no-go signers.
The separately authenticated exact-profile quarantine procedure remains a hard
gate. No admin connection/credential is requested by the packet or runtime.

The [worksheet](../../deploy/sandbox/eks/readiness/control-ledger.csv) maps each
catalog control to its owner, expected observation, evidence boundary and stop
condition. Org operators must separately approve exact probe commands against
the selected sandbox. This repository packet contains no `kubectl`, AWS,
registry, provider, SQL-administration or deployment execution stage.

Return stage-result.json, numeric exits and bounded summaries. Keep raw private
inputs, manifests, XML/logs and images outside public discussions. Do not report
P1 accepted, a target count, live Okta/GitHub qualification or external
exactly-once behavior from this local packet. Full19-field handoff must retain
the exact tree, archive/image identity, failures/unrun stages, runtime/cache
limits, source drift, cleanup observations and ownership release.
