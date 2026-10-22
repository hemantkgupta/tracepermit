---
title: What works today
description: A simulator-only coding-agent approval workflow, its evidence, and the separate local operating profiles.
---

# What works today

TracePermit demonstrates a practical boundary for coding-agent tool requests:
authenticate the request, independently review its exact action, then explicitly
resume with single-use authority. The Java backend and React inbox run locally;
**all target effects are simulated**. This is an open-source evaluation preview,
not a production security gateway or public enforcement service.

## The approval loop

1. **Request:** a supported client submits bounded simulated arguments. Policy
   returns a durable pending request, with an exact action hash and expiry.
2. **Review:** a distinct authorized human inspects and approves or denies it.
   Approved still means **not executed**; the agent cannot review itself.
3. **Resume:** the original requester explicitly resumes the same ID/hash.
   PostgreSQL atomically consumes the approval and claims execution before dispatch.
4. **Inspect:** authenticated history shows the stored status and outcome.
   Expiry, changed bindings, denial or consumed authority cannot authorize replay.

<div class="flow-diagram" tabindex="0" role="region" aria-label="Coding-agent approval loop, horizontally scrollable">

![Codex request, independent browser review and explicit resume](/images/flows/flow-3-human-approval.svg)

</div>

[Open full-size diagram](/images/flows/flow-3-human-approval.svg)

## Choose the right evaluation profile

These reuse components but are **not one interchangeable installation**.

| Profile                         | Working capability                                                                                               | Qualification boundary                                                                                                  |
| ------------------------------- | ---------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------- |
| Synthetic browser walkthrough   | Requester, reviewer and viewer roles; exact-action confirmation; filtered/paged history                          | Java/PostgreSQL/browser tests and rendered UI checks, using a test-only IdP                                             |
| Codex approval rehearsal        | Authenticated MCP request/status/explicit-resume plus separate browser review                                    | Named Codex 0.162.1 / `gpt-5.6-sol` rehearsal on 2026-10-15, not arbitrary clients or models                            |
| Native endpoint read/block demo | Request-bound source proof and policy before simulated read or destructive denial                                | A separate named CLI profile, not the bearer-only approval MCP profile                                                  |
| Buildable operator archive      | Java runtime libraries and built inbox, private configuration, offline check and strict database startup         | Needs Java 21, local PostgreSQL and approved OIDC configuration; no universal external-provider or fresh-laptop claim   |
| Installed Auth0 approval        | Native/public requester sign-in, distinct reviewer approval, explicit simulator execution and restart durability | One same-Mac synthetic Auth0 registration on 2026-10-22; not arbitrary providers, public ingress or production identity |

Start with the [clean Mac walkthrough](/guide/fresh-macbook-evaluation), or read the
[recorded model-driven approval proof](https://github.com/hemantkgupta/tracepermit/blob/main/docs/demo-scenarios/evidence/codex-model-driven-approval-2026-10-15.md).
That manual record reports returned states, not an independently captured target-call
count. Automated claim/concurrency tests are separate evidence.

## More than the happy path

| Safeguard                    | What is implemented                                                                                          | Important limit                                                                                |
| ---------------------------- | ------------------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------- |
| Identity dependency failures | Bounded/coalesced key refresh, bounded DNS/wait resources, late-result and expiry rejection                  | Local-instance resource controls, not provider fairness or distributed public abuse protection |
| Approval listener admission  | Fixed active lanes, bounded pending task queue, transport refusal on overflow, bounded close                 | Not TCP/byte limits, a guaranteed JSON saturation response or a graceful drain                 |
| Uncertain completion         | One bounded attempt to record `unknown` after a completion-write failure; consumed authority is not restored | Persistent outage can leave `executing`; no automatic target retry or abandoned-attempt sweep  |
| Operator recovery            | Read-only aggregate inspection and one-way declared quarantine of new durable authority                      | Not automatic old-backup detection, cancellation of committed work or permission to resume     |

See [browser/history flow](/guide/core-flows#flow-10-browser-sign-in-and-role-scoped-history),
[package startup](/guide/core-flows#flow-11-check-initialize-and-start-a-local-package),
and [inspection/quarantine](/guide/core-flows#flow-12-inspect-and-declare-recovery-quarantine).
The [capability baseline](https://github.com/hemantkgupta/tracepermit/blob/main/docs/specs/03-current-state-baseline.md)
and task-specific runbooks distinguish implementation, automated tests, named
live qualification and future scope. Passing one profile does not qualify another.

## What is not being claimed

No real AWS/Jira executor, universal agent interception, host-wide anti-bypass,
public production gateway, independently signed release, production latency SLO
or complete recovery/upgrade product is shipped by this preview. A fresh machine
and every provider/profile beyond the named Auth0 composition still need their
own recorded qualification. Read the
[installed live record](https://github.com/hemantkgupta/tracepermit/blob/main/docs/demo-scenarios/evidence/installed-auth0-approval-2026-10-22.md).
See [deployment and operations](/guide/deployment) before choosing a profile.
