# Auth0 organization composition qualification

Status: implemented; validation pending. No live provider or deployment step is
part of this runbook. Preserve the existing Okta integration.

The installed organization configuration can set `identityProfile` to
`auth0-rfc9068-rs256-v1`. In both `requesterProvider` and `reviewerProvider`, use
`jwtType: at+jwt`, the same exact namespaced `roleClaim` and `tenantClaim`, exact
issuer including trailing slash, API audience and tenant mapping. Replace the
Okta-only `groupClaim`/`roleGroup` fields; do not retain or alias them. Pin the
RSA public key and key ID independently. The requester provider supports exactly
operations.register, operations.evaluate and operations.execute. The reviewer
provider supports only operations.decide. Each block pins its own distinct client
ID and has a maximum token lifetime of 300 seconds.

Requester enrollment pins `issuer#requesterClientId@clients` to the configured
internal owner and workspace. The private reviewer roster pins the exact human
subject, reviewer client, tenant, workspace and group; its canonical fingerprint
must match the active authority profile. Configuration and signed role claims
alone are not current database authority or an approval. No bearer, secret or
key belongs in this document, command arguments, environment or repository.

For this demo, replay and operation stores may select `MARIADB_11_4_13`, numeric
127.0.0.1 and distinct preinitialized catalogs. The existing datasource checks
the running server version. Runtime never installs schemas, grants access or
activates authority. Existing PostgreSQL support stays active; MariaDB 10.x is
not an accepted organization engine. These changes do not qualify RDS or a
cluster database.

Run each human stage separately with the prepared private runner. It records
exact source hashes, commands, numeric exits, fresh selected XML, archive
identities where applicable and private logs outside Git. It stops on failure:

1. `focused`: signed Auth0 and Okta identity, ingress and organization offline
   configuration/framing/boundary cases. No live token exchange.
2. `mariadb`: exact cached MariaDB 11.4.13, extracted candidate archive outside
   the checkout, independent installed processes, positive lifecycle, denial,
   UNKNOWN/no-resend, contention and restricted-grant controls. No live target.
3. `repo`: unchanged literal `pnpm check`, `pnpm format:check`, documentation,
   flow and diff gates, plus unchanged 94% line and 75% branch coverage. This
   retains all active repository tests, including PostgreSQL tests. Fresh selected
   gates and cached/up-to-date repository tasks must be reported separately.

Review every receipt before starting the next stage. A missing prepared cache
or image is a blocker, not permission to download or silently substitute one.
Historical failed receipts remain intact. No pass is claimed until a returned
receipt is checked against the exact candidate.

This proves no end-to-end hosted demo by itself. Remaining gates include
reviewed integration of the live GitHub executor, Auth0 selection at that exact
boundary, actual private input/credential custody on EKS, authenticated ingress
transport, deployment acceptance and a separately authorized one-shot real draft
PR. Identity-only requester/reviewer success is not operation approval, model
hosting is not tool authority, and database state cannot prove a killed
worker's in-memory target count or exactly-once effects.
