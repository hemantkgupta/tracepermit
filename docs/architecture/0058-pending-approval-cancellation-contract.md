# ADR 0058 — Pending-only requester cancellation and explicit upgrade gate

**Status: Proposed; design only, not an implemented control.**

Implementation follow-up: [ADR 0059](0059-explicit-v9-v10-simulator-upgrade.md)
implements the V10 storage/consumer and explicit V9→V10 operator upgrade
prerequisite only. The cancellation service, authenticated API and UI described
below remain proposed; this historical decision must not be read as their proof.

Subsequent [ADR 0060](0060-authenticated-pending-request-withdrawal.md) implements
the Java service and bearer/browser API slice. [ADR 0061](0061-requester-withdrawal-inbox.md)
adds local requester UI. Installed withdrawal qualification remains separate;
the original proposal below is historical.

Requirements: FR-003/007/008/009/010; SEC-001/002/003/005/006/009/012;
REL-001/002/004/005; DATA-001; OPS-002/004; PORT-001/003.

## Context

The exact-action approval workflow can request, review and explicitly execute a
simulated action, but cannot durably withdraw a pending request. Confirmation
dialog dismissal and displayed expiry are not cancellation. Treating requester
withdrawal as a reviewer denial would forge provenance or weaken separation of
duties. Treating it as a stop command would overstate control over admitted work.

The database also lacks a supported operator upgrade command. Strict installed
startup accepts V1–V9; empty-only initialization must not become an implicit
migration or an accidental replacement for recovery. A new terminal status is a
versioned storage/consumer change, not just a new HTTP suffix.

## Proposed decision

Adopt the [detailed contract](../design/pending-approval-cancellation.md) as a
review proposal, with these boundaries:

- Only the verified, enrolled requester/owner with explicit `approvals.cancel`
  may change a live `pending` request to terminal `cancelled`. The request body
  supplies only the exact request hash; trusted adapters supply identity/time.
- Use a requester-specific context and separate cancellation actor metadata.
  Never populate reviewer/decision fields or mint a permit for withdrawal.
- Serialize cancellation and review on the same tenant/request row under the
  existing recovery-first lock order. Commit transition, bounded evidence and
  outbox atomically before reporting success. Recheck hash/owner/state and
  request/authentication expiry after lock acquisition.
- Approved, denied, expired, consumed and already-cancelled requests are not
  cancellable. No worker interruption, target rollback, automatic retry,
  emergency stop, remote connector or new agent tool is implied.
- Retain current quarantine restrictions and the limitation on in-flight
  membership revocation. Cancellation is not a database-rollback witness.
- Review a versioned V10 transition/consumer update and a separate owner-only,
  offline-listener V9→V10 upgrade command before exposing cancellation. Existing
  migrations remain immutable; old binaries refuse the new guard. No automatic
  startup migration, history repair, clean/baseline or safe-downgrade claim.

The proposed operation and command do **not** exist in this ADR's change.
The existing native endpoint connector and three-tool approval MCP catalog are
unchanged. The design does not qualify a live IdP, fresh host or real target.

## Alternatives not chosen

- **Delete the request:** erases history, can free uniqueness/binding guards and
  does not explain whether a target effect was admitted.
- **Reuse `denied` and reviewer fields:** confuses requester intent with an
  independently authenticated decision and makes audit provenance misleading.
- **Cancel any state:** requires a separate decision about approved authority,
  signer/claim races, already-admitted effects and uncertain target outcomes.
- **UI-only disable:** prevents neither API review nor restart/replay behavior.
- **Add V10 under guard9 or silently auto-migrate startup:** weakens compatibility
  detection and the explicit initialization/recovery boundary of ADR0045.

## Consequences and review gate

The first useful withdrawal adds a real operator-upgrade dependency. That is
intentional: an existing installation must have a qualified path forward, not
instructions to delete or reinitialize its authority database.

Implementation is split into reviewed upgrade, durable transition/API and
React/installed qualification slices. The detailed design specifies both race
schedules, commit uncertainty, zero-effect counters, preservation/rollback
tests, error mapping and required consumer updates. They are acceptance criteria,
not test results. Merging this design must not promote implementation statuses
or advertise cancellation on the public capabilities page.

Existing constraints: [authenticated approval API](0034-authenticated-loopback-approval-api.md),
[explicit initialization](0045-explicit-database-initialization.md),
[quarantine](0051-operator-declared-recovery-quarantine.md) and
[owner inspection](0052-owner-only-recovery-inspection.md).
