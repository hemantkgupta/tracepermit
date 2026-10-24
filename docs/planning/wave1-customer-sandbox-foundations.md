# Customer-sandbox wave-one integration board

Frozen start: `f23dc22f056058d7977715e0878c284728d10d17` (merged PR103).
This is an integration plan, not a claim that the customer pilot is running.
GitHub PR heads, ownership comments and exact-head receipts supersede snapshots
of status in this document. All PRs stay unmerged until the operator decides.

## Work allocation

| Lane                       | Deliverable / coordination point                                                                                                                               | Does not implement                                                     |
| -------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------- |
| Mac                        | [PR101](https://github.com/hemantkgupta/tracepermit/pull/101): current-main withdrawal/journal reconciliation                                                  | Generic approval/executor authority                                    |
| Mac                        | [PR104](https://github.com/hemantkgupta/tracepermit/pull/104): [pure exact operation/target bridge](../architecture/0075-github-business-operation-binding.md) | Permit, claim, credentials or target dispatch                          |
| Gemini                     | [PR95](https://github.com/hemantkgupta/tracepermit/pull/95), then [separate MariaDB transaction design](packets/wave1-gemini-okta-and-mariadb-design.md)       | Live Okta/OBO or MariaDB authority parity                              |
| Cloud credential writer    | [PR105](https://github.com/hemantkgupta/tracepermit/pull/105), [packet](packets/wave1-cloud-github-credentials.md)                                             | Live installation, approved execution or a second binding bridge       |
| Cloud setup writer         | [Java21/Docker setup packet](packets/wave1-cloud-java21-docker-setup.md)                                                                                       | Customer AWS infrastructure or permission to republish shared settings |
| Optional independent cloud | [Read-only boundary audit](packets/wave1-cloud-independent-boundary-audit.md)                                                                                  | Writer ownership, fixes or whole-product certification                 |

The [shared protocol](packets/wave1-shared-protocol.md) freezes exclusive paths,
repair limits, security constraints and handoff fields. Session creation is not
proof of a prepared environment or completed work. A new cloud task does not
inherit local Mac tools, files, sign-ins or Docker. Reuse the existing prepared
TracePermit environment if it is suitable; publication changes need owner review
and a fresh-task verification, not just a passing setup conversation.

## Wave-one integration gates

1. PR101 and PR95 reconcile normally against the frozen current main, with focused
   and full gates. Prior-head green checks do not qualify a new merge.
2. The bridge independently rejects rehashed scope/resource/policy/generation
   substitutions and action normalization; valid output remains data only.
3. The credential implementation preserves the existing provider port and fixed
   GitHub transport contract, with synthetic restricted-exchange and leak controls.
4. The MariaDB design fixes one transaction/port/migration/fault contract before
   independent writers implement it. No network target or IdP call under DB lock.
5. Environment proof includes Java21 and real disposable database tests without
   availability skips; unavailable Docker is an explicit gate, not a passed test.
6. Independent review addresses exact final heads and new cross-component risks.
   It cannot turn a library into the missing integrated authority service.

## Wave two starts only after contract review

The next runtime split is durable claims/reconciliation (Mac), the reviewed
MariaDB authority transaction (Gemini), and trusted execution-disabled AgentCore
ingress (cloud). Freeze their interfaces, lock order, identity provenance,
recovery-generation semantics and owned paths before parallel implementation.

A successful sandbox milestone eventually needs an authenticated, scoped
operation, an independently approved exact action, one durable execution winner,
one exact draft PR and read-only handling of uncertain outcomes. GitHub creation
has no native operation-key guarantee in this profile: do not advertise exactly
once or infer absence from an empty lookup. Old-backup resurrection, RDS/TLS/
failover, private EKS routing and S3 evidence retention remain explicit gates.

The original simulator release has a conditional private-evaluation GO and a
deferred fresh-second-Mac gate, recorded on [issue71](https://github.com/hemantkgupta/tracepermit/issues/71#issuecomment-6346452019).
That decision is not permission for real customer targets, public ingress,
public download or production claims. No customer name/private planning is in
these packets. Live provider/target/AWS work needs separate scoped approval.
