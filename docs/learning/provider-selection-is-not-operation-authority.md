# Provider selection is not operation authority

Selecting an Auth0 grammar tells a verifier which signed claims to require. It
does not let a caller become an owner or reviewer. TracePermit still joins that
verified identity to an exact operator-owned enrollment, independent reviewer
roster, pinned policy/target/profile and current durable authority state.

The requester and reviewer are distinct clients and subjects with distinct
scopes. A reviewer token cannot execute; a requester token cannot approve.
Registration creates a durable operation without permission to deliver it.
An administrator's offline roster is not a body field, and neither a signed
role nor a configuration file can bypass the durable approval and permit gates.

The installed Auth0 organization composition retains these checks and still
uses a synthetic target. Its new tests are unrun until the human executes the
manual qualification stages. Even a successful component run does not qualify
AgentCore-to-EKS connectivity, live GitHub execution or a production deployment.
