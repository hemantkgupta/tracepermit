# Synthetic lifecycle success is not live execution

The installed lifecycle command can return `DELIVERY_SUCCEEDED` while also
returning `executionEnabled:false`. Those fields answer different questions.
The lifecycle result says that the closed V7 authority path evaluated, reviewed,
admitted and dispatched one deterministic in-memory synthetic target. The fixed
flag says the distribution exposes no general or live downstream executor.

The durable chain remains important: current authority and owner identity are
checked, an independently authenticated human approves, a short-lived pinned
permit is issued and consumed, and `dispatch_started` commits before the
synthetic call. That proves the composition preserves its internal authorization
and uncertainty rules. It does not prove that a GitHub pull request, AWS resource,
Jira issue or AgentCore action happened.

Likewise, `UNKNOWN` is not permission to try again. Once admission or durable
start may exist, a retry could duplicate an external effect. The command sets
`automaticRetryAllowed:false`; its `reconcile` path only observes durable state
with fresh owner authentication and cannot issue a permit, create an attempt or
invoke a target.

Live execution requires a separately designed credential boundary, exact target
adapter and production reconciliation model. None can be inferred from a
successful synthetic receipt, a clean worker exit or an installed archive.
