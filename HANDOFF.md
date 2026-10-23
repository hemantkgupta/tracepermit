# TracePermit implementation handoff

Only transferable repository state belongs here. Keep provider/customer values,
credentials, raw claims, keys and local-only planning material outside Git.

TRACEPERMIT HANDOFF

- Task: Bounded Okta public/native OIDC qualification foundation, synthetic only.
- Repository: https://github.com/hemantkgupta/tracepermit
- Branch: codex/okta-oidc-qualification
- Base commit: 642a86b2c0128ced66b49bdce3f505516560d6d6
- Latest pushed commit: this coherent checkpoint; use the PR's exact head and `git rev-parse HEAD`
- PR URL: draft created after the tested checkpoint; locate by head branch `codex/okta-oidc-qualification`
- Branch owner now: cloud until the final PR/chat handoff explicitly releases ownership
- Working tree status: committed and clean at final handoff; generated local test artifacts are ignored
- Completed: strict schema1.2 Okta custom-server/public profile, operator tenant/group bindings, verified group-role projection attenuating existing enrollment, independently bound ID/access tokens, unchanged loopback/PKCE/SSRF/TLS and durable simulator controls; generated-signature configuration/identity/browser negatives and role boundaries; ADR0067, specifications and operator runbook
- Remaining: independent security review and exact-head CI review; separately approved live Okta registration/login and EKS pilot work; no live qualification is claimed
- Files materially changed: Java identity token profile/verifiers and HTTP configuration/membership/browser adapter; synthetic fixtures and three Okta qualification test classes; ADR0067; Okta runbook; requirement/baseline/interface/acceptance/traceability specifications; this handoff
- Tests passed: 98 new Okta cases; focused identity/configuration/browser/code-exchange/Auth0 regressions; pnpm check (1,279 Java cases passed, aggregate coverage gate passed; eight Gradle tasks restored from cache); pnpm format:check; pnpm docs:links; pnpm docs:flows:check; git diff --check
- Tests failed/not run: zero failures; one installed-archive/native-PTY endpoint-connector test skipped because that archive was not built; no live Okta tenant mutation/login, EKS/public ingress, installed Okta archive/helper or real target; hosted status is recorded separately
- Exact next command: gh pr list --repo hemantkgupta/tracepermit --head codex/okta-oidc-qualification
- Required environment: Node.js 22+, Corepack/pnpm 11.25.0, Java 21 and Docker for synthetic PostgreSQL tests; review needs source/GitHub only
- Local-only/manual steps: follow docs/runbooks/okta-oidc-qualification.md only after separate interactive approval for live provider actions; keep all tenant/user/group/configuration/credential facts private
- Security constraints: no client secret; exact numeric 127.0.0.1 callback/listener; no tunnel/public endpoint or real AWS/Jira target; groups narrow operator enrollment after verification and never provision authority; no request-body roles; agentcore-work/** remains unread and excluded from the checkout/staging
- Known limitations: selected token grammar and mandatory callback iss may be incompatible with a particular registration; old signed groups remain until bounded expiry; existing approval execution rechecks local reviewer enrollment rather than live provider group state; no all-Okta/OIDC, EKS, signing or production claim
- Safe for another environment to continue: yes, after explicit ownership release in the PR/final handoff
