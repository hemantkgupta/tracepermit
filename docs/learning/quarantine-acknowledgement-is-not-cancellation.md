# Quarantine acknowledgement is not cancellation

An exact ready-to-quarantined control change can refuse later admissions.
It does not undo an admitted action or atomically stop a paused sender. Keep
each already-started attempt terminal or unknown; cleanup does not prove that
no late effect can occur.

A helper return also answers a different question from a later database read.
An acknowledged COMMIT plus cleanup can confirm that call. A quarantined row
observed after reply loss confirms only what the observer sees, not which
actor's call acknowledged success. A ready row does not prove an uncertain
write cannot arrive later.

The named operator's authority is a third boundary. A privileged connection
does not authenticate a human, and a pre-call token check does not prove what
happens after a database wait. The [org procedure contract](../design/eks-p1-quarantine-procedure-contract.md)
requires that mapping and its lifetime semantics before the drill. No new admin
command or implicit authority is created by writing the contract.

A denial probe must also reach the boundary it claims to test. The permit
issuer requires ready authority, so issuing only after quarantine tests the
issuer, not admission. Prepare the legitimate unspent permit beforehand and
call admission only after the exact stop, within the original validity bounds.
If preparation expires, record an inconclusive case; do not extend its lifetime
or invoke delivery to obtain a result.

The [PostgreSQL component cases](../runbooks/postgres-quarantine-admission-qualification.md)
make these distinctions explicit using a preissued permit, independent readback
and a real admission-only call. Missing/staged authority, expired credentials,
invalid signatures and issuer refusal can all produce a denial without proving
the intended stop. JDBC post-COMMIT/close faults are narrower than transport
loss. All new tests remain unrun; none authenticates the org administrator or
creates an installed probe.
