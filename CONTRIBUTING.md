# Contributing

TracePermit accepts changes through pull requests to protected `main`. Open a
focused issue first for a new trust boundary, real connector, or protocol
change. Report vulnerabilities through the private route in [SECURITY.md](SECURITY.md).

## Before a pull request

1. Read [AGENTS.md](AGENTS.md), the [specification index](docs/specs/README.md),
   and the relevant architecture decision record.
2. Keep enforcement, identity, contract-validation, and simulator behavior
   in Java. TypeScript is for the read-only React console.
3. Never add real cloud/Jira target credentials or destructive adapters to a
   demo. Do not commit secrets, personal account identifiers, or raw token
   values in fixtures, evidence, or logs.
4. For an enforcement or bypass-prevention change, add a negative regression
   test and update the affected requirement IDs in the
   [traceability matrix](docs/specs/12-traceability-matrix.md). Record a new ADR
   when the trust boundary changes.
5. Run `pnpm check`, `pnpm format:check`, and `pnpm docs:links`. Run the relevant
   PostgreSQL/endpoint/scenario check if that boundary changed.

Explain in the PR what was verified, what remains unqualified, and how you
know a denied action did not reach the simulator. CI must pass before merge.
As this is currently a solo-maintained repository, a separate approving
review is not required; PR and required CI are mandatory.

## Preview the documentation

With Node 22.9.0 or newer and the root pnpm setup ready, run from the repository
root:

```bash
pnpm site:install
pnpm site:dev
```

The static documentation needs no Java service or IdP. To inspect production
output, run `pnpm site:build` followed by `pnpm site:preview`. These commands use
the separate npm-pinned site without replacing either lockfile. See the
[site maintenance runbook](docs/runbooks/site-dependency-maintenance.md) for
Corepack troubleshooting, dependency audits and validation scope.
