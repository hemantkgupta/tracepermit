# ADR0095: Offline sandbox artifact and supplied-evidence verifier

- Status: proposed; number subject to coordinator collision check.

A module-local offline Java verifier accepts only bounded POSIX ustar and
checks an independently supplied digest plus a closed qualification record
bound to candidate commit, tree and digest. Unknown/failed/skipped/unrun,
cached or incomplete evidence cannot be marked complete as supplied. The
aggregate record requires counters meeting 94% line and 75% branch floors.
All test results, completeness, provenance and cleanup remain assertions made
by the supplier, not verified execution, authentication or deployment consent.

No extraction, archive execution, credential handling, provider/network/DB
access, new signing authority, runtime wiring or other security claim follows.
The module design and manual validation plan specify limits and unrun gates.
