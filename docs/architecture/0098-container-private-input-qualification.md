# ADR0098: manual container and private-input qualification for the EKS rehearsal

- Status: test fixtures implemented; compilation and all new validation UNRUN
- Date: 2026-10-28
- Requirements: SEC-003/007/008/012/019; REL-002/005; OPS-005, partial evidence only

## Decision and scope

Add test-only fixtures and explicit human-run tasks to `eks-sandbox-bundle`.
Do not alter the installed lifecycle protocol, descriptor provenance checks,
authority, SQL, grants, production classes, image recipe or suspended EKS List.
Accepted main at preparation is PR135 merge
`3ad4f01de9c1b753df1ed58e3ddad1da1500a539`.

The default EKS unit gate includes eight new offline harness controls and its
existing ten bundle tests. A separate `containerQualification` Test task contains
12 transport/containment cases and two PostgreSQL16.4 lifecycle cases. It is an
explicit manual gate, not an automatic dependency of `checkAll` or hosted CI.
Missing images/properties fail that gate; no assumption or new skipped case
stands in for execution. Default checks alone do not qualify the container.

The kernel descriptor is inspected through the actual unchanged
`InstalledPrivateInput` facade. The first attached-input positive control must
pass before any private application frame is sent. File, linked/unlinked FIFO
and TTY negatives carry only public sentinel data. The valid offline application
then proves both unchanged parent and worker accept their actual inherited
descriptor and exact EOF. If this plumbing is incompatible, stop: do not add an
intermediate in-container pipe, accept a caller provenance flag, permit a file
input or relax the installed checks.

## Image and process boundary

The local Docker daemon and the operator-selected cached base are trusted
administration. Input is synthetic test material only. This is not secret
custody against the daemon, host administrator, compromised executor or EKS API
audit infrastructure.

To avoid a registry-backed builder resolving a base, the manual assembler first
inspects an already cached exact repository digest, obtains its immutable image
ID, and creates an owned network-disabled container with `--pull=never`. It
copies only checksum-bound compiled archives, runs the existing image recipe's
checksum/extract/ownership/read-only commands, and commits the stopped filesystem
with the fixed non-root entry point and source/archive labels. No Dockerfile
frontend, package install, registry pull/push or source mount is used. This
qualifies only that recorded local image assembly, not reproducible registry
builds or the Dockerfile frontend. The original Dockerfile remains unchanged.

Runtime containers use UID/GID10001, read-only roots, no capabilities or privilege
escalation, private PID/IPC/cgroup namespaces, no published ports, no restart or
healthcheck, disabled Docker logging, a16MiB non-executable temporary filesystem,
64 PID limit,256MiB memory/swap declaration and two CPU declaration. Metadata
checks plus actual UID/capability/write observations are required; metadata alone
is not measured kernel resource enforcement or hostile-host isolation. Probe
fixtures are a separate read-only JAR mount; no fixture ships in the normal CLI
distribution. Application containers have no fixture or host-source mount.

Each Docker CLI client uses a private empty client configuration and an explicit
local Unix socket. Global client settings are not rewritten. Image/container
environment inspection refuses credential, proxy and injected JVM/shell options
before private application input. The PostgreSQL Test JVM uses the recorded
local socket and a cache-only image policy; this is not remote-daemon custody.

The unchanged CLI retains its60s parent,5s cleanup and1s receipt bounds. The
attach observer retains the existing installed-test70s wait; each channel is
capped at2048 bytes. Docker metadata clients are separately bounded, and only a
full ID returned by this fixture's create call with its exact owner label may be
removed. Host-side client reaping adds its own finite cleanup allowances; it is
not counted as the application's5s budget or proof of target absence.

## PostgreSQL and uncertainty

The explicit PG stage reuses accepted test-only identity/bootstrap/grant
fixtures, with a cached-image-only policy scoped to that Test JVM. Each installed
command runs in a fresh container sharing only the owned PostgreSQL container's
network namespace for numeric loopback. No host network, arbitrary JDBC host or
provider endpoint is introduced. Bootstrap/activation is test administration;
the installed runtime performs neither. Pending/review zero-delivery observations,
restricted DELETE refusal, success and known synthetic unknown, repeated-delivery
refusal and read-only reconciliation are checked.

This is not Cloud1's COMMIT-loss injection. Durable rows do not establish a dead
worker's in-memory target count or external exactly-once behavior. It does not
qualify MariaDB, native EKS sidecars/PVCs, CSI, CNI/RBAC/admission, transport audit
redaction, live Okta/GitHub, real keys, remote ingress or deployment. All remain
separate owner-approved gates.

## Manual stages and sources

Run focused compilation/unit tests, cache inventory, offline image assembly,
transport, PostgreSQL and repository stages separately, stopping at first
failure. Each stage requires the preceding exact-source receipt and keeps private
logs, numeric exits, image/archive IDs, fresh XML and cleanup observations.
Existing94% LINE/75% BRANCH floors and all accepted active-engine tests remain.
No test, compile, image/container action, CI retry or deployment was executed by
the implementation writer. See the [runbook](../runbooks/eks-container-private-input.md).

Primary interface references:
[Docker create](https://docs.docker.com/reference/cli/docker/container/create/),
[Docker start/attach](https://docs.docker.com/reference/cli/docker/container/start/),
[Docker runtime constraints](https://docs.docker.com/engine/containers/run/), and
[Testcontainers image policies](https://java.testcontainers.org/features/advanced_options/#image-pull-policy).
