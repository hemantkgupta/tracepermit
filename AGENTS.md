# Repository instructions

## Purpose

This repository implements TracePermit, an Agentic Visibility & Control proof of concept. Preserve its central invariant: every simulated side effect is evaluated before execution.

## Development

- Use Node.js 22 or newer and pnpm 11.25.0.
- Install with `corepack enable && pnpm install --frozen-lockfile`.
- Before completing a change, run `pnpm check` and `pnpm format:check`.
- Use strict TypeScript for the React console. New backend, enforcement,
  identity, contract-validation, and simulator behavior belongs in Java.
- Keep transport-specific code in adapters; converge all origins on the canonical action contract.
- Java is the sole supported backend. TypeScript is limited to the React
  console and its build/test tooling. The checked-in language-neutral
  `contracts/` artifacts remain active.
- For product or enforcement changes, read `docs/specs/README.md`, cite the
  affected requirement IDs, and update the traceability matrix.

## Security boundaries

- Never connect demo destructive tools to real AWS, Jira, or other production resources.
- Never accept `authenticated`, `delegated`, or `verified` merely because a request body says so. A trusted adapter must derive those fields after token or transport verification.
- Never commit bearer tokens, API keys, client secrets, private keys, or real enterprise identifiers.
- Store only credential fingerprints in evidence records.
- Preserve the distinction between causal lineage and authority delegation.
- A child delegation must not broaden scopes, resources, audience, or lifetime.
- Unknown tools and unknown schema versions require conservative handling.

## Change discipline

- Add a test for every enforcement behavior or bypass prevention change.
- Update an architecture decision record when a trust boundary or security invariant changes.
- Update the relevant specification, architecture decision, and public
  documentation when behavior or implementation status changes.
- Keep fixtures deterministic and clearly simulated.
- Do not weaken a deny or approval rule only to make a demo pass.
