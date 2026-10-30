# Cloud development environment qualification

This is development infrastructure for synthetic tests, not a TracePermit customer
deployment. Baseline: `f23dc22f056058d7977715e0878c284728d10d17` (PR103).
The setup lane is `codex/cloud-java21-docker-qualification`; its public packet is
`docs/planning/packets/wave1-cloud-java21-docker-setup.md` at
`f239d89a49d0f5b150684175c9a2128a42445ed1` on PR104. No runtime, CI, application,
database migration, lockfile or existing test assertion changes are needed here.

## Authority and platform prerequisites

Read the current official [Cloud environments guide](https://learn.chatgpt.com/docs/environments/cloud-environments)
and the active task's permissions/network policy before installing packages or
executing containers. The older singular `cloud-environment` page describes the
legacy product. Do not assume the Mac's tools, credentials or caches exist here.
No additional cloud-runtime skill was advertised in this session; the supplied
runtime policy is authoritative. A domain allowed by one layer does not override
another layer's restrictions. Preserve the supplied proxy and CA configuration.

Use a separate task or environment-preparation workspace. Before any checkout,
exclude `agentcore-work/**` and all private customer planning files. This lane used
a fresh `--no-checkout --filter=blob:none` clone and non-cone sparse patterns
`/*`, `!/agentcore-work/`, `!/docs/planning/` before checkout. Inspect only the
named public packet blobs from their frozen commit. Do not add private inputs to
the prepared filesystem. Excluding all planning files also omits public documents;
whole-tree link checks can therefore report missing baseline planning targets.

Inventory tool versions, free disk/memory and whether the proxy/CA variables are
set; never print their values or dump configuration, credentials or environment.
Distinguish these Docker states:

| Observation                                | Meaning / prerequisite                                                     |
| ------------------------------------------ | -------------------------------------------------------------------------- |
| Client absent                              | Install/provision an approved client; daemon capability is still unproven. |
| Client present, version request fails      | Operator must provide a responsive, authorized local daemon.               |
| Version request times out                  | Stop this gate; inspect platform support privately.                        |
| Platform does not support a daemon/nesting | Use a supported dedicated development VM/environment.                      |
| Exact image absent locally                 | Explicitly pull it during setup after package/registry access is approved. |

Never use privileged nesting workarounds, mount/expose a host Docker socket, open
a TCP daemon, restart a host daemon, disable TLS, bypass a proxy or attach the Mac
or customer network. This session's missing client/socket does **not** prove that
the platform categorically prohibits Docker. There is no container-start script
here: daemon startup is an operator/platform service, and the existing Java
Testcontainers suite owns its disposable databases.

## Explicit setup recipe

The operator prepares tools in this dedicated development workspace using
platform-supported package sources. Preserve an already compliant installation.
These are requirements, not claims that installation succeeded in this session:

1. Select/provision Node.js 22 or newer using the platform's supported runtime
   mechanism. Record the actual version; do not replace a compliant Node24.
2. Provision a full Java21 JDK, including `javac`. For an approved Ubuntu24.04
   package environment, `apt-get update` followed by
   `apt-get install --no-install-recommends openjdk-21-jdk-headless` is the supported
   distribution-package route. These commands require operator-authorized package
   access and privileges in the dedicated setup environment. They are **not** run
   by preflight or dependency setup. Record the installed package version. For an
   archive route, pin the exact vendor release and SHA256 from its official
   release, verify the archive before extraction and capture that manifest. Do not
   substitute an unverified archive or invent a digest when access is blocked.
3. If the active pnpm is not exactly 11.25.0, explicitly install the pinned public
   package into a task-owned prefix, for example
   `npm install --prefix "$PWD/.cache/cloud/pnpm-cli" --ignore-scripts --no-audit --no-fund pnpm@11.25.0`.
   Set `PATH` to that prefix's `node_modules/.bin` in the prepared environment.
   Preserve an existing compliant pnpm. Do not rewrite global Corepack shims or
   package locks. Preflight disables Corepack network fetching and pnpm version
   management so a version probe cannot silently install its own requirement.
4. Set `TRACEPERMIT_JAVA_HOME` only if needed to select the operator-provisioned
   JDK. Keep a compliant explicit selection. The existing
   [Java selector](../../scripts/configure-java-environment.sh) remains the sole
   discovery implementation. It configures Gradle's proxy/trust store and caches;
   do not add a different Java/backend launcher.
5. Provision a supported local Docker client/daemon through the platform. Wait
   for `docker version --format '{{.Server.Version}}'` to respond. If that cannot
   be provided safely, stop the database execution gate. Never treat a skip as
   qualification.
6. With approved registry access, explicitly pull the existing suite's exact
   images: `docker pull postgres:16.4-alpine`, `docker pull mariadb:11.4.13`,
   `docker pull mariadb:10.6.25`. Testcontainers may also require its pinned helper
   images; inspect its dependency version and approve those registry requests.
   Record resolved image IDs/digests as evidence; tags alone are mutable. Do not
   disable Ryuk or Testcontainers checks to make the gate pass.
7. Run the explicit dependency setup below. It installs with the frozen lockfile,
   warms the checked-in checksum-pinned Gradle8.14.3 wrapper and runs the existing
   Java environment check. It starts no services, pulls no images and changes no
   shared environment settings. It fails immediately on a missing toolchain.

```bash
bash scripts/cloud/setup.sh --install
node scripts/cloud/preflight.mjs
node scripts/cloud/preflight.mjs
node --test scripts/cloud/preflight.test.mjs
```

Setup bounds version probes to 15 seconds, dependency installation to 300 seconds,
wrapper preparation to 180 seconds and the Java environment test to 30 seconds,
with a two-second forced-termination grace for its own commands.
It reports fixed categories and never retries automatically. It defaults to
repository-local `.cache/cloud/{pnpm-store,gradle,java-preferences}` and preserves
explicit cache choices. Supply task-owned paths only; never point at another
lane's/shared cache. Two setup fixture passes prove repeatability of the recipe,
not a real installed/published environment. A real dependency installation and
fresh-task qualification remain required.

The package access inventory is not an allowlist grant. Setup needs approved npm
package/tarball hosts, the operator's JDK package source, Gradle distribution and
redirect hosts, Maven Central and Docker registry/auth/blob hosts. Record the
specific requested hosts privately for owner review rather than allowing all
internet access. Keep the configured CA and HTTPS proxy in use. No API key,
customer database URL, cloud credential or GitHub App secret is necessary.

## Non-mutating preflight and fixed diagnostics

`node scripts/cloud/preflight.mjs` exits zero only when **every** requirement is
present. Node is needed to launch this tool; if Node itself is absent, the shell
cannot launch it and the environment is unqualified. The program checks Node22+,
pnpm11.25.0, selected Java21 compiler/runtime, the checked-in wrapper files and
pinned distribution properties, Docker client/daemon, and all three local images.
Wrapper inspection does not execute Gradle, download its distribution or prove
that Java dependencies have resolved.

Each command is bounded to five seconds and 64KiB captured output. A timeout
terminates only the probe's own child process group; it never stops an existing
daemon/container. The Java check sources the existing selector in a child shell
with its `mkdir` operation suppressed, then verifies both selected compiler and
runtime. Environment changes stay in the child. No directories are created by
preflight. Docker commands are limited to client version, server version and
local image inspection. No installation, pull, run, startup, cleanup or skip flag
is hidden in preflight.

| Check ID         | Non-success codes                                              |
| ---------------- | -------------------------------------------------------------- |
| `NODE`, `PNPM`   | `MISSING`, `VERSION_REQUIRED`, `TIMEOUT`, `OUTPUT_LIMIT`       |
| `JAVA`           | `JDK21_REQUIRED`, `TIMEOUT`, `OUTPUT_LIMIT`                    |
| `GRADLE_WRAPPER` | `INVALID`                                                      |
| `DOCKER_CLIENT`  | `MISSING`, `UNAVAILABLE`, `TIMEOUT`, `OUTPUT_LIMIT`            |
| `DOCKER_DAEMON`  | `CLIENT_REQUIRED`, `UNRESPONSIVE`, `TIMEOUT`, `OUTPUT_LIMIT`   |
| Exact image tag  | `DAEMON_REQUIRED`, `IMAGE_REQUIRED`, `TIMEOUT`, `OUTPUT_LIMIT` |

`JAVA/JDK21_REQUIRED` includes a missing compiler, rejected Java17 selection or
compiler/runtime mismatch. Dependent Docker checks retain a failure category
rather than silently disappearing. Successful version fields accept only numeric
version syntax. Raw command output, exception text, paths, daemon details, proxy
values and credentials are not emitted. Resource numbers are observations, not
reservations or arbitrary readiness thresholds. Preflight success is a prerequisite,
not proof of database behavior or environment reproducibility.

## Execute the existing qualification gates

After setup and two successful preflights, use the existing runners unchanged:

```bash
export TRACEPERMIT_CACHE_DIR="$PWD/.cache/cloud"
export TRACEPERMIT_GRADLE_USER_HOME="$TRACEPERMIT_CACHE_DIR/gradle"
pnpm java:env-check
bash scripts/run-java-operation-tests.sh --no-daemon --max-workers=2 --rerun-tasks
pnpm check
pnpm format:check
pnpm docs:links
pnpm docs:flows:check
git diff --check
```

The operation suite forces container startup (there is no Docker-availability
skip annotation). Require all three classes: `PostgresOperationRepositoryTest`,
`MariaDb114OperationRepositoryTest`, `MariaDb106OperationRepositoryTest`. Record
actual XML test/failure/error/skip counts from
`java/persistence-operations/build/test-results/test/`, source SHA, client/server
versions, exact image IDs and actual server versions. A successful preflight
image inspection alone does not establish the running server version.

Keep the tests' concurrency and assertions unchanged. Bound Gradle workers only.
Use fresh `--rerun-tasks` results, disclose caches and verify zero skipped cases.
The contract's `@AfterAll` stops its own database; inspect task-owned resources
afterward and record cleanup. Never broadly prune Docker or stop another lane's
resources. Inspect raw logs/XML privately and report only sanitized counts/codes;
they may include generated connection details. A failed/unknown cleanup is a
failed qualification item, not permission for broad cleanup.

See the existing [operation repository runbook](business-operation-repository.md)
and [ADR0070](../architecture/0070-durable-business-operation-registration.md).
No full suite or container run is qualified by static inspection.

## Session evidence: 24 October 2026

Initial environment was a fresh isolated sparse checkout of the frozen base:
Ubuntu24.04.3/x86_64; Node24.19.0; pnpm11.25.0; OpenJDK17.0.20 runtime;
`javac` and Docker client absent; `/var/run/docker.sock` and `/run/docker.sock`
absent; `DOCKER_HOST` unset. Approximately 28GiB free disk and 9GiB available
memory were observed. No daemon or nesting capability was established. No Docker
install/start/restart, image pull, container creation or cleanup was attempted.

The configured proxy/CA variables were present. Bounded package HEAD inventory:
npm registry200; Adoptium release endpoint403 (policy/access denied); Gradle
metadata200; Docker registry401 (authentication challenge, not successful image
access). The JDK denial ended installation attempts; no alternate download,
proxy bypass or network-policy change was attempted. Git HTTPS read worked;
shell push dry-run failed (diagnostics withheld). Source-control publication uses
the separate connected GitHub tool, not an acquired shell credential.

Two real preflights returned the same failing checks: Java `JDK21_REQUIRED`,
Docker client `MISSING`, daemon `CLIENT_REQUIRED`, and three `DAEMON_REQUIRED`
images. Node, pnpm and wrapper inspection passed. Explicit dependency setup
returned `SETUP_JDK21_REQUIRED` before installation or wrapper execution. The
development-tool tests exercise a synthetic compliant JDK/Docker, both required
Java negatives, wrong pnpm, absent client, daemon failure, a real child-process
timeout, image/wrapper refusal, redaction and repeatable setup. Synthetic positive
controls do not count as real Java21/Docker qualification.

An independent task-local `pnpm install --frozen-lockfile` using
`.cache/cloud/pnpm-store` passed. Frontend build/typecheck and 124 tests across
seven files passed. The nine development-tool tests passed with no skips.
`pnpm check` and the separate `pnpm java:env-check` failed at the missing Java21
prerequisite; no Java tests were qualified. Formatting initially failed on the
three new formatted files; scoped Prettier fixes were applied, and the mandatory
format check then passed. Shell syntax and diff checks passed. Flow checks passed
(13 tests plus synchronized generated artifacts). Documentation-link helper tests
passed (3); the whole-tree link command failed on 3 existing links into the
deliberately excluded planning subtree. All 4 links in this new runbook passed
the existing link resolver. No baseline document was changed to hide that failure.

Mandatory unavailable gates stay failed/not-run. Actual operation cases executed
here: **0** on every image; no container cleanup was necessary because this task
created none. Frozen JavaScript dependencies passing does not establish JDK
installation, Gradle resolution, zero-skip database execution or reproducibility.

## Owner review, publication and a new task

This task cannot publish a reusable environment through an exposed setup UI/API.
Do not change a shared environment's network, variables, secrets, install script,
start skill or publication state without owner review of this setup report.
The owner must:

1. Open **Settings > Codex Cloud > Environments**. Create a dedicated development
   environment, or open its **Edit** flow. Keep other lanes' environments intact.
2. Review this draft PR and report. Prepare the required toolchain and supported
   Docker service; approve only the package/source-control domains needed. Keep
   customer/private files excluded before repository checkout and from the saved
   filesystem. No customer secrets or VPN are required.
3. Record `bash scripts/cloud/setup.sh --install` as the explicit **Install script**
   once this commit is available in the selected repository setup. Record a
   **Start skill** that uses the platform-supported local Docker startup/readiness
   mechanism and then runs preflight; do not invent a daemon command for an
   unsupported platform. Capture tools, dependency stores and selected paths in
   the prepared filesystem/configuration, rather than referencing this task.
4. Run every gate above in that setup and review failures, actual three-engine
   case counts and cleanup evidence. Save configuration. Only after owner review,
   select **Publish** (or **Republish**) and wait for **Environment published**.
   Saving alone does not publish the prepared filesystem. Existing tasks retain
   their state and do not prove that an update is reusable.
5. Select **Start a new task** from that published environment. Verify the source
   commit, private-file exclusions and tool paths. Run setup, preflight twice,
   the tooling tests and every gate above. Record the new task and published
   environment revision, exact image/server versions and actual zero-skip counts.
   Do not reuse this task's absolute paths, caches or result files as evidence.

Until that last independent task succeeds, reproducibility and reusable Docker
qualification remain pending. The draft PR stays unmerged, and the setup writer
releases branch ownership in the handoff. Customer ingress, integrated MariaDB
authority, durable dispatch and any real-target pilot are separate decisions.
