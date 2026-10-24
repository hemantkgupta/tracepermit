# A matching operation is still not permission to execute

The business-operation contract knows tenant/workspace/requester, policy/artifact
pins, recovery generation and an immutable action. The GitHub adapter knows an
installation/repository/owner and exact refs/SHAs. Before connecting them, prove
that both describe the same resource and precondition—not just that each is
independently well-formed.

[ADR0075](../architecture/0075-github-business-operation-binding.md) defines that
pure data join. It rejects equivalent-looking aliases, whitespace repairs and
rehashed substitutions of target, scope, policy or generation. A separate
versioned profile binds both commit SHAs without changing generic canonical JSON.

Three important non-equivalences remain:

- A valid `approved` JSON state is not independent reviewer approval or a current
  policy decision. A constructor accepting trusted context is not authentication.
- A matching action hash is not an atomic durable permit/claim/evidence transaction.
  The existing request permit does not itself bind every business-operation field.
- An operation target key is not GitHub-native idempotency. An unknown write
  outcome requires bounded read-only reconciliation, never an assumed failed POST.

Even rehashing every field can produce internally consistent new data. The future
trusted service must load the authoritative stored operation and recheck current
authority. Hashes alone do not attest its origin or defeat a database administrator.
Binding data intentionally creates no credential, permit, execution claim or call.
