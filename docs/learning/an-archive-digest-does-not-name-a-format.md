# An archive digest does not name a format

Two readers can agree on every byte and disagree about whether those bytes
belong to their supported format. GNU long-name records are understood by some
readers; a strict USTAR-only reader must reject them. Broadening that reader
changes its contract rather than repairing the producer.

The lifecycle producer now prepares standard name/prefix fields before a fresh
distribution task completes. Full paths, permissions and payloads must stay
intact. A new digest identifies the result; the old rejected artifact and its
receipt remain unchanged.

Manual qualification compares the fresh raw/final inventories and uses the
unchanged published parser as an independent comparator. That checks
compatibility, not source truth, credential custody or permission to deploy.
See the [runbook](../runbooks/lifecycle-archive-compatibility.md).

A format pass also does not qualify every build mode. A Kotlin task action can
produce valid bytes without configuration caching and fail when CI serializes
that action. Capture task inputs/services at configuration time, then manually
test both cache creation and cache reuse with real task execution. Disabling
the CI cache would hide that integration defect rather than qualify the fix.
