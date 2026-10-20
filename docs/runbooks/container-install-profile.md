# Container install profile foundation

This runbook installs the source-free Java/PostgreSQL/React simulator approval
composition on a **Linux Docker Engine** host. It preserves numeric-loopback
binding and provides repeatable configuration, initialization, health and
readiness checks.

> [!IMPORTANT]
> This is a TP-MVP-05 foundation, not a production or public deployment. Do not
> add port publishing, a reverse proxy, a tunnel, a real target credential, or a
> development issuer. Docker Desktop host networking, external IdP compatibility,
> TLS, sender constraints, shared abuse controls and real AWS/Jira targets are not
> qualified here.

## Trust boundary

- The application and PostgreSQL share the Linux host network and both bind only
  `127.0.0.1`. The Compose file has no `ports` mapping.
- The application runs as UID/GID `10001`, with a read-only root filesystem, all
  capabilities dropped and `no-new-privileges`.
- A one-shot preparation service copies only `operator.json`, `database.secret`,
  `permit.pk8`, `permit.spki` and optional `browser.secret` into an owned volume.
  It refuses changed replacements. The runtime mounts that volume read-only.
- PostgreSQL gets its password from a file-backed Compose secret. No reusable
  secret belongs in an environment variable, image, repository, command line or
  chat transcript.
- The only target is the deterministic in-memory simulator.

## Prerequisites

- Linux Docker Engine 28 or a reviewed compatible release with Compose v2.
- A clean, committed checkout plus Node.js 22, pnpm 11.25.0 and Java 21 **only
  when building the image locally**. A supplied reviewed image does not require
  those build tools.
- A dedicated pair of unused loopback ports, a dedicated database name/role and
  an operator-selected compatible HTTPS OIDC provider.

The profile deliberately does not fall back to bridge networking. Stop if host
networking is unavailable.

## Build and inspect the image

From a clean checkout:

```bash
pnpm build:container-profile
```

The command first builds the existing source-free distribution, then uses a
minimal temporary Docker context and prints the exact local image tag. Review:

```bash
docker image inspect tracepermit/simulator-approval:0.1.0-<commit>
```

For registry distribution, publish through a separately reviewed release
process and configure the immutable registry digest, not a mutable convenience
tag. OCI revision/archive-hash labels and pinned base digests are inventory, not
independent signed provenance.

## Prepare private operator input

Create a private directory outside the checkout and synchronized notes. Copy
`deploy/container/operator.example.json` to `operator.json`, then edit all
placeholders. The JSON port/database/user values must exactly match the Compose
environment file. The IdP URLs must be operator-owned HTTPS endpoints; the
`.invalid` values are safe examples and cannot provide login.

Generate a new Ed25519 key pair and write strong database/browser secrets using
a private editor or secret tool. Follow the key commands in the
[source-free distribution runbook](simulator-approval-distribution.md#1-generate-simulator-permit-keys).
The final directory must contain:

```text
operator.json
database.secret
permit.pk8
permit.spki
browser.secret  # only when the browser object is configured
```

Use owner-only directory and file permissions. Do not reuse an example/default
password; the Java validator rejects known placeholder values but cannot judge
whether an arbitrary operator secret is unique.

Copy `deploy/container/container.env.example` to another private file and set:

- the reviewed image tag/digest;
- the absolute private input directory;
- exact application/PostgreSQL loopback ports; and
- the dedicated PostgreSQL database and role.

The environment file contains no password or private key.

## Explicit first installation

Set a short alias for the exact Compose invocation used below:

```bash
docker compose --env-file /private/path/container.env \
  -f deploy/container/compose.yaml --profile setup run --rm prepare
docker compose --env-file /private/path/container.env \
  -f deploy/container/compose.yaml --profile setup run --rm check
```

Expected markers are `CONTAINER_PRIVATE_CONFIGURATION_PREPARED` followed by
`APPROVAL_CONFIGURATION_VALID`. `check` is fully offline. A changed retained
secret or key is rejected; investigate rather than deleting the volume or
overwriting durable identity.

Start the database and initialize only the fresh, dedicated empty database:

```bash
docker compose --env-file /private/path/container.env \
  -f deploy/container/compose.yaml up -d --wait postgres
docker compose --env-file /private/path/container.env \
  -f deploy/container/compose.yaml --profile setup run --rm init
```

`APPROVAL_DATABASE_INITIALIZED` is required. Never repeat initialization against
retained/restored state and never delete a database merely to make setup pass.
Use the existing stopped-service upgrade and recovery procedures for their exact
supported cases.

Start the application:

```bash
docker compose --env-file /private/path/container.env \
  -f deploy/container/compose.yaml up -d --wait tracepermit
```

## Health and readiness

From the same host, use the configured application port:

```bash
curl --fail --silent http://127.0.0.1:<port>/health
curl --fail --silent http://127.0.0.1:<port>/ready
```

`health` returns schema `1.0` with `status: live`. `ready` returns `ready` only
while the exact database schema is readable and recovery mode is `ready`; it
returns HTTP 503 with `not_ready` otherwise. These endpoints accept no bearer,
Cookie, Origin, query or body and disclose no dependency details.

Readiness does **not** prove external IdP compatibility, token issuance, TLS,
reviewer availability, target availability or production safety. Verify the
chosen IdP/browser composition separately before relying on sign-in.

## Stop and retain state

```bash
docker compose --env-file /private/path/container.env \
  -f deploy/container/compose.yaml down
```

This stops owned services while retaining named volumes. Do not add `--volumes`
unless intentionally destroying this installation under an approved data
lifecycle. The automated qualification uses a random Compose project and removes
only its own disposable volumes.

## Maintainer qualification

From a clean commit with Docker available:

```bash
pnpm build:container-profile
pnpm test:container-profile
pnpm check
pnpm format:check
```

The container test generates all signing keys and synthetic secrets at runtime,
uses `.invalid` IdP endpoints, invokes no approval mutation/target, checks
loopback-only sockets and removes only its owned Compose project.

## Unsupported changes

Stop and obtain a new architecture/security review before any of these:

- adding `ports`, bridge ingress, wildcard binding, proxy-header trust or a tunnel;
- enabling a development issuer or embedding credentials/default keys;
- adding a TLS/reverse-proxy edge or claiming sender-constrained client support;
- sharing the local database/config volumes across hosts or replicas; or
- adding a real AWS, Jira or other target credential/SDK.

See [ADR 0062](../architecture/0062-loopback-container-install-profile.md) for
the decision, evidence and remaining TP-MVP-05 limits.
