# GitHub pilot operator diagnostics ownership

Cloud 1 claims sole writer ownership of `codex/github-pilot-operator-diagnostics`,
starting exactly from PR105 `c52ba244463ff7d363d8e30a21b76dd2a50892e4`.
Producer branches remain read-only and unmerged. ADR0081 is reserved for this slice;
it was absent from the pinned source, current main architecture directory and
open-PR search at startup.

Scope: a new Java21 `pilot-operator` module with offline private-configuration
validation, an explicit read-only exact target preflight and a source-free archive.
The existing package-private preflight and fixed transport will be reused from
new composition code in the same Java package, without reflection, copies,
existing runtime edits or new production trust/origin injection.

This is no admission, approval, operation-permit issuer, dispatch, reconciliation
or target execution authority. All qualification uses generated synthetic keys
and fixtures; no live provider/target calls. Final evidence and writer release
will be published in the draft stacked PR handoff.

## Installed-byte qualification repair

Current-main qualification review found that the original installed archive DNS/
provider probe launched with the build test runtime classpath. The runtime behavior
was not bypassed, but that probe did not establish that its production classes came
from the extracted archive. The test/build harness now packages only the probe and
denying DNS SPI in a separate helper JAR, loads every production class/dependency
from the distribution `lib` JARs, and verifies exact class origins plus both runtime
and helper inventories. The old build classpath is an explicit failing control.
No production, launcher, dependency, CI, authority, timeout or workload behavior
changes in this repair; final exact-head evidence belongs to the PR handoff.
