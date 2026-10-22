# Fresh MacBook evaluation

Use this checklist on a second MacBook Air, without copying an existing development
environment. **Start with Path A.** It exercises the Java/React approval workflow
with disposable PostgreSQL and a synthetic account picker.

This is a source-based fresh-machine evaluation, **not** a completed qualification
of the installed operator package, every Mac model or an external identity provider.
All target effects are simulated. No AWS/Jira credentials are needed. Day 3 remains
**pending** until an owner records an actual unassisted second-Mac run. Built-site
and documentation tests are preparation evidence only (FR-007/012; SEC-003/011/012;
REL-002; OPS-002/004).

| Path                             | What it tests                                                                                  | Prerequisites                                                                   |
| -------------------------------- | ---------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------- |
| A — source and synthetic browser | Clean checkout, build, local approval UI and independent review/execute                        | Git, Java 21, Node 22+, pnpm 11.25.0, running Docker                            |
| B — operator archive             | Source-free Java package, private operator configuration, existing database and configured IdP | Trusted archive/checksum, Java 21, local PostgreSQL, operator-owned OIDC        |
| C — live identity/manual gate    | Exact external-provider browser and optional installed requester/Codex composition             | Same Mac as the service, dedicated test registration/users, operator enrollment |

## Before starting

- Use a new directory. Do not copy `.cache`, `node_modules`, private keys, tokens,
  `.env` files or another machine's operator configuration.
- Use a supported macOS version. In **About This Mac**, check Apple silicon versus
  Intel and choose matching installers. The same checklist covers both; successful
  testing on one Mac is not evidence for every CPU/OS combination.
- Initial dependency and PostgreSQL-image downloads need Internet access.
- Use a dedicated simulator environment, never a customer or production database.
- Run each command individually in the same Terminal unless a
  second Terminal is specified. A nonzero exit, unexpected output or missing
  prerequisite is a **stop**, not permission to continue to the next block.
- Do not expose loopback through a wildcard listener, proxy, tunnel or port
  forward. The Linux container profile is **not qualified on Docker Desktop**;
  Docker Desktop in Path A supplies disposable test PostgreSQL only.
- Close unrelated TracePermit evaluations first. Do not kill an unfamiliar
  listener or use host-wide process-name/container cleanup commands.

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
**and server** reachable. Select Java 21 on PATH as well:

```bash
export PATH="$JAVA_HOME/bin:$PATH"
javac -version
command -v lsof
```

Require a working `lsof` before any listener observation; a missing/failed probe is
not evidence of port release. If **only Corepack** is missing, use the supported
[manual installation](https://github.com/nodejs/corepack#manual-installs) into a
user-owned prefix, then verify it. This does not install project dependencies:

```bash
npm install --global --prefix "$HOME/.local" corepack@0.34.6
export PATH="$HOME/.local/bin:$PATH"
corepack --version
```

Do not use `sudo`, curl-pipe installers or an independently selected pnpm version.
If the prerequisite still fails, record only its safe category and stop.

### 2. Clone into a new directory and record the commit

```bash
tracepermit_source_parent="$(mktemp -d "$HOME/tracepermit-source-evaluation.XXXXXX")"
cd "$tracepermit_source_parent"
git clone https://github.com/hemantkgupta/tracepermit.git
cd tracepermit
git branch --show-current
git rev-parse HEAD
git status --porcelain=v1 --untracked-files=all
mkdir -p "$HOME/.local/bin"
corepack enable --install-directory "$HOME/.local/bin" pnpm
export PATH="$HOME/.local/bin:$PATH"
pnpm --version
```

Expected: branch `main`, a full recorded commit, **empty** status output, and pnpm
**11.25.0**, selected by `packageManager: pnpm@11.25.0`. Check that this is the
agreed evaluation commit before installing; stop on a mismatch. Private-repository
Git authentication is local operator setup; never paste a GitHub token into a
clone URL. Then install exactly the lockfile:

```bash
pnpm install --frozen-lockfile
git status --porcelain=v1 --untracked-files=all
```

Status must still be empty. The owner-writable shim directory avoids needing
elevated permission for `corepack enable`; preserve this PATH in each new Terminal.
If `mktemp`, Git, Corepack or frozen install fails, stop. Do not reset/delete an
existing directory, change the lockfile, disable Corepack integrity checks or use
`pnpm install --no-frozen-lockfile` to make evaluation pass.

### 3. Run the baseline checks

```bash
pnpm check
pnpm format:check
```

Both must pass. Docker-backed tests must run, not skip. A first build on a laptop
can take longer because it downloads Gradle/JARs/images. Keep the commit and command
results so failures can be tied to the exact version; do not infer compatibility
from a successful dependency installation alone.

Use a 15-minute observation budget for installation and 30 minutes for baseline
checks. These are manual stop budgets, not built-in timeouts or performance SLOs.
If a command exceeds its budget or stops progressing, interrupt with Ctrl+C,
record incomplete/failed and investigate privately before another attempt.
After both gates pass, repeat the clean-status command above; nonempty output is
a stop. Do not build a distribution from an edited checkout.

### 4. Start the synthetic approval environment

```bash
pnpm demo:approvals:browser
```

Allow up to five minutes for the printed inbox URL; if none appears, stop with
Ctrl+C and record startup incomplete, without proceeding to login. Leave a running
terminal open. Open the **printed** `http://127.0.0.1:<port>/approvals`
URL; the port is chosen for this run. Do not substitute the default demo's
5173 console or a previously printed port. The command starts the real Java
approval/browser service with a test-classpath-only synthetic account picker
and disposable PostgreSQL. There is no Gmail login or external IdP setup here.

Only run one synthetic approval environment at a time. The account roles are
`alice=requester`, `bob=reviewer`, `carol=viewer`, `dave=requester+reviewer`.

In a second Terminal, set the port from that printed inbox URL (digits only):

```bash
read -r tracepermit_port
case "$tracepermit_port" in
  ''|*[!0-9]*) echo 'STOP: numeric inbox port required' ;;
  *)
    if [ "${#tracepermit_port}" -le 5 ] && [ "$tracepermit_port" -ge 1 ] && \
      [ "$tracepermit_port" -le 65535 ]; then
      lsof -nP -iTCP:"$tracepermit_port" -sTCP:LISTEN
    else
      echo 'STOP: port must be between 1 and 65535'
    fi
    ;;
esac
```

Privately confirm its local address is `127.0.0.1:<port>`, not `*`, `0.0.0.0` or
`[::]`. Stop if it differs. Keep this Terminal for the port-release check; do not
include its process/user details in evidence.

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
not a broken login: record the old request as expired and explicitly create a
fresh request. Each synthetic login lasts approximately **290 seconds**, so it
can expire before a request. Sign in again as the same synthetic account and
reselect the same request before deciding whether it is still eligible. Do not
automatically recreate, approve or execute after an error or ambiguous result.
Never approve an old request merely to continue a recording. UI state is not an
independent count of target calls; automated API tests separately cover replay.

Use a different documentation CIDR (`198.51.100.0/24`) for the denial request, and
correlate its own ID/hash rather than the successful request. The viewer may read
workspace requests but has no request/review/execute authority. Dave's combined
roles do not allow self-review; a new request as dave must hide self-approval.

### 6. Stop cleanly

Return to the launcher terminal and press **Enter**, as its message instructs.
This stops the fixture and discards its disposable database and synthetic keys.
Expect the launcher to return within 30 seconds. In the second Terminal, check
port release for at most ten seconds:

```bash
for tracepermit_attempt in {1..10}; do
  if ! lsof -t -iTCP:"$tracepermit_port" -sTCP:LISTEN >/dev/null; then
    break
  fi
  sleep 1
done
if lsof -t -iTCP:"$tracepermit_port" -sTCP:LISTEN >/dev/null; then
  echo 'STOP: listener still present'
else
  echo 'PORT_RELEASED'
fi
```

Require `PORT_RELEASED` **and** launcher return. Privately check Docker Desktop
for a remaining container owned by this run; retained resources or cleanup errors
mean incomplete cleanup. A Gradle daemon, shared image cache or another run's
container is not owned fixture state. Do not delete unrelated containers/volumes.
If Enter does not finish, use Ctrl+C once, keep the outcome as failed/incomplete,
and investigate before restarting; do not claim a clean shutdown.

This is intentional: restarting this fixture is not a durable production-restart
test. Only after cleanup passes, run `pnpm demo:approvals:browser` again: expect a
new URL/session and empty history, then stop it the same way. Do not expect old
requests, keys or simulator state to survive, and do not retain fixture directories
as audit evidence. Leave the clean source checkout in place; no recursive deletion
is required by this evaluation.

For detailed behavior and negative controls, see the
[browser inbox runbook](https://github.com/hemantkgupta/tracepermit/blob/main/docs/runbooks/browser-approval-inbox.md).
Optional native/model-driven Codex qualification is a separate step using the
[Codex approval runbook](https://github.com/hemantkgupta/tracepermit/blob/main/docs/runbooks/codex-durable-approvals.md);
Path A alone does not qualify a new Codex installation or model.

## Path B — source-free operator installation

This path runs **outside any source checkout**. It requires Java 21 on PATH,
the macOS `shasum`, `tar`, `openssl` and `lsof` tools, a dedicated local PostgreSQL
database on `127.0.0.1`, and operator-prepared compatible OIDC enrollment. No source
tree, Node, pnpm or Gradle is required at runtime. Database provisioning and
live-provider registration must be ready before initialization/run; if they are
unavailable, record the path as pending rather than substituting Path A's issuer.

After extraction, read the bundled **`docs/setup-runbook.md`** for operator setup;
it requires no development checkout. The canonical web reference is the
[simulator approval distribution runbook](https://github.com/hemantkgupta/tracepermit/blob/main/docs/runbooks/simulator-approval-distribution.md).
Not every linked reference is bundled: for example, the installed setup runbook's
`container-install-profile.md` sibling is absent from this archive. Use the
canonical web reference if a relative reference cannot open; do not fetch source
or switch to the Linux container profile. Web-reference access is separate from
the archive's runtime prerequisites. Keep these fresh-machine checks in order.

### 1. Receive a trusted build

Obtain the archive, matching outer checksum and intended full source commit through
the trusted operator handover. There is no assumed published release URL. If none
exists, the **build machine only** needs the Path A build prerequisites and a clean
committed checkout at the intended commit:

```bash
git rev-parse HEAD
git status --porcelain=v1 --untracked-files=all
pnpm install --frozen-lockfile
pnpm build:distribution
git status --porcelain=v1 --untracked-files=all
```

Both status outputs must be empty. Packaging refuses dirty/missing Git provenance;
stop on any failure and do not use a leftover archive. The exact output pair is
`java/adapters-http/build/distributions/tracepermit-simulator-approval-0.1.0.tar.gz`
and the same path with `.sha256` appended. Transfer only this pair and the expected
commit to the second Mac. Keep all keys, secrets, operator config and caches out.
Use the same 30-minute build observation budget as Path A; interrupt and record
incomplete if exceeded, without transferring any stale output.

### 2. Verify and extract on the second Mac

In the directory holding the trusted pair, run:

```bash
shasum -a 256 -c tracepermit-simulator-approval-0.1.0.tar.gz.sha256
```

Require `tracepermit-simulator-approval-0.1.0.tar.gz: OK`. A checksum from the same
untrusted download is not an independent trust source. A checksum/manifest is
**not a signature** or third-party provenance attestation.

Extract into a newly created directory, never over an existing installation:

```bash
tracepermit_archive_directory="$PWD"
umask 077
tracepermit_install_parent="$(mktemp -d "$HOME/tracepermit-operator-evaluation.XXXXXX")"
tar -xzf "$tracepermit_archive_directory/tracepermit-simulator-approval-0.1.0.tar.gz" \
  -C "$tracepermit_install_parent"
cd "$tracepermit_install_parent/tracepermit-simulator-approval-0.1.0"
tracepermit_install_directory="$PWD"
shasum -a 256 -c SHA256SUMS
sed -n '1,28p' manifest.json
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"
java -version
```

Require every inner checksum to be `OK`, Java **21**, manifest schema `1.2`,
`sourceTree: "clean"`, `requiredJava: "21"`, and `sourceCommit` equal to the
trusted intended commit. Record that commit and the archive SHA-256 privately;
the package version `0.1.0` alone does not identify the build. Stop on mismatch.

### 3. Prepare private operator configuration

Follow the distribution runbook's operator setup using a `0700` directory outside
Git and synchronized storage, owner-only `0600`/`0400` config/secret/key files,
new runtime-generated persistent Ed25519 keys for this installation, and a private
editor. Substitute this extracted installation's absolute path for its example
`/path/to/...`. Do not overwrite retained keys, copy another machine's operator
JSON, or print the files. Database provisioning must verify the dedicated schema
is empty before the first initialization; uncertainty means stop.

The packaged `examples/simulator-approvals.example.json` deliberately has **no
browser object** and nonworking provider/enrollment placeholders. Copy it privately
and configure exact database, issuer, audience, client, tenant and distinct human
requester/reviewer enrollment. For a rendered inbox, the operator must also add the
browser object and absolute `consoleDirectory` for this archive using Path C's
selected public/confidential profile. `.invalid` examples cannot log in. There is
no packaged synthetic account picker or token minting endpoint.

### 4. Check offline, initialize once, then run

Set the actual private path locally, return to the extracted archive, and run the
exact offline command **before** either database initialization or startup:

```bash
export TRACEPERMIT_APPROVAL_CONFIG="/absolute/private-directory/operator.json"
cd "$tracepermit_install_directory"
./bin/tracepermit-simulator-approval --check
```

Require exit `0` and exactly:

```text
APPROVAL_CONFIGURATION_VALID: offline only; no database, IdP or listener checked.
```

Allow ten seconds; interrupt and record incomplete if it hangs. Offline validation
does not prove database or provider readiness. Exit `2` / `APPROVAL_STARTUP_FAILED`
is a stop; never copy private diagnostics into a report.

Only for a **verified-empty dedicated schema**, run once:

```bash
./bin/tracepermit-simulator-approval --init-db
```

Require exit `0` and `APPROVAL_DATABASE_INITIALIZED: fresh simulator schema only;
no listener or target started.` Allow 60 seconds; on failure, timeout or lost
acknowledgement keep the service stopped and investigate privately. Never blindly
retry initialization or delete/repair retained state. Existing compatible
installations **skip --init-db**; restore/upgrade are outside this fresh-install
walkthrough and require their own procedures.

Check the configured application port is unused with `lsof` as in Path A, then:

```bash
./bin/tracepermit-simulator-approval --run
```

Within 60 seconds require `TracePermit simulator approval API listening on
http://127.0.0.1:<port>`. Keep it foreground. Confirm numeric-loopback binding in
a second Terminal using the configured port. No listener by the budget, a different
address, or a startup failure means stop with Ctrl+C and record incomplete.
Normal startup validates existing V1–V10 state; it does not migrate or repair it.

### 5. Observe readiness, stop and restart

In that second Terminal, after setting `tracepermit_port`, run credential-free
probes with finite connect/total budgets:

```bash
curl --noproxy '*' --connect-timeout 2 --max-time 5 --fail --silent --show-error \
  "http://127.0.0.1:$tracepermit_port/health"
curl --noproxy '*' --connect-timeout 2 --max-time 5 --fail --silent --show-error \
  "http://127.0.0.1:$tracepermit_port/ready"
```

Require schema `1.0`, `status: "live"` for health and `status: "ready"` for readiness.
503 / `not_ready`, connection failure or timeout is a stop. Send no cookies, bearer,
Origin, query or body. These probes check the local process/database/recovery
composition, not the external IdP, reviewer availability or target safety.

For live sign-in and role transitions, continue with Path C. One named same-Mac
Auth0 composition completed this procedure on 2026-10-22; a different Mac,
registration or provider still needs its own record even when startup/readiness
pass.

Stop the foreground service with **Ctrl+C**, wait up to 30 seconds for process
exit, then run Path A's ten-second port-release check. Require both process exit
and `PORT_RELEASED`; do not force-kill an unknown process or label a hang passed.
Retain the database, private config and permit keys. Restart from the same archive
with the same config and `--check` then `--run`, **never --init-db again**. Old
browser sessions must be invalid; after fresh login retained requests/history
remain, with their original expiries. This does not promise simulator target-state
durability: the in-memory target is reconstructed on process restart. Never replay
a consumed request to recreate target state. Stop and check port release again.

## Path C — live external-IdP and manual qualification

The browser and service must run on the **same Mac**. A phone cannot use a cloud
sandbox's `127.0.0.1`. Provider registration, PostgreSQL provisioning, actual user
selection and installed native-client interaction cannot be established by static
docs, offline checks, synthetic tests or this cloud session.

Use the [Auth0 native/public procedure](https://github.com/hemantkgupta/tracepermit/blob/main/docs/runbooks/auth0-native-oidc-qualification.md)
for the exact provider settings and compatibility stop conditions. Its source
commands (`pnpm build`, `pnpm dev:approvals`) apply **only to a source evaluation**.
For Path B, use the archive's prebuilt `console/` as `browser.consoleDirectory`
and the `./bin/tracepermit-simulator-approval` commands above; do not install build
tools or switch to a checkout to make the installed run succeed.

- Public/native browser profile: root schema `1.1`, explicit
  `tokenEndpointAuthMethod: "none"`, exact pinned HTTPS endpoints and organization,
  numeric-loopback callback, S256 PKCE, no client secret. A confidential schema
  `1.0` profile instead needs its private browser-secret file and separate provider
  registration. Do not mix these profiles or invent a secret for a native app.
- Enroll dedicated synthetic identities with separate requester/reviewer roles.
  For the public candidate require RS256 `at+jwt`, matching signed ID/access-token
  identity/organization, required operation scopes, bounded lifetimes and exact
  callback `iss`. A missing/incompatible claim is failed/incompatible, never
  grounds to rewrite a response or relax verification.
- Repeat request → distinct review → approved/not-executed → original-requester
  explicit execution → consumed/succeeded, plus denial, viewer restrictions,
  self-review denial, session expiry, local logout and restart observations.
  Use separate browser profiles if provider SSO keeps selecting the same account.
  Local sign-out does not sign out the provider. Do not automatically retry a
  mutation or replay execution; API replay negatives are separate automated tests.
- Bound a login attempt to two minutes. On `BROWSER_LOGIN_FAILED`, record only an
  allowlisted `BROWSER_LOGIN_DIAGNOSTIC stage=... reason=...` if present. At most
  one fresh login after correcting a confirmed mismatch; never decode/export
  tokens, capture HAR or copy a callback URL to diagnose it.
- Optional installed requester/Codex use is a **separate manual gate**, following
  the archive's `docs/requester-sign-in-runbook.md` and
  [installed requester runbook](https://github.com/hemantkgupta/tracepermit/blob/main/docs/runbooks/installed-requester-public-sign-in.md).
  Its launcher sequence is `bin/tracepermit-requester-auth --check`, then explicit
  `--login`, then one `--codex`, and `--cleanup` for the exact credential. It needs
  a separately pinned Codex executable/provider registration and requester-only
  enrollment; the helper does not confer reviewer authority. Passing Path A or
  the installed browser flow does not qualify this helper/Codex composition.
- After evaluation, stop and verify port release, retain dedicated local state
  privately or retire it through the operator's planned lifecycle, and retire
  dedicated test registrations/users through the provider's normal controls.
  No database reset, schema upgrade or provider change is automated by this guide.

## What to report back

Create a private `0700` record directory outside Git/synchronized storage with
`umask 077`; use a private editor for an owner-only record. Copy this bounded
fill-in shape; leave unknowns `pending` and use only pass/fail/pending facts:

```text
Date (UTC):
Path: A / B / C (one record per path)
Outcome: pass / fail / pending; unassisted: yes / no
Mac chip family; macOS version (no device name/serial/account):
Source or manifest full commit; archive SHA-256 if B:
Java version; Node/pnpm versions if A; browser version:
Docker server reachable if A: yes / no / pending
Checkpoints: prerequisites / clean status or archive integrity / offline check /
  DB init (fresh only, otherwise not applicable) / loopback / readiness /
  request pending / viewer read-only / independent approve-not-executed /
  owner consumed-succeeded / denial / self-review refused / logout / restart
Each checkpoint: pass / fail / pending / not applicable
Shutdown returned; port released; owned fixture resources removed if A:
First failed step number + fixed safe error category (at most one line):
Undocumented assistance needed (one sentence, no raw output):
Manual gates still pending (one sentence):
```

Do not attach raw logs, terminal output, browser traces/HAR or screenshots by
default. Correlate the same request ID/hash **locally in the UI**, then record only
the status facts; do not copy identity values, IDs/hashes or payloads into this
record. Never collect tokens, authorization codes, cookies, callback URLs,
passwords, PKCE material, private keys, operator JSON, provider IDs, personal
account data or environment dumps. If an error contains an unreviewed value,
record `UNCLASSIFIED_PRIVATE_ERROR` instead. If a credential appears, do not send
it; handle rotation through the operator's normal process.

A record of startup or checks alone is not a completed unassisted evaluation.
Neither unsigned review exports nor a successful offline review-verifier result
prove current authorization, a signed attestation, complete audit history or real
target effects. All paths remain simulator-only; fresh-host, external-IdP and
installed requester/Codex results must be recorded separately for the exact build.
