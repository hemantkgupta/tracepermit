# Organization operator container qualification

Status: **IMPLEMENTED; ALL VALIDATION UNRUN**.

This local-only qualification packages the accepted source-free organization
operator without changing its protocol, authority, receipt, deadline or target
behavior. It does not authorize deployment or a live provider call.

## Archive producer contract

The organization `distTar` retains its typed task, output filename and
`tracepermit-synthetic-organization-operator-0.1.0-SNAPSHOT` root. It resolves
the accepted lifecycle USTAR finalizer through a build-only consumable
configuration. That tool is not a runtime dependency and is absent from the
installed archive and product JARs.

Each forced fresh build first preserves Gradle's raw TAR at
`build/tmp/ustar-producer/fresh-gradle.tar`, then finalizes the distribution in
place before tests begin. The final TAR uses standard USTAR name/prefix fields
while preserving ordered logical paths, payload hashes, types, permissions,
UID/GID, owners/groups and modification times. Unsupported input fails closed;
there is no name truncation, verifier widening or permissive fallback.

The previous PR147 archives, images, digests and private receipts remain
immutable historical evidence. They do not qualify these newly generated
bytes. New archive and image bytes receive new recorded identities.

## Fixed image contract

- Accepted archive: the actual
  `tracepermit-synthetic-organization-operator` Gradle distribution.
- Immutable cached base:
  `eclipse-temurin@sha256:23cc5734ff46e776f51ce66eb9ee08444fd47caabec0a95b7eb6e94be2d3696d`.
- Install root: `/opt/organization`.
- Entrypoint:
  `/opt/organization/bin/tracepermit-synthetic-organization-operator`.
- Runtime identity: `10001:10001`; application root is root-owned,
  non-root-readable/traversable and non-writable.
- Application containers use a read-only root, empty capabilities,
  `no-new-privileges`, private IPC/cgroup namespaces, fixed CPU/memory/PID
  bounds, a 16 MiB no-exec tmpfs, no healthcheck and no Docker log driver.
- Application invocations have no source, configuration, token, key or permit
  mount. The test-only public probe JAR is the only bind mount and is never used
  for an application command.
- Image assembly uses one owned, network-disabled container, `--pull=never`,
  the cached native-architecture base ID and no registry push.

## Manual case sequence

- `cache-create`: force both lifecycle-producer and organization-consumer
  `distTar` tasks with configuration cache enabled, problems set to fail and
  build-cache reuse disabled; require a newly stored entry and executed tasks.
- `cache-reuse`: repeat the exact forced tasks against the same private project
  cache; require configuration-cache reuse and executed, non-cached tasks.
- `focused`: run the accepted lifecycle producer/format selectors (2 selectors,
  33 cases) and the organization format plus existing boundary selectors (8
  selectors, 26 cases). Preserve private immutable raw and final TAR copies.
- `compatibility`: stream-compare every ordered raw/final logical entry and its
  payload/metadata. Compile the byte-unchanged published strict parser privately
  and require final `MATCH`, raw GNU `INVALID` and wrong-digest `MISMATCH`.
- `inventory`: 1 selector / 1 case for the exact finalized archive, source-free
  class origins, fixed recipe and entrypoint.
- `image`: offline local assembly, native cached base, immutable public install,
  exact source/final-archive labels and owned cleanup.
- `transport`: 1 selector / 14 cases covering the attached-pipe positive first,
  containment, file/FIFO/TTY refusal, EOF/framing bounds, interruption and
  owned cleanup.
- `postgresql`: 1 selector / 3 cases covering installed-container success,
  denial, durable synthetic unknown, no resend and restricted runtime grants.
- `repository`: literal `pnpm check`, fresh aggregate coverage at 94% LINE / 75%
  BRANCH, formatting, links/helpers, 13 flow/drift cases and diff checks.

Every installed command uses a fresh constrained container process. The
PostgreSQL16.4 fixture generates requester/reviewer tokens and permit keys at
runtime, binds its published administrative port to numeric loopback, and gives
the application containers only the restricted runtime roles. Registration is
execution-disabled. The exact registered authority is activated only after the
installed registration receipt is observed.

The PostgreSQL application topology deliberately joins the disposable
database container's network namespace and connects to `127.0.0.1:5432`. This
is local synthetic qualification, not an EKS networking claim.

## Human-run sequence

Use the exact candidate commit and tree from the handoff. Reuse the same private
root for all nine commands and stop after the first failure:

```bash
export TRACEPERMIT_EXPECTED_HEAD='<exact handoff commit>'
export TRACEPERMIT_EXPECTED_TREE='<exact handoff tree>'
export TRACEPERMIT_PRIVATE_LOG_DIR="/private/tmp/tracepermit-organization-archive-$TRACEPERMIT_EXPECTED_HEAD"

bash /Users/hemantkgupta/code-all-air/tracepermit/deploy/sandbox/organization-operator/validate-organization-container.sh cache-create
bash /Users/hemantkgupta/code-all-air/tracepermit/deploy/sandbox/organization-operator/validate-organization-container.sh cache-reuse
bash /Users/hemantkgupta/code-all-air/tracepermit/deploy/sandbox/organization-operator/validate-organization-container.sh focused
bash /Users/hemantkgupta/code-all-air/tracepermit/deploy/sandbox/organization-operator/validate-organization-container.sh compatibility
bash /Users/hemantkgupta/code-all-air/tracepermit/deploy/sandbox/organization-operator/validate-organization-container.sh inventory
bash /Users/hemantkgupta/code-all-air/tracepermit/deploy/sandbox/organization-operator/validate-organization-container.sh image
bash /Users/hemantkgupta/code-all-air/tracepermit/deploy/sandbox/organization-operator/validate-organization-container.sh transport
bash /Users/hemantkgupta/code-all-air/tracepermit/deploy/sandbox/organization-operator/validate-organization-container.sh postgresql
bash /Users/hemantkgupta/code-all-air/tracepermit/deploy/sandbox/organization-operator/validate-organization-container.sh repository
```

The runner refuses a dirty or different checkout, wrong branch/commit/tree,
changed source manifest, missing prepared dependency/cache, wrong Java/Gradle/
Node/pnpm runtime, changed database image ID, absent prior-stage receipt or an
existing mode receipt. It captures private stdout, stderr and numeric exits for
each stage. It does not install, download, pull, push, deploy or contact a live
provider.

Focused validation is the sole authoritative fresh organization archive build.
Compatibility and every container mode require the raw and final build outputs
to remain byte-identical to the focused private copies. Image provenance binds
the runtime label to that exact final SHA256; no prior PR147 image or archive is
reused as new-format evidence. The repository stage may rebuild after all
archive/image evidence is preserved and does not retroactively change it.

## Limits

The qualification makes no target invocation-count or exactly-once claim.
Durable delivery rows are not external-target evidence. Docker daemon custody,
EKS attach/exec transport, audit capture, credential custody, real Okta/GitHub
ingress, CNI/RBAC, hostile-node isolation, COMMIT-loss injection and the
separate artifact verifier remain outside this scope. MariaDB is not enabled
for the organization-container gate; the accepted MariaDB11.4 composition is
retained only in the final repository aggregate. MariaDB10.x remains retired.

The published strict parser is only a private compatibility comparator. These
stages do not establish independent provenance, EKS custody, deployment
readiness, target invocation counts or exactly-once execution.

Raw logs, generated tokens, keys, permits, database credentials and JDBC URLs
remain private under the selected `/private/tmp` receipt root.
