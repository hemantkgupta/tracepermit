# Lifecycle archive producer compatibility

Status: **implemented; compilation and all new validation UNRUN**.
Base: accepted main `936ec9724f30eff3e3b2f02c39df8d37c1862ee7`, tree
`f9ef77909cc26cf47419516f39ed2cc17729c308`. This local Mac task changes
the legacy lifecycle TAR producer only. Air's organization packaging, Cloud1's
four COMMIT-loss tests and Cloud2's verifier remain separately owned.

## Producer contract

The published strict verifier supports standard USTAR regular-file/directory
headers, including155byte prefix and100byte name fields. A read-only inspection
of the prechange local lifecycle archive found nine GNU long-name records.
Those records can be understood by ordinary tar extraction but are outside the
strict verifier's format. The verifier stays unchanged.

The existing `distTar` retains its task type, output filename/root and consumer
property. After Gradle generates a fresh TAR, it saves that raw intermediate at
`build/tmp/ustar-producer/fresh-gradle.tar` and invokes a JDK-only build helper
from the separate `archiveTool` source set. Before the producer task completes,
the helper rewrites supported GNU names into standard USTAR name/prefix fields.
It streams bytes without filesystem extraction and preserves full paths,
entry order, types, permission fields, UID/GID, user/group fields, modification
times and payloads. Checksums change. No name is truncated or renamed.

The helper is absent from product JARs. Runtime/bootstrap/protocol/authority,
SQL/grants, CLI/supervisor deadlines and target bindings are unchanged. Header
permissions, including private-umask library modes, are preserved; the accepted
image recipe still owns public-library readability/immutability normalization.
The existing ZIP distribution is unchanged and has no USTAR compatibility claim.

Only module-rooted uncompressed regular-file/directory content and a single
pending GNU long-name record are accepted. Links, PAX/device/special records,
invalid checksums/numbers/padding, unsafe or unrepresentable paths, collisions,
missing terminators and over-bound data fail the build. Limits:256MiB source,
32MiB/file,128MiB expanded payload,4096 logical entries/name records and a
standard-representable ASCII-safe prefix<=155/name<=100 path. No format,
truncation or external-tool fallback exists. A complete temporary sibling is
atomically substituted only after source-stability checks; POSIX outer-file
permissions are preserved where supported. A failed finalization leaves a
failed build, not a releasable GNU fallback.

This is fresh producer finalization, not normalization of an already-qualified
artifact. Assign a new digest after completion. Preserve old rejected archives
and receipts; never replace bytes under an old digest. The raw intermediate is
a build diagnostic, not the release artifact.

## Separate HUMAN stages

The coordinator supplies exact checkout/commit/tree/runtime/cache-pinned Bash
commands with fresh private stdout/stderr logs and numeric exits. The writer
runs no build/test/syntax/container/CI command. Stop on the first failure.

1. **focused:** One offline/single-worker invocation selects31 new producer
   cases, two actual-distribution cases and seven existing archive/launcher/
   protocol/supervisor cases:40 expected cases, no new skips. Preserve fresh
   XML and before/after raw/final archive identities. Tests cover full long names,
   metadata/payload preservation, idempotence, unsupported/unsafe input,
   rejection without replacing input, build-helper exclusion and installed
   fixed USAGE/INPUT_INVALID receipts. Existing bodies/deadlines stay active.
2. **compatibility:** No distribution rebuild. Compare the focused raw/final
   ordered entry names/types/modes/UID/GID/owners/groups/times/sizes and streamed
   payload SHA256 values without filesystem extraction. Confirm no GNU/PAX
   records in the completed TAR. Compile a byte-unchanged public TAR parser with
   a tiny private classification adapter; require MATCH for the final digest,
   INVALID for the GNU intermediate and MISMATCH for a wrong digest. This checks
   the published TAR inventory/digest boundary only, not the evidence ledger,
   Cloud2's unpublished candidate or COMPLETE_AS_SUPPLIED truth.
3. **repo:** After reviewed focused/compatibility passes, run unchanged pnpm check,
   pnpm format:check, links/helpers,13flow/drift checks, diff and94LINE/75BRANCH
   aggregate gates. Record actual cache/up-to-date/inherited-skip limits. Active
   PG16.4/MariaDB11.4 policy remains unchanged;10.x remains retired. No automatic
   retry, workload reduction or extra aggregate is supplied.

Public comparator: PR141 head
`a1f67ff6419d464b16fa5b3d227b4d046b69b529`, path
`java/sandbox-artifact-verifier/src/main/java/io/tracepermit/sandbox/verifier/TarVerifier.java`,
Git blob `f0e627c540c6a599e6464ae9efc3728a0cc2a30b`, source SHA256
`821dc588ab50764e715ed96c0ac986b84f61db9c7ca2fbdd1769869c13acc5be`.
It is prepared privately as an unchanged reference, not a product dependency or
an edit to Cloud2. Future verifier/producer composition needs exact evidence.
The [published parser](https://github.com/hemantkgupta/tracepermit/blob/a1f67ff6419d464b16fa5b3d227b4d046b69b529/java/sandbox-artifact-verifier/src/main/java/io/tracepermit/sandbox/verifier/TarVerifier.java)
is the compatibility contract; task-time process execution uses Gradle's
[injected ExecOperations service](https://docs.gradle.org/current/javadoc/org/gradle/process/ExecOperations.html).

Gradle's `--offline` flag constrains dependency resolution to prepared caches;
it is not an independently enforced host/network isolation policy. Record that
distinction in the handoff. The comparator source reads local files only, but
this task does not certify another environment's egress policy or artifact trust.

## Handoff limits

Archive compatibility does not establish source truth, test execution, approval,
secret custody, deployment readiness, target counts or exactly-once behavior.
The new format/installed checks target the existing prepared Mac/Linux setup;
native Java8/Windows compatibility is not newly qualified by unchanged bootstrap
bytes. Return exact source/parents, raw/final hashes, entry/payload comparison,
comparator identity, numeric exits/tests/skips/cache, coverage and ownership.
No publication, CI retry, registry action, deployment or worker message follows.

See [ADR0093](../architecture/0093-source-free-operation-lifecycle.md) and the
[learning note](../learning/an-archive-digest-does-not-name-a-format.md).
Partial requirements: SEC-003/012, REL-002, OPS-002/004; no whole-ID promotion.
