# Fresh MacBook evaluation

Use this checklist on a second Mac, without copying an existing development
environment. **Start with Path A.** It exercises the Java/React approval workflow
with disposable PostgreSQL and a synthetic login, not a personal Google account.

This is a source-based fresh-machine evaluation, **not** a completed qualification
of the installed operator package, every Mac model or an external identity provider.
All target effects are simulated. No AWS/Jira credentials are needed.

| Path                             | What it tests                                                                                  | Prerequisites                                                            |
| -------------------------------- | ---------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------ |
| A — source and synthetic browser | Clean checkout, build, local approval UI and independent review/execute                        | Git, Java 21, Node 22+, pnpm 11.25.0, running Docker                     |
| B — operator archive             | Source-free Java package, private operator configuration, existing database and configured IdP | Trusted archive/checksum, Java 21, local PostgreSQL, operator-owned OIDC |

## Before starting

- Use a new directory. Do not copy `.cache`, `node_modules`, private keys, tokens,
  `.env` files or another machine's operator configuration.
- Use a supported macOS version. In **About This Mac**, check Apple silicon versus
  Intel and choose matching installers. The same checklist covers both; successful
  testing on one Mac is not evidence for every CPU/OS combination.
- Initial dependency and PostgreSQL-image downloads need Internet access.
- Use a dedicated simulator environment, never a customer or production database.

## Path A — source-based synthetic evaluation

### 1. Install the prerequisites

Install Git if unavailable, a **Java 21 JDK**, **Node.js 22** for a reproducible
baseline, and Docker Desktop. Use the official instructions:

- [Git for macOS](https://git-scm.com/downloads/mac)
- [Temurin macOS installation](https://adoptium.net/installation/macOS) — select JDK 21
- [Node.js downloads](https://nodejs.org/en/download) — select version 22
- [Docker Desktop for Mac](https://docs.docker.com/desktop/setup/install/mac-install/)

Open Docker Desktop and wait until its engine is running. Open a **new Terminal**
after installing Java/Node, then run:

```bash
git --version
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
"$JAVA_HOME/bin/java" -version
node --version
corepack --version
docker version
```

Expected: Java 21, Node 22 or newer, Corepack present, and both Docker client
**and server** reachable. If Corepack is missing, follow the
[official Corepack installation instructions](https://github.com/nodejs/corepack#manual-installs)
before continuing. Do not bypass a failed prerequisite or paste passwords into
logs. Record the failed step and stop if an installer or permission issue is unclear.

### 2. Clone into a new directory and record the commit

```bash
mkdir -p "$HOME/tracepermit-evaluation"
cd "$HOME/tracepermit-evaluation"
git clone https://github.com/hemantkgupta/tracepermit.git
cd tracepermit
git rev-parse HEAD
git status --short
corepack enable
pnpm --version
pnpm install --frozen-lockfile
```

The new clone must have no tracked changes and `pnpm --version` must report
**11.25.0**, selected by the repository's `packageManager` field. If the directory
already exists, choose another new directory; do not reset/delete it. If Corepack
cannot create its shim, resolve its documented installation/permission issue rather
than running arbitrary project commands with elevated privileges.

### 3. Run the baseline checks

```bash
pnpm check
pnpm format:check
```

Both must pass. Docker-backed tests must run, not skip. A first build on a laptop
can take longer because it downloads Gradle/JARs/images. Keep the commit and command
results so failures can be tied to the exact version; do not infer compatibility
from a successful dependency installation alone.

### 4. Start the synthetic approval environment

```bash
pnpm demo:approvals:browser
```

Leave that terminal open. Open the **printed** `http://127.0.0.1:<port>/approvals`
URL; the port is chosen for this run. Do not substitute the default demo's
5173 console or a previously printed port. The command starts the real Java
approval/browser service with a test-classpath-only synthetic account picker
and disposable PostgreSQL. There is no Gmail login or external IdP setup here.

Only run one synthetic approval environment at a time. The account roles are
`alice=requester`, `bob=reviewer`, `carol=viewer`, `dave=requester+reviewer`.

### 5. Walk through independent review and explicit execution

Use sequential sign-out/sign-in in one browser profile. Signing in as another
account in a second tab replaces the same-origin session cookie for both tabs.
Read the steps below before creating the first five-minute request.

| Step               | Do this                                                                                                                     | Expected result                                                                      |
| ------------------ | --------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------ |
| Request            | Sign in as **alice**, request `sg-demo`, port `443`, CIDR `192.0.2.0/24` with **Request approval**                          | Exact arguments visible, `pending`, execution `not executed`                         |
| Read-only role     | Sign out, sign in as **carol**, select the request                                                                          | It can be read, but request/review/execute controls are absent                       |
| Independent review | Sign out, sign in as **bob**, inspect exact arguments/hash/expiry, click **Approve exact action**, then **Confirm approve** | `approved`, still `not executed`                                                     |
| Resume             | Sign out, sign in as **alice**, select that request, click **Execute approved simulation**, then **Confirm execute**        | `consumed`, execution `succeeded`, target remains a simulator                        |
| Refresh            | Refresh or filter by the same Request ID                                                                                    | Consumed state persists during this fixture; execute does not become available again |
| Denial control     | Create a fresh request as alice, deny it as bob using **Deny** and **Confirm deny**                                         | `denied`, `not executed`, no execute option                                          |

Each request expires after **five minutes**. If it expires while reading, that is
not a broken login: create a fresh synthetic request and repeat the review steps.
Never approve an old request merely to continue a recording. UI state is not an
independent count of target calls; automated API tests separately cover replay.

### 6. Stop cleanly

Return to the launcher terminal and press **Enter**, as its message instructs.
This stops the fixture and discards its disposable database and synthetic keys.
This is intentional: restarting this fixture is not a durable production-restart
test. Do not copy fixture directories as retained audit evidence.

For detailed behavior and negative controls, see the
[browser inbox runbook](https://github.com/hemantkgupta/tracepermit/blob/main/docs/runbooks/browser-approval-inbox.md).
Optional native/model-driven Codex qualification is a separate step using the
[Codex approval runbook](https://github.com/hemantkgupta/tracepermit/blob/main/docs/runbooks/codex-durable-approvals.md);
Path A alone does not qualify a new Codex installation or model.

## Path B — source-free operator installation

The exact operator setup is the
[simulator approval distribution runbook](https://github.com/hemantkgupta/tracepermit/blob/main/docs/runbooks/simulator-approval-distribution.md).
It covers trusted archive/checksum verification, new private Ed25519 keys, local
PostgreSQL, OIDC enrollment, browser configuration and startup. Use that runbook
in order; do not transplant Path A's account picker or tokens into the package.

If no archive is provided, build one from a **clean, recorded source commit** on
the build machine with `pnpm build:distribution`. Transfer only the generated
archive and checksum, not build caches or private operator files. Packaging records
the source commit; a checksum/manifest is not an independent signature. Do not
advertise or assume a stable downloadable release unless one has actually been published.

The operator sequence is:

```bash
export TRACEPERMIT_APPROVAL_CONFIG="/absolute/private-directory/operator.json"
./bin/tracepermit-simulator-approval --check
./bin/tracepermit-simulator-approval --init-db
./bin/tracepermit-simulator-approval --run
```

Run these **inside the extracted distribution**, only after completing the
operator runbook. `--init-db` is for a fresh empty dedicated schema **once**,
never an existing/recovered database. `--check` performs no network/database/IdP
readiness check. Normal startup does not migrate or repair the database. This path
still needs approved external OIDC configuration; compatibility must be tested for
the exact provider. Neither Path A nor offline validation establishes that proof.

## What to report back

Keep a short private record:

- Path A or B, date, Mac chip, macOS version and exact source/manifest commit.
- Java/Node/pnpm versions and whether Docker's server was reachable.
- First failing command and its safe error, or which walkthrough rows passed.
- Screenshots of pending, approved/not-executed, consumed/succeeded and denied
  synthetic states, with the same request ID/hash correlated within each request.
- Any steps that needed undocumented help. A guided success is not yet proof of
  unassisted installation.

Do not share tokens, cookie values, callback URLs, private keys, operator JSON,
passwords, personal account details or a full environment dump. If a real credential
appears, do not send the screenshot; redact locally and arrange appropriate rotation.
