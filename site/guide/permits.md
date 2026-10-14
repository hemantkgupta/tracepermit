---
title: Request-bound execution permits
description: Preventing changed or replayed simulated actions between policy and dispatch.
---

# Request-bound execution permits

A time-of-check/time-of-use gap appears when the action evaluated by policy is
not the action later sent to an executor. TracePermit binds a short-lived
permit to the canonical action hash, evaluation, policy, subject, and executor
audience. The executor verifies the binding and claims the permit before
simulator dispatch. Mutation, expiry, wrong audience, and replay fail closed.

## Decision proof and permit minting

![TracePermit Decision Proof and Execution Permit](/images/02-decision-proof.png)

The frozen [permit
schema](https://github.com/hemantkgupta/tracepermit/blob/main/contracts/json-schema/execution-permit-claims-v1.schema.json)
uses fields such as `permitId`, `subjectId`, `eventId`, `evaluationId`,
`actionHash`, `decision`, `issuedAt`, `expiresAt`, and `maxUses: 1`. The Java
authority limits lifetime to 60 seconds. Some local profiles use in-memory
single-use state; the PostgreSQL-backed profile demonstrates atomic claims
across concurrent processes and restarts.

An approved action has additional exact-request approval binding. A sanitized
action receives its own hash and must pass policy again before a permit can
be issued.

These controls narrow the check/use gap for the **mediated simulator path**.
They do not prove that arbitrary real targets have exactly-once effects or
that an agent cannot bypass a future deployment's gateway. Those require
target-side credential brokerage and anti-bypass egress controls.
