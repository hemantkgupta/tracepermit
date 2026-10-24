# JSON text is not a confirmed text field

Requirements: FR-008/015; SEC-003/019; REL-003/008.

TracePermit confirms a GitHub draft-PR effect only after validating the returned
identity and exact approved content. Syntactically valid JSON is not sufficient:
the `body` field must also have the right type.

Convenience conversion such as Jackson's `asText` can turn JSON boolean `true`
into the string `"true"`, a number into matching text, or a container into an
empty string. Those conversions are useful for display but cannot prove an
exact response-content match. A successful HTTP status does not repair that
missing proof.

The shared body matcher in `GitHubTargetPreflight` accepts only:

- a JSON string exactly equal to the expected body;
- explicit JSON null when the expected body is empty.

Missing fields, booleans, numbers, arrays and objects are not confirmations.
The same rule applies to the POST acknowledgement and the read-only lookup.

An invalid POST acknowledgement remains unknown: the write might already have
committed, so a fresh permit must not cause another POST. An invalid lookup also
stays unknown and removes any earlier cached confirmation. It neither proves
absence nor resets an execution attempt.

The permanent synthetic HTTP matrix has 23 cases. Fourteen failed before the
repair, including scalar text aliases and empty containers on both paths. All
cases pass after repair, with exact-string and empty-string/explicit-null
positive controls. No target operation or public-provider request was needed.

See [ADR0073](../architecture/0073-github-pilot-target-preflight.md) and the
[qualification runbook](../runbooks/github-pilot-target-preflight.md). This
repair changes neither the canonical JSON profile nor permit, target-binding,
retry, deadline, capacity or authority rules. It is not a live pilot or an
exactly-once execution guarantee.
