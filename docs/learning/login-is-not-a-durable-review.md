# Login is not a durable review

A valid human access token identifies an enrolled reviewer and limits what that
identity may request. It does not specify a decision or prove that a durable
approval committed. TracePermit keeps identity-only login separate from the
explicit operation-review launcher.

The reviewer must see an exact pending operation and confirm its decision,
UUID and snapshot hash. These are intent and state pins, not identity assertions.
The operation can change or become quarantined while the person is reading it.
The transaction therefore checks the same snapshot and current authority after
its locks, using database time and the original deadlines.

A successful login without exact confirmation writes no decision. A timeout or
lost acknowledgement after submission can mean UNKNOWN rather than rollback;
automatic retry would be unsafe. Even an acknowledged approval is not execution:
a separate requester, original permit and controlled delivery path are required.
The new helper contains no signer or target port. All new qualification remains
pending until its human-run receipts are reviewed.

A schema-validation error can also represent an unreachable database. After
submitting the new helper's review, this ambiguous observation stays UNKNOWN;
it does not prove a schema defect or grant another attempt. The same prepared
plan refuses resubmission even with a fresh valid reviewer token.
