# Domain and data model

**Status:** Target model; current contracts implement a subset  
**Applies to:** Java backend and versioned language-neutral contracts

## 1. Domain language

| Term               | Meaning                                                            |
| ------------------ | ------------------------------------------------------------------ |
| Principal          | Human or workload identity                                         |
| Subject            | Principal whose authority is evaluated                             |
| Actor              | Immediate agent, service, orchestrator, or tool acting             |
| Trigger            | Human, service, or scheduled cause                                 |
| Action event       | Canonical observed request before classification and decision      |
| Business operation | Durable business intent spanning requests and lifecycle dimensions |
| Identity evidence  | Verification metadata and non-secret credential fingerprint        |
| Source attestation | Short-lived signed endpoint observations bound to one request      |
| Source evidence    | Safe verified device/process/trigger projection on an action       |
| Workload identity  | Scheduler-signed principal/run/action binding                      |
| Lineage hop        | Causal observation linking components in a request path            |
| Delegation grant   | Signed, verifiable, parent-linked authority transfer               |
| Classification     | Derived action class, severity, operation, and risk signals        |
| Evaluation         | Policy decision for one action event and policy version            |
| Approval request   | Pending request for a human decision on an exact action hash       |
| Approval decision  | Approver's allow or deny attestation                               |
| Execution permit   | Short-lived capability for one subject, action, and executor       |
| Execution attempt  | One claimed attempt to invoke a target                             |
| Evidence event     | Append-only lifecycle fact                                         |
| Reconciliation     | Later determination of an initially uncertain outcome              |

V10 storage additionally recognizes terminal `cancelled` and separate requester
cancellation provenance on pending→cancelled transitions, never reviewer fields.
The storage prerequisite is
[ADR 0059](../architecture/0059-explicit-v9-v10-simulator-upgrade.md).
[ADR 0060](../architecture/0060-authenticated-pending-request-withdrawal.md)
adds authenticated pending-only withdrawal with version3 bounded requester
lifecycle evidence and atomic outbox. It is not reviewer denial, approved-authority
revocation or interruption of a target operation. The React mutation is separate.

## 2. Aggregate boundaries

### Action aggregate

Owns the immutable canonical action, identity evidence references, lineage,
classification, and evaluations. The original normalized event is never edited
to reflect later decisions.

### Business-operation aggregate

Owns a server operation ID, trusted tenant/workspace/requester context, client
request-key fingerprint, immutable normalized action and hash, semantic payload
digest, resource/precondition binding, policy/artifact releases, recovery
generation, retention policy and independent authorization/execution/evidence
projections. Its append-only transitions are globally ordered and versioned per
dimension. Transport request, event, operation, semantic, resource and target
idempotency identities are deliberately distinct.

The implemented v1 contract and Java validator are a persistence-neutral
foundation only. They do not provide repository uniqueness, access control,
encryption, retention deletion, resume/cancel APIs, target dispatch or
reconciliation. See [ADR 0068](../architecture/0068-versioned-business-operation-contract.md).

### Approval aggregate

Owns approval request state, reviewer identity, decision, expiry, request hash,
and consumption. Approval cannot mutate the action it references.

### Execution aggregate

Owns permit metadata, atomic consumption, attempts, idempotency reference,
observed target outcome, and reconciliation.

### Policy aggregate

Owns immutable policy versions and activation state. Evaluations always record
the exact version used.

## 3. Target entity relationship model

```mermaid
erDiagram
  TENANT ||--o{ ACTION_EVENT : owns
  TENANT ||--o{ BUSINESS_OPERATION : owns
  TENANT ||--o{ POLICY : owns
  BUSINESS_OPERATION ||--|| ACTION_EVENT : binds
  BUSINESS_OPERATION ||--o{ EXECUTION_ATTEMPT : attempts
  BUSINESS_OPERATION ||--o{ EVIDENCE_EVENT : records
  POLICY ||--|{ POLICY_VERSION : versions
  ACTION_EVENT ||--|{ IDENTITY_EVIDENCE : supports
  ACTION_EVENT ||--o| SOURCE_EVIDENCE : observed_from
  ACTION_EVENT ||--|{ LINEAGE_HOP : contains
  ACTION_EVENT ||--o{ DELEGATION_GRANT : carries
  ACTION_EVENT ||--o{ EVALUATION : evaluated_by
  POLICY_VERSION ||--o{ EVALUATION : produces
  EVALUATION ||--o| APPROVAL_REQUEST : may_require
  APPROVAL_REQUEST ||--o{ APPROVAL_DECISION : receives
  EVALUATION ||--o| EXECUTION_PERMIT : authorizes
  APPROVAL_DECISION ||--o| EXECUTION_PERMIT : may_authorize
  EXECUTION_PERMIT ||--o| PERMIT_CONSUMPTION : consumed_as
  ACTION_EVENT ||--o{ EXECUTION_ATTEMPT : attempts
  EXECUTION_PERMIT ||--o{ EXECUTION_ATTEMPT : permits
  EXECUTION_ATTEMPT ||--o{ EVIDENCE_EVENT : records
  ACTION_EVENT ||--o{ EVIDENCE_EVENT : correlates
  EXECUTION_ATTEMPT ||--o{ RECONCILIATION : reconciled_by

  TENANT {
    uuid tenant_id PK
    string name
    string status
  }
  BUSINESS_OPERATION {
    uuid operation_id PK
    uuid tenant_id FK
    string workspace_id
    string requester_principal_id
    string client_request_key_hash
    string semantic_payload_digest
    string resource_binding_hash
    string target_idempotency_key_hash
    bigint recovery_generation
    string authorization_status
    string execution_status
    string evidence_status
  }
  ACTION_EVENT {
    uuid event_id PK
    uuid tenant_id FK
    string schema_version
    string trace_id
    string subject_id
    string action_hash
    json canonical_action
    timestamp observed_at
  }
  IDENTITY_EVIDENCE {
    uuid evidence_id PK
    uuid event_id FK
    string subject_id
    string kind
    string assurance
    string issuer
    string credential_fingerprint
    timestamp verified_at
  }
  SOURCE_EVIDENCE {
    uuid event_id PK
    string issuer
    string key_id
    string key_fingerprint
    string device_id
    string executable_hash
    string requested_mode
    timestamp verified_at
  }
  LINEAGE_HOP {
    string hop_id PK
    uuid event_id FK
    string parent_hop_id
    string kind
    string subject_id
    string assurance
  }
  DELEGATION_GRANT {
    string grant_id PK
    uuid event_id FK
    string parent_grant_id
    string subject_id
    string actor_id
    string audience
    json constraints
    timestamp expires_at
  }
  POLICY {
    uuid policy_id PK
    uuid tenant_id FK
    string name
  }
  POLICY_VERSION {
    uuid policy_version_id PK
    uuid policy_id FK
    int version
    string content_hash
    json document
    string state
  }
  EVALUATION {
    uuid evaluation_id PK
    uuid event_id FK
    uuid policy_version_id FK
    string outcome
    string reason_code
    json classification
    timestamp evaluated_at
  }
  APPROVAL_REQUEST {
    uuid approval_request_id PK
    uuid evaluation_id FK
    string request_hash
    string action_hash
    string actor_lineage_hash
    string resource_hash
    string status
    timestamp expires_at
    int max_uses
  }
  APPROVAL_DECISION {
    uuid approval_decision_id PK
    uuid approval_request_id FK
    string reviewer_subject_id
    string decision
    string signature_ref
    timestamp decided_at
  }
  EXECUTION_PERMIT {
    uuid permit_id PK
    uuid evaluation_id FK
    string approval_decision_id FK
    string audience
    string action_hash
    string signing_key_id
    timestamp expires_at
  }
  PERMIT_CONSUMPTION {
    uuid permit_id PK
    string state
    timestamp claimed_at
    string instance_id
  }
  EXECUTION_ATTEMPT {
    uuid attempt_id PK
    uuid event_id FK
    uuid permit_id FK
    string idempotency_key
    string adapter_id
    string status
    timestamp started_at
    timestamp finished_at
  }
  EVIDENCE_EVENT {
    uuid evidence_event_id PK
    uuid event_id FK
    uuid attempt_id FK
    string event_type
    string payload_hash
    string previous_hash
    timestamp recorded_at
  }
  RECONCILIATION {
    uuid reconciliation_id PK
    uuid attempt_id FK
    string result
    string evidence_ref
    timestamp reconciled_at
  }
```

## 4. Lifecycle state machines

### Evaluation and approval

```mermaid
stateDiagram-v2
  [*] --> Evaluated
  Evaluated --> Denied: block
  Evaluated --> PermitReady: allow
  Evaluated --> ApprovalPending: require_approval
  Evaluated --> Sanitizing: sanitize
  Sanitizing --> ReEvaluated: derived canonical action
  Sanitizing --> NotExecuted: unavailable/error
  ReEvaluated --> PermitReady: explicit allow
  ReEvaluated --> NotExecuted: block/approval/sanitize
  ApprovalPending --> ApprovalDenied: deny/expire
  ApprovalPending --> ApprovalApproved: authorized approve
  ApprovalApproved --> PermitReady: atomic consume + execution claim
  ApprovalApproved --> ApprovalDenied: expire before use
  PermitReady --> [*]
  Denied --> [*]
  ApprovalDenied --> [*]
  NotExecuted --> [*]
```

The original event remains immutable. A sanitizer produces a second event with
a distinct event ID and canonical hash. Policy evaluation, permit binding, and
idempotency use that effective event; bounded evidence links the two hashes and
records the allowlisted transformer without persisting raw arguments.

### Execution

```mermaid
stateDiagram-v2
  [*] --> Issued
  Issued --> Expired: lifetime elapsed
  Issued --> Claimed: atomic consumption
  Claimed --> Executing
  Executing --> Succeeded: confirmed target result
  Executing --> Failed: confirmed no/failed effect
  Executing --> Unknown: outcome not provable
  Unknown --> Succeeded: reconciliation proves success
  Unknown --> Failed: reconciliation proves failure
```

State transitions append evidence. Reconciliation does not rewrite the earlier
unknown event.

## 5. Data handling rules

- Canonical actions are immutable after acceptance.
- Large or sensitive arguments may be stored as redacted data plus a content
  hash and protected external reference.
- Tokens and reusable secrets are never stored.
- Permit and approval records retain IDs, hashes, key IDs, claims required for
  audit, and signatures only when safe and required.
- Tenant ID participates in every storage key and authorization decision.
- Retention and deletion must preserve legally required audit integrity while
  honoring tenant and privacy policy.

## 6. Java mapping guidance

Use immutable records/value objects for canonical events and signed claims.
Keep domain state transitions behind aggregate methods and repositories. JSON
transport objects must not double as trusted domain objects; adapters validate
and map them explicitly.

## 7. Implemented durable Java subset

The durable execution implementation Java simulator profile now persists tenant-scoped permit
consumption, execution attempts, monotonic transition history, ordered evidence
events, stream heads, and evidence outbox identities. The trusted tenant is an
application context value, not a field accepted from an arbitrary action body.

The persistence projection deliberately does not store canonical action JSON or
target results. It retains the action/idempotency hashes and bounded correlation,
policy, status, reason, timestamp, and credential-fingerprint fields required
for enforcement and evidence.

The request-bound approval implementation implements the approval subset in PostgreSQL V2: an immutable
`ApprovalBinding`, current aggregate state, and append-only transitions for
`pending`, `approved`, `denied`, `expired`, and `consumed`. The consumed
transition links to the permit and attempt under the same tenant. Reviewer
provenance stores bounded identity/authentication fields and a credential
fingerprint, never a reviewer token. Approval consumption and the durable execution implementation
claim rows commit together; the original request and transition history are
not overwritten. Policy-version documents, retention, outbox publication, and
external checkpoints remain target entities.

The hardened remote-edge implementation adds Flyway V3 only to align durable subject identifiers with the
existing issuer-qualified OAuth form (`issuer#subject`). The `#` delimiter is
accepted for subject and reviewer-subject domain values and the two persisted
subject columns; permit, attempt, event, policy, and other identifier grammars
remain unchanged. The remote adapter supplies the tenant as separate trusted
application context before this persistence boundary.

The evidence-publication implementation adds Flyway V4 publication state to `tp_evidence_outbox` and the
append-only `tp_evidence_anchors` relation. An outbox row may be unclaimed,
leased, delayed for retry, or published. Publication attempts increment without
changing the evidence event. A receipt binds one tenant/outbox/event/stream
position and payload hash and can never be updated or deleted. The stable
outbox ID is the external idempotency identity; the database does not claim
that an arbitrary sink provides exactly-once delivery.

The safe-reconciliation implementation adds `tp_reconciliation_queue`, created in the same transaction as an
`unknown` transition. The queue stores the original tenant/attempt/adapter/
idempotency binding, retry time, lookup count, and lease. It is deleted only in
the transaction that appends `unknown -> succeeded|failed`; an unconfirmed
lookup changes queue scheduling but not execution history.
