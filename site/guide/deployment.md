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

Follow the [local POC quickstart](/guide/getting-started). The default
composition uses ephemeral in-process development keys and state. A separate
opt-in PostgreSQL-backed profile demonstrates selected durable enforcement
properties; it is not a turnkey production deployment.

The VitePress documentation site can be built independently:

```bash
npm --prefix site ci
pnpm site:build
```

The generated static files are under `site/.vitepress/dist/`. Hosting those
files on `tracepermit.org` does not expose or deploy the Java gateway.

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
