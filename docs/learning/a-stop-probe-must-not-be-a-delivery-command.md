# A stop probe must not be a delivery command

A useful stop check starts with otherwise-valid authority. An expired token,
invalid signature or missing control can cause a denial even when the intended
quarantine never happened. A permit prepared after quarantine may fail at the
issuer and never reach admission. Prepare the original permit while ready and
make the later boundary explicit.

Admission is already a mutation: it consumes authority and writes a claim,
facts/head and outbox. A delivery command continues beyond it. A probe must end
at admission even when the stop failed and admission unexpectedly succeeds.
That is a failed stop with durable state, not permission for a target call.

A one-shot private protocol also cannot casually become interactive. EOF before
effects and a later release frame on the same stream are incompatible. The
[proposed contract](../design/eks-p1-admission-only-stop-probe-contract.md)
recommends an explicit bounded timed hold after preparation, with one already
preauthorized admission attempt. Its tradeoff is concrete: the timer can expire
before a human sees the marker or finishes quarantine. The operator must accept
that bounded synthetic workload or choose a separately reviewed control channel.

A PREPARED marker is neither administrative authority nor evidence of remaining
permit lifetime. A refusal code alone does not distinguish quarantine from
missing/staged authority. The existing inspector itself requires ready control,
so independent post-stop observation belongs to the separately reviewed org
observer. A later database observation cannot turn lost acknowledgement into a
successful earlier call or guarantee that no late write can arrive.

This is a proposal with no installed probe. The
[manual qualification plan](../runbooks/eks-p1-admission-only-stop-probe-qualification.md)
keeps source-free framing, identity, real admission, original deadlines, owned
cleanup and independent evidence separate. No new parser, callback, authority
adapter, deployment or exactly-once claim follows from a draft or passing docs.
