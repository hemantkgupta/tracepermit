# ADR0082: Numeric-loopback HTTP registration transport

Status: Cloud2 reservation and implementation checkpoint; not qualified yet.

Cloud2 claims sole-writer ownership of `codex/agentcore-loopback-registration-http`
for the new `java/agentcore-registration-http` module, minimal additive build
hooks and scoped documentation, until a final handoff explicitly releases it.
Stacked base: PR111 at `317a1be331b46292699a1e5bbf2e824537a27eb8`.
PR109/111, Mac authority/ADR0077, Cloud1 pilot-operator and Gemini identity
replay/ADR0080 remain untouched and unmerged.

The planned library binds hardcoded numeric127.0.0.1 and accepts only a closed,
versioned HTTP1.1 POST registration route. Trusted operator construction supplies
the real existing ingress. No main/default launcher, public edge, approval,
admission, execution, target credential or provider deployment is added.

NIO channels will enforce finite header/body bytes and absolute monotonic
header/body/overall deadlines. Connections, worker lanes, pending tasks and
completion bookkeeping are bounded. Network timeout closes transport and asks
for interruption; it does not assert database rollback. A dispatched call keeps
its capacity slot until it actually ends. Fresh-token same-key metadata recovery
is separate from dispatch. Bounded close must not wait forever for an
uncooperative trusted port, and must never free capacity for replacement work
while a timed-out registration is still running.

Requirements: FR-002/003/010/012; SEC-001/002/003/004/009/012;
REL-001/002/003/005/009; OPS-002/004; DATA-001; PORT-004.
No whole-requirement, live AgentCore/Okta interoperability, durable replay,
revocation, production-readiness or execution-authority promotion.

Runtime implementation, focused/real-JDBC/guard-removal tests, serialized full
gates and exact-head hosted CI are pending at this ownership checkpoint.
