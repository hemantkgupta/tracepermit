# GitHub business-operation binding qualification

This internal, pure library checks that operation data and the explicit operator
target record describe the same pilot target. It authorizes no action and opens
no listener. See [ADR0075](../architecture/0075-github-business-operation-binding.md).

## Inputs and output

- `BusinessOperationScope`: separately derived tenant/workspace/requester context.
- Stored operation snapshot: integrity-checked by the storage adapter; not a
  model-authored authority body. The bridge copies and revalidates this tree.
- `ExpectedProfile`: service/operator-owned exact adapter, non-production
  environment, recovery generation, policy/content/artifact pins and target tuple.

Call `expected.resourceBinding()` while constructing a new trusted operation;
do not repair or overwrite an already stored binding with a newly calculated
value. At bind time the stored value must already match. Original request-key
retry semantics and operation lifecycle repositories remain unchanged.

Output is `BoundOperationData`: defensive copies and hashes. It proves neither
authentication, database origin, approval, current authorization nor execution
ownership. Do not display snapshots in logs/evidence merely because `toString`
is redacted. No raw credential is an input to this API.

## Strict profile and stop conditions

The full allowlisted repository must appear exactly in the operation arguments
and target account. The target is GitHub/non-production/`pull_request`, protocol
HTTP, draft true, exact configured head/base, bounded title and optional textual
body. Split aliases, trimming and case normalization are deliberately not part
of this profile. The generic frozen example remains valid generic data but fails
this stricter join; preserve it and use the separately named golden recipe.

Stop with fixed `GITHUB_OPERATION_BINDING_INVALID` on scope, adapter, generation,
policy, artifacts, resource identity, precondition, dispatch interpretation,
schema/hash/type mismatch or oversized UTF-8 snapshot. Do not retry automatically,
substitute expected values, mint a permit or submit a target call after rejection.

Both refs and SHAs are bound as `resource_state_digest`. They still require a
fresh target preflight in a future executor; hashing a ref is not a GitHub CAS.
An unknown outcome must remain lookup-only, not become another creation attempt.

## Reproducible verification

Use Node22+, pnpm11.25.0 and the repository Java21 configuration:

```bash
bash -c 'source scripts/configure-java-environment.sh && java/gradlew -p java :adapters-remote:test --tests "*GitHubBusinessOperationBindingTest" --max-workers=1 --console=plain'
pnpm check
pnpm format:check
pnpm docs:links
pnpm docs:flows:check
git diff --check
```

The focused tests need no provider credentials or network target. Broad suites
may require disposable Docker databases; do not report those as passed if skipped.
Record exact head, fresh versus reused tasks, failure/skip counts and coverage.

The independent [golden recipe](../../contracts/vectors/github-business-operation-binding-v1.json)
fixes target IDs71/101/201 and synthetic refs/SHAs; these are fixtures, not an
installation record. Its artifact list is sorted by kind for a stable whole
snapshot hash. Artifact-order equivalence in an expected profile does not mean
different serialized operation snapshots have identical whole-snapshot digests.

No live GitHub/Okta/AgentCore/RDS/S3/EKS, provider installation, public ingress,
database migration or production use is authorized by this runbook. Complete
atomic execution and fault/restart qualification remain separate gates.
