# Rendered approval-inbox regression suite

This opt-in suite exercises the **built React inbox**, Java browser adapter,
synthetic identity provider and disposable PostgreSQL 16.4 fixture. It is not a
mock-only reducer test, a real-provider certification, or production enforcement.
All downstream effects remain simulated. No personal login is needed.

Relevant requirements receive bounded test evidence: FR-003/007/008/009/010;
SEC-001/003/005/006/009/012; REL-002/004. Neither these tests nor hidden buttons
replace Java's authorization and exact-request checks. See
[the browser boundary](../architecture/0036-local-browser-approval-boundary.md)
and [history contract](../architecture/0046-authenticated-approval-history-pagination.md).

## Prerequisites and commands

- Node.js 22 or newer, pnpm 11.25.0, Java 21 and a running Docker daemon.
- Install frozen dependencies: `pnpm install --frozen-lockfile`.
- The exact development dependency is Playwright `1.64.1`. It adds no production
  browser dependency and does not download browsers during the test command.
- Explicitly prepare its matching Chromium once:

  ```bash
  pnpm exec playwright install chromium
  pnpm test:approvals:browser
  ```

On a Mac with Chrome already installed, the explicit alternative is:

```bash
TRACEPERMIT_BROWSER_CHANNEL=chrome pnpm test:approvals:browser
```

Only `chrome` is accepted as a channel override. There is no executable-path,
remote server/database, real account, existing browser-profile or arbitrary
command override. Missing Docker, browser, Java or startup prerequisites fail
nonzero; they are not passing skips. The default command launches Chromium.
See the official [Playwright library](https://playwright.dev/docs/library)
and [clock documentation](https://playwright.dev/docs/api/class-clock).

The suite starts `pnpm demo:approvals:browser` as its own child. Do not launch
another fixture for it or supply an approval-inbox URL. It accepts only the
numeric-loopback origin parsed from that child's exact readiness line. Each
invocation uses a new database and ephemeral keys. The default `pnpm check`
stays unchanged; CI runs this suite in a separate `browser-approvals` job with a
15-minute bound and an explicit matching Chromium installation.

## What is tested

| Surface                   | Evidence and boundary                                                                                                                                                                                                           |
| ------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| First mount               | Anonymous 401 reaches sign-in rather than staying loading; the actual synthetic account picker returns to a usable inbox.                                                                                                       |
| Role and owner visibility | 51 Alice requests and 201 Dave requests are created through authenticated browser APIs. Alice traverses only her own 51; Carol has workspace visibility and no mutation buttons.                                                |
| Continuation              | Real server pages accumulate through 100/150/200/250, without duplicates. The cap is distinct from observed exhaustion; no total/export completeness is claimed.                                                                |
| Filters and keyboard      | Draft typing does not fetch. Enter applies an exact tool filter; request-ID and stored-status filters alter rendered results; Reset clears filters and traversal.                                                               |
| Exact-action lifecycle    | Alice submits in the UI, Bob confirms the displayed exact hash, and the result is approved/not-executed. Alice separately confirms execution, yielding consumed/succeeded. Mutation refresh starts from page one.               |
| Replay                    | An explicit second execute through the real authenticated browser API returns 409. This is not an independently measured simulator invocation counter.                                                                          |
| Client failures           | Injected 400/401/403/503 and malformed 200 pages clear stale rows/authority, disable mutation handlers and hide raw diagnostic canaries. These are labeled client-transport faults, not malformed responses observed from Java. |
| Authority changes         | A controlled changed-principal session cannot append another page into an old traversal. It requires a fresh search.                                                                                                            |
| Expiry                    | A controlled pending-but-expired item preserves stored status; a controlled client session and Playwright clock expire local authority without sleeping for server token expiry.                                                |
| Cancellation/logout       | An intentionally delayed fetch returns a fully received body even after cancellation. Logout still prevents it restoring session, rows or mutation authority.                                                                   |
| Navigation remount        | A pending read is interrupted by document navigation, then a fresh mount loads correctly. This is not development StrictMode double-effect qualification; a production build does not replay those effects.                     |
| Render health             | Intended route/title, meaningful content, no framework overlay, classified console/network errors, empty token storage and mobile 390×844 without horizontal document overflow. Desktop is 1440×1000.                           |

## Bounds, privacy and cleanup

Startup is bounded to 240 seconds; UI operations to 15 seconds; each named
interaction group to 90 seconds; the enclosing rendered suite to 10 minutes.
Readiness parsing and child output are bounded. The child output is never
forwarded: Gradle/Flyway output can contain JDBC addresses or private paths.
Failure messages contain fixed phases, error categories and test line numbers,
not raw browser exceptions, response bodies, callback URLs or credentials.

Browser session cookies and CSRF values stay in isolated context memory. The
suite does not record browser storage state, cookies, network traces, headers,
JWTs or the environment. Synthetic test identifiers are not customer data.
Ambient provider credential variables are removed from the fixture environment.

Cleanup closes owned contexts/browser and sends Enter to the owned fixture,
allowing its Java try-with-resources to close PostgreSQL and discard its unique
temporary directory. It waits for clean exit and independently probes that the
owned listener port is released. On a shutdown timeout, escalation targets only
the directly spawned detached child process group; it fails the test rather than
silently accepting forced shutdown. It never kills an arbitrary port, container
or other checkout's process. The port check does not independently inventory
every Docker resource; fixture lifecycle and existing container tests provide
the separate resource-close coverage.

Optional screenshots can be enabled explicitly:

```bash
TRACEPERMIT_BROWSER_SCREENSHOTS=1 pnpm test:approvals:browser
```

They are written to a newly created private OS-temporary directory, outside the
repository; the successful run prints that directory. No screenshot, profile,
trace or raw backend log is uploaded by CI. Inspect any local evidence before
sharing it. Screenshots support visual review but are not substitutes for DOM
and interaction assertions.

## Qualification record

Observed on 2026-10-17, based on main
`6af7755840a84fa66e38edb88ced4db2c3357155`:

- Two fresh-fixture installed-Chrome runs passed **18/18 tests** each, zero
  skips: 16 rendered interaction groups, their enclosing test and the origin
  parser test. Chrome was `155.0.8059.82`; Node was `25.6.1` and Java 21.
  Both runs confirmed clean fixture exit and listener-port release. CI separately
  exercises Node 22 and the dependency-matched Chromium on Linux.
- Restoring only the original PR #40 `ApprovalInbox.tsx` in the owned checkout
  reproduced the expected anonymous-entry timeout. The enclosing test failed
  nonzero, cleanup still succeeded, and the merged component was restored exactly
  before both final green runs. No control mutation was committed.
- A private temporary child-command stub printed a synthetic diagnostic canary
  and exited 2 before readiness. The suite failed with
  `BROWSER_FIXTURE_EXITED_BEFORE_READY`; its report did not contain that canary.
  This proves startup-failure output handling, not live database-outage recovery.
- `pnpm check` passed: **724 Java tests / 90 suites**, zero failures/errors/skips,
  plus **41 React tests**. Formatting, documentation/flow checks, diff hygiene,
  frozen installation and dependency audit passed. These existing tests do not
  replace the rendered suite.

Exact final-head CI evidence belongs in the PR. Earlier harness-development
failures are retained privately, not relabeled as passing qualification. Expected
API negative controls and only the exact owned fixture/IdP optional favicon404
are classified; unexpected application resource errors still fail the suite.

The historical first-load regression was present at original PR #40 producer
head `1e6edd45dd525a6e33a358cab948f62ea905fe7e`: its mismatched initial generation
left successful/unauthenticated reads permanently loading. A sensitivity run
must fail on that condition and then pass with the merged repair, in an owned
diagnostic checkout or with an exactly restored local control. Do not push the
broken control or modify another worker's checkout.

These tests do not establish external IdP compatibility, production cookie/TLS
isolation, distributed sequence safety, retention/export completeness, load
capacity, p95 targets or PR #41's concurrency correctness. They do not change
authority, schemas, policies, PostgreSQL migrations or simulator behavior.
