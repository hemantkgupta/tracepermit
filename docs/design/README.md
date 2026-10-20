# Flow diagram maintenance

[flow-diagrams.json](flow-diagrams.json) is the source of truth for the central
overview and twelve action/operator sequences. Each entry defines a stable ID, title,
participants and ordered messages/notes. It contains no credentials or external
image dependencies. The source is illustrative documentation, not runtime policy.

The dependency-free [generator](../../scripts/generate-flow-diagrams.mjs) produces:

- Mermaid regions between matching `diagram:<id>` comments in the
  [developer guide](../flows-guide.md) and [public guide](../../site/guide/core-flows.md).
- Accessible standalone SVGs in `site/public/images/flows/`.

Edit the catalog and surrounding prose, then run:

```bash
pnpm docs:flows:generate
pnpm exec prettier --write docs/design/flow-diagrams.json docs/flows-guide.md site/guide/core-flows.md
pnpm docs:flows:check
pnpm docs:links
pnpm site:build
```

Do not hand-edit generated blocks or SVGs. `docs:flows:check` runs generator tests
and an exact artifact comparison; CI fails for a missing image, stale Mermaid,
duplicate marker or drifted SVG. It does not establish semantic correctness:
review messages against the Java adapter/service, active policy, tests and
qualification evidence before changing a claim.

The site shows native SVGs rather than requiring client-side Mermaid rendering.
Each diagram has a keyboard-focusable horizontal scroll region and a full-size
link so narrow screens need not shrink labels into unreadable text. Verify the
page on desktop and mobile after layout or catalog changes.

The existing PNG is a separate overview illustration, not a generated sequence
or authoritative replacement for the maintained flow catalog.

## Proposed product contracts

- [Pending approval cancellation](pending-approval-cancellation.md): owner-only,
  pending-to-cancelled withdrawal, race/evidence semantics and the prerequisite
  explicit database upgrade. The proposal itself is design only;
  [ADR 0059](../architecture/0059-explicit-v9-v10-simulator-upgrade.md) implements
  the storage/upgrade prerequisite and
  [ADR 0060](../architecture/0060-authenticated-pending-request-withdrawal.md)
  implements the Java API. Requester UI/installed withdrawal qualification remain.
