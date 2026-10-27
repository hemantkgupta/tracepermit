# Installed session end is not rollback

An installed listener's final `SESSION_ENDED` receipt proves that its bounded
listening window finished and the owned server was closed. It does not prove
that every accepted request completed before close, that an interrupted JDBC
call rolled back, or that no durable metadata can appear after a client timeout.

The lifecycle channel and registration channel answer different questions:

- lifecycle receipts describe listener bind and close;
- HTTP registration responses describe a specific request outcome; and
- the replay and operation databases remain the durable evidence for separately
  committing work.

A client disconnect, worker termination or closed listener cannot retract a
commit already accepted by the database. Work that ignores interruption can
retain transport capacity until its actual return. Therefore the installed
command sets `rollbackConfirmed:false`, never retries an uncertain request, and
never turns clean session shutdown into registration success or absence.

Recovery uses the existing contract: inspect durable state through approved
operator paths and, when allowed, submit an explicitly fresh verified workload
credential with the same request key. The recovered value is execution-disabled
metadata, not approval, admission or target authority.
