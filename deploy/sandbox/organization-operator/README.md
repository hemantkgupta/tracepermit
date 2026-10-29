# Organization operator container qualification

Status: **IMPLEMENTED; ALL VALIDATION UNRUN**.

This local-only qualification packages the accepted source-free organization
operator without changing its protocol, authority, receipt, deadline or target
behavior. It does not authorize deployment or a live provider call.

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

## Manual case matrix

| Mode | Expected selectors/cases | Contract |
| --- | ---: | --- |
| `focused` | 7 / 24 | Existing protocol, identity, receipt, archive, process boundary and installed PostgreSQL operator tests, including the eight-contender accepted case. |
| `inventory` | 1 / 1 | Exact archive SHA256/inventory/extraction, source-free class origins, fixed recipe and entrypoint. |
| `image` | no JUnit selector | Offline local assembly, native cached base, immutable public install, exact source/archive labels and owned cleanup. |
| `transport` | 1 / 14 | Attached pipe positive first; containment; file, linked FIFO, unlinked FIFO and TTY refusal; valid EOF; malformed, trailing and oversized input; held EOF; bounded stdout/stderr; interruption and owned cleanup. |
| `postgresql` | 1 / 3 | Installed-container register/evaluate/independent review/deliver/reconcile for success, denial and durable synthetic unknown; second delivery refused and runtime grants remain restricted. |
| `repository` | repository gates | One complete `pnpm check`, fresh aggregate coverage receipt at 94% LINE / 75% BRANCH, formatting, links/helpers, 13 flow/drift cases and diff checks. |

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
root for all six commands and stop after the first failure:

```bash
export TRACEPERMIT_EXPECTED_HEAD='<exact handoff commit>'
export TRACEPERMIT_EXPECTED_TREE='<exact handoff tree>'
export TRACEPERMIT_PRIVATE_LOG_DIR="/private/tmp/tracepermit-organization-container-$TRACEPERMIT_EXPECTED_HEAD"

bash /Users/hemantkgupta/code-all-air/tracepermit/deploy/sandbox/organization-operator/validate-organization-container.sh focused
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

## Limits

The qualification makes no target invocation-count or exactly-once claim.
Durable delivery rows are not external-target evidence. Docker daemon custody,
EKS attach/exec transport, audit capture, credential custody, real Okta/GitHub
ingress, CNI/RBAC, hostile-node isolation, COMMIT-loss injection and the
separate artifact verifier remain outside this scope. MariaDB is not enabled
for the organization-container gate; the accepted MariaDB11.4 composition is
retained only in the final repository aggregate. MariaDB10.x remains retired.

Raw logs, generated tokens, keys, permits, database credentials and JDBC URLs
remain private under the selected `/private/tmp` receipt root.
