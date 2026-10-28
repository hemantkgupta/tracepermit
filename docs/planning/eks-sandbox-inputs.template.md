# Restricted sandbox input sheet — blank template

Copy this blank sheet to an owner-only file outside all Git checkouts before
entering real organization values. Do not commit the completed sheet or paste
it into a public issue/Page. Supply identifiers, public fingerprints and
operator-approved custody references only: **no keys, tokens, passwords,
kubeconfigs, cookies, credential-bearing URLs or secret values**.

Every field below is unselected. Record `not applicable` with an owner and
reason when appropriate. `Unknown` blocks the stage that depends on it.
This is an input sheet, not executable configuration or a Kubernetes manifest.
See the [contract](eks-sandbox-contract.md) for stage and authority limits.

## A. Scope, client and owners

| Exact input                                                                                                    | Value   |
| -------------------------------------------------------------------------------------------------------------- | ------- |
| Pilot owner; platform operator; security approver; Okta owner; GitHub target owner; incident contact           | PENDING |
| Approved stage: P1 synthetic, later P2 agent integration, or separately approved P3 non-production target      | PENDING |
| Actual agent/client name and version; where it runs; whether hostile arbitrary code is in scope                | PENDING |
| Desired entry point: human-only installed Job for P1; exact proposed CLI/MCP/HTTP adapter and transport for P2 | PENDING |
| Tenant/workspace IDs, requester enrollment and independent reviewer group identifiers                          | PENDING |
| Pilot time window, budget/quotas, fixed workload and target-count limit                                        | PENDING |
| Go/no-go signers and who can stop admissions, networking and provider identity                                 | PENDING |

## B. AWS / EKS cluster and topology

| Exact input                                                                                                                    | Value   |
| ------------------------------------------------------------------------------------------------------------------------------ | ------- |
| AWS account ID, partition, region, cluster name and cluster ARN; dedicated sandbox confirmation                                | PENDING |
| Kubernetes/EKS version, platform version and API endpoint public/private access configuration                                  | PENDING |
| Operator's approved AWS role and kube-context name/reference; cluster-access method and RBAC scope                             | PENDING |
| Compute mode (managed/self-managed EC2, Fargate or Auto Mode); node groups, instance architecture and OS/AMI/runtime versions  | PENDING |
| VPC ID, subnets, availability zones, route tables, security groups, NAT/egress endpoints and any peering/transit routes        | PENDING |
| Cluster/service/Pod/node CIDRs; IPv4/IPv6/dual-stack; additional interfaces or chained CNI                                     | PENDING |
| CNI and version; network-policy add-on/engine and version; startup enforcement mode and outage behavior                        | PENDING |
| Existing selecting network policies and admission controls; org-approved policy review/export reference                        | PENDING |
| Proposed separate agent/trusted namespaces, service accounts, trusted/untrusted node placement and RuntimeClass                | PENDING |
| Pod Security/admission policy version; who can change namespaces, labels, workloads and policies                               | PENDING |
| Agent workload identity/token-mount state; executor/audit Pod Identity or IRSA mappings and exact IAM trust/policy references  | PENDING |
| Node IAM role and IMDS configuration; applicable metadata/container-credential endpoints                                       | PENDING |
| Approved DNS resolver and DNS-name controls; egress gateway/proxy/firewall product/configuration reference                     | PENDING |
| Exact allowed destinations, ports and methods/paths: model/dependency, registry, identity, audit; GitHub only for approved P3  | PENDING |
| Registry/repository, immutable runtime image digests, accepted Track E commit/tree/archive SHA256 and SBOM/inventory reference | PENDING |

## C. Database, credentials and evidence

| Exact input                                                                                                                        | Value   |
| ---------------------------------------------------------------------------------------------------------------------------------- | ------- |
| P1 engine/image: PostgreSQL16.4, MariaDB11.4.13 or MariaDB10.6.25; immutable image digest and architecture                         | PENDING |
| Disposable loopback sidecar confirmation; database/schema names, ephemeral/storage class policy and resource limits                | PENDING |
| Restricted runtime/operator identity references and grant policy; stopped-writer initialization/upgrade/restore owners             | PENDING |
| Existing schema/active authority profile fingerprint, policy/artifact/action/target/precondition pins and recovery generation      | PENDING |
| Track E Ed25519 public-key fingerprint and approved per-delivery private-frame supply method, without key material                 | PENDING |
| GitHub RSA key custody option and trusted-container delivery format; secret/KMS/provider reference and approved IAM resource scope | PENDING |
| Source-signing custody/public pin only if the selected ingress actually uses source attestation                                    | PENDING |
| Rotation/revocation owner and timing; no agent-readable mounts/identity; no default credential-chain fallback                      | PENDING |
| Independent audit sink, write identity, retention/delete authority, encryption and redaction requirements                          | PENDING |
| Audit outage behavior, backup/restore and emergency-stop procedure; limits for already-started/unknown work                        | PENDING |

## D. Okta — identifiers and public configuration only

| Exact input                                                                                                                        | Value   |
| ---------------------------------------------------------------------------------------------------------------------------------- | ------- |
| Sandbox Okta org/domain and identity administrator; custom authorization-server ID and exact issuer URL                            | PENDING |
| API Access Management/custom-server entitlement and applicable access-policy confirmation                                          | PENDING |
| Exact HTTPS API audience; public client/application ID; app type and approved grant/token acquisition flow                         | PENDING |
| Discovery/JWKS/authorization/token endpoints actually used; allowed signing algorithm and public JWK/pin/rotation policy           | PENDING |
| Actual signed access-token claim names/types: issuer, subject, audience, client, tenant, scope, groups, JTI and time               | PENDING |
| Required requester/reviewer scope and role values; exact lifecycle vs browser-profile adapter compatibility                        | PENDING |
| Operator-controlled tenant claim/value and canonical issuer-qualified requester/reviewer enrollment                                | PENDING |
| Independent reviewer group/roster entries, canonical roster fingerprint and active profile's membershipSourceFingerprint           | PENDING |
| Requester/reviewer token lifetime and skew; roster refresh cadence and maximum stale authorization window                          | PENDING |
| Test principals/role labels for requester, independent reviewer and denied actor; no passwords or raw claims                       | PENDING |
| Exact redirect URI only if a separately approved login flow uses one; client/TLS/audience boundary if a remote adapter is proposed | PENDING |
| Enrollment removal, group removal, key rotation, token expiry and incident revocation process/owner                                | PENDING |

## E. GitHub — exact non-production target

| Exact input                                                                                                                                      | Value   |
| ------------------------------------------------------------------------------------------------------------------------------------------------ | ------- |
| GitHub.com versus Enterprise host; exact supported API origin; organization/login and immutable account ID                                       | PENDING |
| GitHub App numeric ID and client ID; installation ID; installation owner and unsuspended state                                                   | PENDING |
| Immutable repository ID and exact owner/name; dedicated non-production/private visibility; draft feature availability                            | PENDING |
| Installation selection restricted to this repository; exact metadata:read, contents:read, pull_requests:write profile                            | PENDING |
| Exact pre-existing base/head ref names, head repository/owner, and expected head/base commit SHAs; reviewed content                              | PENDING |
| Allowed fixed draft-PR title/body/content policy and maximum number of creations in the approved rehearsal                                       | PENDING |
| API version/header contract and actual compatibility with required installation/token response assurance fields                                  | PENDING |
| App signing-key public fingerprint/custody reference, executor identity, token expiry/rotation/revocation owner                                  | PENDING |
| Branch/ruleset protections and explicit exclusion of merge, ready-for-review, push, branch creation and administration                           | PENDING |
| Actions/pull_request_target/workflow permissions, webhooks, bots, deployment hooks and notification consequences; production-secret/path absence | PENDING |
| Read-only reconciliation lookup binding/pagination limits and incident procedure for an unknown create acknowledgement                           | PENDING |
| Target owner approval for exact live non-production effects and separately approved operator cleanup                                             | PENDING |

No synthetic fixture repository, default issuer/audience, local kube-context or
existing personal credential fills an unanswered field automatically. Use the
completed sheet to prepare the implementation packet and stage-specific approval;
filling the sheet itself does not authorize deployment or provider calls.
