---
title: Deployment status and production design
description: What can be run today and what remains before production deployment.
---

# Deployment status and production design

TracePermit has a repeatable **local Java POC** and a static documentation
site. It does **not** publish a production gateway image, a ready-to-run
production Compose file, or a public enforcement service. The supported
development backend binds to loopback and executes only safe AWS/Jira
simulators.

## Run today

For a second laptop, start with the [fresh MacBook checklist](/guide/fresh-macbook-evaluation).
Path A is a source-based synthetic browser evaluation; Path B uses the packaged
operator application and requires separate provider/database configuration. They
are not interchangeable qualification profiles.

Follow the [local POC quickstart](/guide/getting-started). The default
composition uses ephemeral in-process development keys and state. A separate
opt-in PostgreSQL-backed profile demonstrates selected durable enforcement
properties; it is not a turnkey production deployment.

The VitePress documentation site can be built independently:

```bash
pnpm site:install
pnpm site:build
```

The generated static files are under `site/.vitepress/dist/`. Hosting those
files on `tracepermit.org` does not expose or deploy the Java gateway.

To read the site locally without a Java backend, use `pnpm site:dev`, or run
`pnpm site:preview` after the build. Both default to `127.0.0.1`; stop the local
server with Ctrl+C. Preview refuses an occupied port; select another with
`pnpm site:preview -- --port 4174`.
The root workspace uses pnpm 11.25.0; these wrappers run npm in the separately
locked `site/` project, which pins npm 11.9.0 and requires Node 22.9.0 or newer.
If a Corepack npm shim reports “configured to use pnpm,” use these root commands,
not root-CWD `npm --prefix site` calls. Do not disable the package-manager guard.

## Buildable source-free approval application

The separate archive bundles Java libraries and the built React approval inbox.
The runtime host does not need Node, pnpm, Gradle or a source checkout, but still
needs Java 21, dedicated local PostgreSQL, private keys and operator-owned OIDC
configuration. A build target is not a promise of a published downloadable release.
Follow the [operator installation runbook](https://github.com/hemantkgupta/tracepermit/blob/main/docs/runbooks/simulator-approval-distribution.md).

| Command              | What it establishes                                               | What it does not establish                                           |
| -------------------- | ----------------------------------------------------------------- | -------------------------------------------------------------------- |
| `--check`            | Offline configuration, key-pair and asset validity                | Database/IdP readiness or successful login                           |
| `--init-db`          | Explicit first installation into a fresh empty dedicated schema   | Upgrade, repair or restore safety                                    |
| `--upgrade-db`       | Explicit owner-only stopped-service V9→V10 schema upgrade         | Online migration, cancellation API, repair, downgrade or safe resume |
| `--run`              | Strict existing-state checks and numeric-loopback service startup | Public TLS deployment or universal provider compatibility            |
| `--recovery-inspect` | Owner-only consistent aggregate stored-state snapshot             | Quiescence, verified target outcomes or safe resume                  |
| `--quarantine-db`    | Owner-declared one-way containment of new durable authority       | Cancellation of committed claims or automatic old-backup detection   |

See the [startup sequence](/guide/core-flows#flow-11-check-initialize-and-start-a-local-package)
and [inspection/quarantine sequence](/guide/core-flows#flow-12-inspect-and-declare-recovery-quarantine).
There is no quarantine-clear/resume command. Keep effectful services stopped
independently during recovery; already-committed claims can still finish.

Current packages require V1–V10. Preserve a verified backup and stop all clients
before upgrading an existing V9 simulator database with the new binary; ordinary
run never migrates. Failed or uncertain upgrades require private inspection with
services stopped, not blind retry or reset. V10 prepares cancelled storage and
report-format2 inspection counts; requester withdrawal is **not exposed yet**.
See the [upgrade procedure and limits](https://github.com/hemantkgupta/tracepermit/blob/main/docs/runbooks/simulator-approval-distribution.md#explicit-stopped-service-upgrade-v9-to-v10).

## Bounded local failure controls

The separate approval listener bounds active and pending executor tasks and
closes boundedly; overflow is a transport refusal, not a promised JSON 503.
Identity key refresh/DNS work has finite local resources and refuses expired or
unavailable trust. A completion-write error after dispatch can trigger one bounded
unknown-outcome recording attempt, never a target retry. These controls have
targeted automated tests; they are not TCP/byte quotas, distributed abuse state,
forced dependency cancellation, a graceful drain or a production SLO.

See [what works today](/guide/working-today) and the public
[admission](https://github.com/hemantkgupta/tracepermit/blob/main/docs/runbooks/simulator-approval-admission.md),
[identity refresh](https://github.com/hemantkgupta/tracepermit/blob/main/docs/runbooks/jwks-refresh-isolation.md)
and [recovery](https://github.com/hemantkgupta/tracepermit/blob/main/docs/runbooks/operator-recovery-quarantine.md)
runbooks for their exact boundaries.

## Target production composition (not shipped)

```text
Managed ingress / TLS / sender constraint / abuse controls
                        ↓
              Java enforcement service
                        ↓
     durable state + managed keys + target broker
                        ↓
             governed enterprise targets
```

Before real targets or public ingress, the design needs managed key custody
and rotation, endpoint enrollment, production OAuth and client qualification,
shared rate/replay state, secret handling, anti-bypass egress, independently
verified target credentials, operational monitoring, scale tests, staged
canary/rollback, and a security review. These are explicit gaps, not hidden
features of the local POC. See the [capability
matrix](https://github.com/hemantkgupta/tracepermit/blob/main/docs/specs/03-current-state-baseline.md).
