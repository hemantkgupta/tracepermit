# Optional process metadata is not worker identity

Java's process information is optional. A live child can have no reported
arguments. In the issue126 Linux probe, the cached Java21 runtime reported a
short command normally but omitted argument metadata for an8KB classpath. The
actual extracted HTTP archive produced a4130-byte worker command and the same
missing-argument observation. Absence of metadata did not mean absence of the
worker.

The old test looked for the worker class name in optional arguments. Its failed
cardinality assertion happened before the worker-termination assertions; that
result did not demonstrate a failed runtime kill or an orphan.

The test-only replacement first requires a live direct child of the particular
parent started by the test. It checks the exact Java executable and complete
worker launch shape. Only when optional arguments are missing on Linux does it
read a bounded kernel command record for that owned child, with ownership and
liveness checked again afterward. Truncated/oversized records, wrong Java/main,
class names embedded in another argument and unknown metadata all refuse a
match. It never selects an arbitrary descendant merely because one exists.

This is a process-test harness, not an authentication or execution-authority
mechanism. It does not establish protected key custody, org sandbox isolation,
or safety of inspecting an unrelated process. The installed CLI protocol,
registration's execution-disabled state and every runtime trust check are
unchanged.

Resource ownership begins as soon as a test starts a process. A later READY
assertion cannot defer that responsibility: failed startup still needs bounded
cleanup of that owned process and its reader. Likewise a group finish must
attempt every session even if an earlier receipt fails, while retaining the
original failure and later failures rather than skipping the remaining checks.
An owned parent exiting promptly is not proof that its captured workers exited:
the retained negative control demonstrated a surviving worker until the harness
was repaired to force and observe captured children within the same cleanup budget.

Safe diagnostics report fixed phases, validated outcome labels, counts and
metadata availability. They do not print command lines, configuration, bearer
tokens, child output or exception strings. The
[reliability record](../runbooks/issue126-process-test-reliability.md) separates
these proofs from unreproduced CI failures; the
[org sandbox plan](../runbooks/org-sandbox-acceptance-plan.md) defines the
separate deployment acceptance work.
