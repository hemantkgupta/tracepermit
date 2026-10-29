# Okta/GitHub organization sandbox composition

Status: **library candidate locally qualified on three engines; no live authority or target access**.

Requirements: FR-001/008/009/012/015; SEC-001/002/003/006/007/008/012/019;
REL-002/005/008; OPS-005. This candidate promotes no whole requirement.

This is the Mac Air lane from the
[organization sandbox acceptance plan](../runbooks/org-sandbox-acceptance-plan.md).
EKS containment, workload identity, network policy, database placement and
emergency-stop operation remain owned by the separate EKS lane. This document
contains no organization identifiers, credentials, deployment instructions or
authorization to call Okta or GitHub.

## Implemented Java packet

The qualified composition remains a library surface. ADR0096 separately composes
it into a local source-free synthetic launcher with registration; that executable
adds no listener, credential source, token endpoint, login, deployment or live
target call.

| API                                                                     | Contract                                                                                                                                                                                                                                                                                                                                                                                 |
| ----------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `OktaOperationAuthority`                                                | Accepts separately constructed requester/reviewer `OidcAccessTokenVerifier` instances, one exact `BusinessOperationScope`, requester enrollment, offline reviewer roster, current authority profile and clock. Verified Okta role mapping only attenuates enrollment.                                                                                                                    |
| `OktaOperationReviewerRoster`                                           | Canonical operator-owned roster using the already-approved `tracepermit-installed-operation-reviewer-roster-v1` recipe. Membership expires at the earlier of token expiry and five minutes after verification.                                                                                                                                                                           |
| `GitHubOrganizationTargetBinding`                                       | Pins App, installation, repository and organization-owner IDs; exact `owner/repository`; exact head/base refs and SHAs; and only `metadata:read`, `contents:read`, `pull_requests:write`.                                                                                                                                                                                                |
| `GitHubOrganizationOperationBinding.ExpectedProfile`                    | Pins adapter `github-org-draft-pr-v1`, profile `tracepermit-github-org-draft-pr-binding-v1`, a non-production environment, recovery generation, policy release, artifact set and exact organization target.                                                                                                                                                                              |
| `GitHubOperationProfile`, `GitHubTargetBinding`, `BoundGitHubOperation` | Sealed compatibility seams permitting only the existing Track E profile and the organization profile. No third-party or request-selected implementation is allowed.                                                                                                                                                                                                                      |
| Registration and durable JDBC lifecycle composition                     | The original `AgentCoreRegistrationIngress.Enrollment` and every Track E constructor descriptor remain unchanged. A closed `OrganizationEnrollment` factory and generalized lifecycle overloads accept the organization profile. Permit verification pins the adapter ID supplied by trusted composition. The two-argument Track E codec constructor still selects `github-draft-pr-v1`. |

The concrete public composition signatures are:

```java
new OktaOperationAuthority(
    OidcAccessTokenVerifier requesterVerifier,
    OidcAccessTokenVerifier reviewerVerifier,
    BusinessOperationScope scope,
    OktaOperationAuthority.RequesterEnrollment requesterEnrollment,
    OktaOperationReviewerRoster reviewerRoster,
    BusinessOperationAuthorityProfile authorityProfile,
    String reviewerClientId,
    Clock clock);

TrustedRequesterContext requester(String authorization, String requiredScope);
TrustedOperationEvaluatorContext evaluator(String authorization);
OktaOperationAuthority.ReviewAuthority reviewer(String authorization);

new GitHubOrganizationTargetBinding(
    long appId, long installationId, long repositoryId, long ownerId,
    String repository, String head, String base, String headSha, String baseSha,
    Map<String, String> permissions);

new GitHubOrganizationOperationBinding.ExpectedProfile(
    String adapterId, String environment, long recoveryGeneration,
    GitHubBusinessOperationBinding.PolicyRelease policyRelease,
    Map<String, String> artifactDigests,
    GitHubOrganizationTargetBinding targetBinding);

BoundGitHubOperation GitHubOperationProfile.bind(
    BusinessOperationScope trustedScope, JsonNode storedOperation);

static AgentCoreRegistrationIngress organization(
    OidcAccessTokenVerifier verifier,
    WorkloadIdentityReplayStore replay,
    BusinessOperationRepository repository,
    Clock clock,
    AgentCoreRegistrationIngress.OrganizationEnrollment enrollment);

static DurableAgentCoreRegistration organization(
    OidcAccessTokenVerifier verifier,
    AgentCoreRegistrationIngress.OrganizationEnrollment enrollment,
    Clock clock,
    DataSource replaySource,
    DataSource operationSource,
    long replayCapacity,
    Duration replayDmlBudget);
```

The new `AgentCoreRegistrationIngress.OrganizationEnrollment`,
`JdbcGitHubOperationReviewRepository`, `JdbcGitHubOperationPermitIssuer`,
`JdbcGitHubOperationAdmissionRepository`, `SyntheticGitHubDeliveryTarget` and
`JdbcGitHubOperationDeliveryRepository` accept the sealed organization profile
at their trusted-composition seams. The original concrete Track E signatures
delegate internally to the sealed seam, so existing compiled descriptors and
default hashes remain intact. No public target-port parameter was added.

`AgentCoreRegistrationIngress` continues to derive identity from the verified
token. The organization factory requires the Okta profile and mapped role
`REQUESTER`; the original Track E enrollment retains its standard-profile
behavior and applies the same role attenuation when given Okta.
`OktaOperationAuthority.requester`
allows only `operations.register`, `operations.evaluate` or `operations.execute`
and requires mapped role `REQUESTER`. `reviewer` requires `operations.decide`,
mapped role `REVIEWER`, a separately constructed verifier, a client distinct
from the requester client, an exact human roster row, and both a verified
subject and principal distinct from the operation requester. Sender-constrained
tokens remain rejected by this candidate because no selected sender-constraint
transport has been approved.

All profile and roster inputs are operator/service construction values. They
must never be deserialized from an action, lifecycle command or target response.
Bound operation data, policy decisions, approval labels and signed permits do
not themselves grant dispatch authority.

## Exclusive paths and fixed GitHub composition

The existing transport permits only these pre-write observations, in order:

1. `GET https://api.github.com/installation/repositories?per_page=100&page=1`
2. `GET https://api.github.com/repos/{owner}/{repository}`
3. `GET https://api.github.com/repos/{owner}/{repository}/git/ref/heads/{head}`
4. `GET https://api.github.com/repos/{owner}/{repository}/git/ref/heads/{base}`

The only proposed write remains:

`POST https://api.github.com/repos/{owner}/{repository}/pulls`

with exactly the five fields `title`, `head`, `base`, `draft:true` and textual
`body` (the wire value is the empty string when the canonical optional body is
absent). The organization binding is case-exact and permits one repository, one
pre-existing head, one base and their exact 40-character lowercase commit SHAs.
Merge, branch mutation, issues, settings, workflows, administration and any
other repository are excluded. The existing fixed-origin, public-DNS,
platform-TLS, no-proxy, no-redirect, no-retry and bounded-response rules remain
prerequisites; this candidate does not create a second transport.

After the same four GET preflight observations, read-only reconciliation may use
only this exact lookup:

`GET https://api.github.com/repos/{owner}/{repository}/pulls?head={owner}:{head}&base={base}&state=all&per_page=100&page=1`

Reconciliation may confirm only one complete matching open draft. Empty,
duplicate, paginated, unavailable or mismatched observations remain unknown.
It must never issue a new permit, claim a new attempt or repeat the POST.

## Required organization inputs

The restricted operator manifest must pin public values or fingerprints only:

- exact Okta custom authorization-server issuer, API audience, tenant claim and
  external-to-local tenant mapping;
- requester and reviewer client IDs, public JWKS trust configuration and exact
  supported scope sets;
- exact requester issuer-qualified subject enrollment and requester principal;
- exact human reviewer issuer-qualified subjects, reviewer client, tenant,
  workspace, group and canonical roster fingerprint;
- policy ID/version/content hash, artifact-kind digests, recovery generation,
  review group/source fingerprint, approval lifetime, permit issuer/key ID/
  audience/public-key fingerprint and lifetime;
- GitHub App, installation, organization-owner and repository IDs; exact private
  non-fork repository; exact refs/SHAs; selected-repository installation; and the
  three exact minimum permissions;
- database implementation/version and current authority/profile fingerprints;
  the EKS lane separately pins workload, service-account and network identities.

Raw access tokens, App private keys, installation tokens, Ed25519 private keys
and permits are never manifest/evidence values. Evidence stores credential
fingerprints only.

## Fixture acceptance cases

The candidate includes generated/offline fixtures. The focused registration,
organization-binding, Okta-authority and permit-codec cases passed locally on
2026-10-28:

1. the closed registration factory creates and rebinds an exact organization
   profile containing all IDs, permissions, refs and SHAs;
2. case, account, App, installation, repository, owner, ref or SHA substitution
   fails after otherwise-valid snapshot rehashing;
3. production environment, unsafe refs, broader/extra permissions and invalid
   IDs fail during trusted-profile construction;
4. the sealed profile path returns data only and preserves the Track E profile's
   existing fingerprint and binding digest;
5. generated RS256 Okta requester/reviewer tokens require the exact mapped role,
   scope, client, tenant, subject, workspace and independent roster enrollment;
6. missing scope, role swap, unenrolled reviewer, client swap and self-review fail;
7. the canonical roster fingerprint is stable and membership is capped at five
   minutes even when token expiry is later;
8. the new permit codec accepts only the trusted composition's exact adapter ID,
   while the old constructor produces unchanged Track E bytes.

The published Java candidate at
`8108598a658121735dbd59d5a44053b3ff22da7a` passed 27/27 focused engine cases.
The later fresh aggregate recorded 3,807 cases with one inherited skip and
94.089457% line/76.854231% branch coverage. Earlier failed/hung aggregate
attempts remain historical evidence; the later pass does not establish their
root causes. No hosted CI, deployment, login, live-provider token minting or
live target call was executed.

The additive
[organization-profile three-engine contract](../runbooks/org-sandbox-three-engine-qualification.md)
now composes the accepted libraries on PostgreSQL16.4, MariaDB11.4.13 and
MariaDB10.6.25 with restricted runtime grants and runtime-generated RSA/Ed25519
material. It covers the exact organization lifecycle, negative authority/profile
matrix, eight-way contention, writer-time expiry after a real row-lock wait,
durable unknown/no-resend reconciliation and the retained Track E compatibility
surface. All 27 engine cases passed in the published receipt above. Those results
qualify that Java tree only, not later documentation edits or the separate
ADR0096 installed operator, whose manual validation remains pending.

## Genuinely new approvals still required

Track E's approved policy, reviewer independence, permit, admission, timeout,
grant and synthetic-delivery decisions are unchanged and are not reopened.
Only these new composition choices remain unresolved:

1. **Provider credential custody:** select the exact executor-only GitHub App
   signing boundary. The existing same-host POSIX key-file factory is not assumed
   to be acceptable in EKS. No broker, CSI source, secret-store driver, KMS/HSM
   signer or workload-token exchange is implemented here.
2. **Permit signing-key custody:** supply the exact workload-authenticated
   Ed25519 private-key source matching the already-pinned public key. This
   candidate does not add a persistent key store or key frame.
3. **Ingress/sender constraint:** select the actual library caller or reviewed
   authenticated transport. No public HTTP route, TLS identity, bearer-forwarding
   protocol, token minting or sender-constraint mapping is introduced.
4. **Exact organization manifest:** the identity and target owners must provide
   and approve the non-secret values above. Placeholder or repository defaults
   cannot authorize a live target.

Until all four are approved and the EKS lane supplies its exact workload-authenticated
contract, the only durable dispatch compatible with the new profile is the
existing deterministic synthetic target. Real GitHub dispatch and live read-only
reconciliation remain unwired.
