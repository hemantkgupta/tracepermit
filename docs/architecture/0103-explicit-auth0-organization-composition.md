# ADR 0103: Explicit Auth0 organization composition

Status: implemented; validation pending.

Requirements: FR-003/007/008/009/012/015; SEC-002/003/004/006/009/011/012/015;
REL-001/002/004/005; DATA-001; OPS-002/004.

The organization registration and installed synthetic operator can explicitly
select the existing Auth0 RFC 9068 verifier and authority implementation.
`AgentCoreRegistrationIngress.organizationAuth0` and
`DurableAgentCoreRegistration.organizationAuth0` are separate factories. The
existing organization factories remain Okta-only. A signature, exact issuer,
client, mapped tenant, machine subject, REQUESTER role and registration scope
must pass before replay consumption or registration. There is no token-profile
inference, fallback or body-derived authority.

The installed private configuration adds optional `identityProfile`. Omission
retains `okta-custom-as-jwt-rs256-v1`; the only alternative is the explicit
`auth0-rfc9068-rs256-v1`. Both provider blocks follow that selection. Auth0
requires `jwtType: at+jwt`, a namespaced `roleClaim`, no Okta group aliases,
exact requester/reviewer scope allowlists, distinct client IDs, the same pinned
issuer/API/tenant/claim mapping and at most 300 seconds of token lifetime.
Requester enrollment pins `issuer#clientId@clients`. Reviewer identity still
requires the independent exact human roster and its active authority-profile
fingerprint. A signed role cannot create membership or self-review authority.
The sealed `OperationIdentityAuthority` unifies only the existing Okta and
Auth0 implementations; it does not accept arbitrary identity callbacks.

The organization database parser additionally accepts `MARIADB_11_4_13` through
the existing numeric-loopback, exact-server-version installed datasource.
`POSTGRESQL_16_4` remains accepted and its existing tests remain active. MariaDB
10.x is rejected by this organization configuration. SQL, migrations, runtime
grants, authority activation and lease/admission rules do not change. Test
administration of an isolated temporary database is separate from runtime
privilege. The MariaDB test uses the cached 11.4.13 image, real restricted runtime
credentials and independent administrator observations.

Anonymous input frames, EOF-before-effects, bounded supervisor lifetime,
Ed25519 permit-key proof, immutable receipts, no-resend after start or UNKNOWN
and read-only reconciliation remain unchanged. The target is still synthetic
and every receipt remains execution-disabled. No live GitHub executor, hosted
AgentCore tool, remote listener, EKS deployment, provider setup or token request
is added. Air's separate live-executor work needs its own reviewed integration.

New signed-token, configuration, zero-database-effect and installed MariaDB
cases and all repository gates are UNRUN. Earlier live identity receipts do not
qualify this new composition. See the
[manual runbook](../runbooks/auth0-organization-composition.md).
