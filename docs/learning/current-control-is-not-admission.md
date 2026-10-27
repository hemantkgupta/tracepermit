# Current policy and recovery control is not admission

A copied operator profile can prove that data matches configured pins. It cannot
prove another process has not replaced policy or quarantined the deployment.
The [writer-locked control](../architecture/0083-writer-locked-operation-authority-control.md)
adds that current-row check while retaining the lock in the caller's transaction.

The future authority owner must lock that row first, then the operation and
approval/admission records, sample fresh database time after all waits, and
repeat every independent deadline/binding/identity check before one atomic commit.
Reading controls, closing the connection and then calling another store leaves
a race; an unkeyed hash or a successful lookup does not close it. The opt-in V4
[closed review owner](../architecture/0085-closed-durable-operation-review.md)
now composes the evaluation/review/pending-withdrawal part on one connection.
It does not implement the later admission or execution attempt transaction.

New or replaced controls start inactive. Quarantine is terminal for that
generation; a new generation and explicit operator activation are required.
That protects this database's current pins, not a paused sender already outside
the transaction or an older restored backup. It is not approval, a single-use
permit, an execution attempt or permission to contact a target.
