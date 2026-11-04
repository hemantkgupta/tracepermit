# ADR 0102: Separate Auth0 reviewer PKCE foundation

Status: implemented; validation pending.

Requirements: FR-003/007/012; SEC-002/004/009/011/012/015; REL-001/004.

Auth0 RFC 9068 access tokens use a distinct `auth0-rfc9068-rs256-v1`
profile. The existing Okta JWT/cid/scp/group grammar and standard profile remain
separate. Shared cryptographic checks still require RS256, exact issuer,
known client, bounded API audience, jti, mapped tenant and token time bounds.
Auth0 adds a single namespaced signed REQUESTER or REVIEWER role, a five-minute
maximum, service subject/client binding for requesters, and a human subject
shape for reviewers. The only permitted additional audience is this issuer's
userinfo endpoint. Default Auth0 azp/JWT, Okta aliases, permissions dialect and
sender-constraint claims are refused by this explicit pilot profile.

`Auth0OperationAuthority` reuses exact enrollment, independent roster,
fingerprint, fresh verification and expiry checks through the existing core.
The public Okta constructor remains fixed to the Okta profile. Roster and
requester enrollment data types retain their existing names and canonical
format; their names do not select a provider or authenticate data. Auth0
failures have separate fixed diagnostics.

The separate `auth0-reviewer-cli` is an identity qualification helper, not an
approval tool. It consumes one private anonymous-pipe configuration frame and
EOF before effects. `check-config` is offline. Explicit future `login` uses
code/S256 PKCE, state and required callback issuer, only operations.decide,
no openid/ID token, no secret, refresh, retries or credential files. A verified
access token's exact subject/client/tenant/role/scope must match operator trust.
Only a fixed outcome and credential fingerprint leave the process. The bearer
is discarded, not exported to an executor. Existing browser callback parsing
is reused through a narrow facade, with absolute header deadlines replacing
per-byte timeout renewal without extending any existing timeout.

The fixed callback is `http://127.0.0.1:41839/reviewer/callback`. It is a proposed
registration until this candidate passes manual validation. No Auth0 settings
or Actions are deployed by the code. No database, ingress, installed organization
configuration, target or AgentCore tool is wired by this change.

Manual qualification, actual provider dialect/issuer callback behavior,
PKCE negatives and integration with the operation/executor remain pending.
See the [runbook](../runbooks/auth0-reviewer-pkce.md).
