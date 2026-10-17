# Simulator approval distribution runbook

This runbook guides operators using the packaged, source-free TracePermit
simulator approval application (`tracepermit-simulator-approval`).
This archive bundles the authenticated Java backend and built React inbox
without requiring Node.js, pnpm, Gradle, or a source checkout on the host.

> [!IMPORTANT]
> This distribution is a **simulator-alpha qualification foundation**, not a
> production deployment, cloud target gateway, or external IdP qualification.
> It binds exclusively to numeric IPv4 loopback (`127.0.0.1`).

## Host prerequisites

- **Operating system:** macOS (ARM64/x86_64) or Linux (x86_64/ARM64).
- **Runtime:** Java 21 (OpenJDK / Eclipse Temurin recommended).
- **Database:** Dedicated local PostgreSQL instance listening on `127.0.0.1:5432`.
- **Identity Provider:** An OIDC-compliant provider issuing RS256 `at+jwt` access tokens.
- **Tools not required:** Node.js, pnpm, and Gradle are **not** required to run or offline-validate this bundle.

The host-native launcher is architecture-independent; automated qualification runs
on the available macOS host and Linux CI, not on every listed CPU/OS combination.

## Archive verification and extraction

Verify archive integrity before extracting:

```bash
shasum -a 256 -c tracepermit-simulator-approval-0.1.0.tar.gz.sha256
```

> [!NOTE]
> SHA-256 checksums verify file transmission and storage integrity. They do not
> represent an independent cryptographic code signature or third-party provenance attestation.

Extract the archive into your chosen installation directory:

```bash
tar -xzf tracepermit-simulator-approval-0.1.0.tar.gz
cd tracepermit-simulator-approval-0.1.0
shasum -a 256 -c SHA256SUMS
```

Inspect `manifest.json`: schema `1.1` records `sourceCommit`,
`sourceCommitTimestamp` (the commit time, not the packaging time), `sourceTree: "clean"`,
and an exact runtime JAR inventory. Compare the commit to the release you intended
to install. These fields identify a trusted local build; they are not signed
provenance. Do not use an archive left over from a failed packaging command.

The extracted archive layout:

```text
tracepermit-simulator-approval-0.1.0/
├── bin/
│   └── tracepermit-simulator-approval
├── lib/
│   └── *.jar
├── console/
│   ├── index.html
│   └── assets/
├── examples/
│   └── simulator-approvals.example.json
├── docs/
│   ├── setup-runbook.md
│   ├── LICENSE
│   └── NOTICE
├── manifest.json
└── SHA256SUMS
```

## Operator setup

Prepare a private directory outside source control with owner-only access:

```bash
umask 077
mkdir -p "$HOME/.config/tracepermit-approvals"
chmod 700 "$HOME/.config/tracepermit-approvals"
cd "$HOME/.config/tracepermit-approvals"
```

### 1. Generate simulator permit keys

For a first installation only, generate a persistent Ed25519 keypair for execution
permit signing. The guard prevents accidental overwrite of existing keys:

```bash
(
  set -euo pipefail
  if [ -e permit.pk8 ] || [ -e permit.spki ]; then
    echo 'Existing permit key files found; reuse them, do not overwrite.' >&2
    exit 2
  fi
  openssl genpkey -algorithm ED25519 -outform DER -out permit.pk8
  openssl pkey -inform DER -in permit.pk8 -pubout -outform DER -out permit.spki
  chmod 600 permit.pk8 permit.spki
)
```

> [!WARNING]
> Do not overwrite existing keys across restarts; retained approvals and consumption
> records depend on key stability.

### 2. Configure database credentials

Using a private local editor (not synchronized notes), create `database.secret`
with your dedicated PostgreSQL role's password (at least 16 non-control characters)
as its only line. Do not put the value in a shell command, command argument or chat:

```bash
chmod 600 database.secret
```

### 3. Configure browser client secret (optional)

If using the browser approval inbox, similarly create `browser.secret` with the
OIDC client secret in your private editor. Do not overwrite it on subsequent starts:

```bash
chmod 600 browser.secret
```

### 4. Create operator configuration

Copy and customize the example configuration:

```bash
cp /path/to/tracepermit-simulator-approval-0.1.0/examples/simulator-approvals.example.json operator.json
chmod 600 operator.json
```

Edit `operator.json` with your private editor:

- Set `postgres.port`, `postgres.database`, and `postgres.user`.
- Set `oidc.issuer`, `oidc.audience`, `oidc.jwksUri`, `oidc.clientId`, and tenant mapping.
- Set `memberships` with at least one human `REQUESTER` and a distinct human `REVIEWER`.
- Set `permit.keyId` (e.g., `"local-simulator-1"`).
- If using `browser`, point `consoleDirectory` to the extracted `console/` path
  (e.g., `/path/to/tracepermit-simulator-approval-0.1.0/console`).

## Offline configuration check (`--check`)

Export the path to your private configuration file:

```bash
export TRACEPERMIT_APPROVAL_CONFIG="$HOME/.config/tracepermit-approvals/operator.json"
```

Run the offline validation:

```bash
/path/to/tracepermit-simulator-approval-0.1.0/bin/tracepermit-simulator-approval --check
```

On success, the launcher outputs:

```text
APPROVAL_CONFIGURATION_VALID: offline only; no database, IdP or listener checked.
```

and exits with status `0`.

### Offline check guarantees

- **Zero network traffic:** No network sockets are opened, no DNS resolution is made, and no IdP endpoints are contacted.
- **Zero database interaction:** No connection is established and no Flyway migrations are run.
- **Full local validation:** JSON schema, file permissions (0600/0400 owner-only), Ed25519 keypair mathematics, membership roles, and console assets are verified.
- **Redacted failure:** Any validation error exits with status `2` and outputs `APPROVAL_STARTUP_FAILED: ... details redacted.` without exposing file paths, database passwords, or key material.

## First installation only (`--init-db`)

With the private config pointing to a dedicated empty local database, initialize:

```bash
/path/to/tracepermit-simulator-approval-0.1.0/bin/tracepermit-simulator-approval --init-db
```

Success emits `APPROVAL_DATABASE_INITIALIZED` and exits without a listener,
IdP call or target effect. It installs packaged V1–V9 with an initialization
role. Existing/partial state is refused; cooperating concurrent initializers
fail fast. Never use this command after a restore or for an upgrade. Migration
failure can leave partial state; no automatic repair, clean or retry is provided.

Normal run may use a separate private config with a non-owner runtime role:
application DML and metadata/history reads, not migration rights. Role/grant
provisioning remains operator-owned. Keep initialization credentials out of the
runtime config. Runtime row locks also require SELECT and UPDATE on
`public.tp_recovery_quarantine`; its trigger still rejects non-owner quarantine
transitions. Exact checks and limits are in
[ADR 0045](../architecture/0045-explicit-database-initialization.md).

## Running the application (`--run`)

Start the approval service:

```bash
/path/to/tracepermit-simulator-approval-0.1.0/bin/tracepermit-simulator-approval --run
```

During startup:

1. Validates operator configuration and credentials.
2. Validates existing packaged PostgreSQL V1–V9 migration history/checksums,
   guard, required tables and enabled invariant triggers. Empty/partial,
   incompatible or pending-upgrade state fails without migration or repair.
3. Refuses an already quarantined recovery row, then binds the HTTP listener
   strictly to `http://127.0.0.1:<port>`.
4. Outputs: `TracePermit simulator approval API listening on http://127.0.0.1:<port>`.

The default launcher still uses its fixed static simulator policy. Installing
schema V9 does not select an active policy or enable generation-bound approvals;
that composition is an opt-in Java factory described in
[ADR 0042](../architecture/0042-durable-policy-generation-binding.md).

Schema validation does not prove successful recovery: a complete old snapshot
can revive consumed approvals. Keep agents/effectful services disconnected after
restore or restore failure; no external recovery fence or safe-resume command
is implemented. There is no in-place schema-upgrade command; never reset or
rewrite a populated database to make this version start.

Owner-only `--recovery-inspect` prints a read-only, consistent aggregate snapshot
without identifiers, mutation or target lookup. It is not a safe-resume verdict;
see [recovery inspection](operator-recovery-inspection.md).

`--recovery-status` and owner-only `--quarantine-db` provide explicit one-way
containment without IdP/listener/target startup. They block new database authority,
not already admitted effects or internal permit minting. They do not detect a
restored old ready row and have no resume command. See the
[quarantine runbook](operator-recovery-quarantine.md). Existing V8 installations
are refused without implicit upgrade; keep them offline pending a separate
upgrade procedure, rather than initializing over retained state.

### Clean process termination

To stop the service, send `SIGTERM` or `SIGINT`:

```bash
kill -TERM <pid>
# or press Ctrl+C if running in foreground
```

The process executes registered JVM shutdown hooks, cleanly closes the HTTP server,
and releases the port immediately.

## Security boundaries and limits

1. **Loopback only:** The service binds only to `127.0.0.1`. Never expose this port
   through a reverse proxy, public interface, tunnel, or container port-forward.
2. **File permissions:** Configuration, secret, and key files must be owned by the
   current user with mode `0600` or `0400` on a POSIX filesystem. This protects against
   other unprivileged users, but not against same-UID compromise or root access.
3. **Synthetic targets:** Simulated actions dispatch only to in-memory stateful simulators.
   No AWS, Jira, or cloud provider SDK or credential is included or contacted.
4. **Independent IdP:** Operator configuration enrolls an external OIDC provider.
   Offline check does not certify that the provider will issue compatible tokens.
5. **Fixed launcher, shared JARs:** The launcher selects only `SimulatorApprovalMain`.
   Shared runtime JARs also contain development/hosted-client entry points; they
   have not been stripped. Direct Java invocation by a local host user is not
   constrained by the launcher. Test-only account pickers and IdP classes are absent.
6. **Redaction scope:** Launcher validation and Java bootstrap handlers omit private
   paths and secret values. Damaged archives or JVM/OS errors can produce diagnostics
   outside those handlers; preserve logs privately and verify integrity first.

## Building and qualifying from source (maintainers only)

Run from a clean, committed checkout with Node.js 22+, pnpm 11.25.0 and Java 21:

```bash
pnpm install --frozen-lockfile
pnpm build:distribution
pnpm test:distribution
```

Packaging rejects tracked/non-ignored untracked changes and missing Git metadata.
There is no dirty-release override. Generated build output stays ignored; run tests
in an isolated checkout with no concurrent source edits. Commit-only changes are
tracked inputs, so Gradle cannot reuse a manifest naming the previous commit.
Qualification always requests a build before inspecting an existing archive and
compares the manifest to the current commit. Checksums verify exact file contents,
not an independent release authority. Rebuilding identical inputs is tested;
cross-host/JDK bit-for-bit reproducibility and signed release publication are not.

## References

- [ADR 0035: Explicit operator bootstrap](../architecture/0035-local-approval-operator-bootstrap.md)
- [ADR 0036: Local browser sign-in and inbox](../architecture/0036-local-browser-approval-boundary.md)
- [ADR 0040: Source-free simulator approval distribution](../architecture/0040-source-free-simulator-approval-distribution.md)
- [Authenticated approval API runbook](authenticated-approval-api-foundation.md)
