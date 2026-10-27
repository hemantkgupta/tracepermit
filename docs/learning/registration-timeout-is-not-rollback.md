# A registration timeout is not a rollback receipt

The [loopback registration transport](../architecture/0082-loopback-agentcore-registration-http.md)
can bound network lifetime without knowing whether the database committed. If a
worker is inside JDBC when a client disconnects, interruption is a cancellation
request, not proof that the server or its transaction stopped.

The transport therefore closes the socket at its deadline, retains the running
call's capacity until actual return, and never resubmits work. Queue cancellation
prevents later authentication/registration when a lane becomes available. Bounded
close stops new traffic and waits a finite budget, while preserving uncertainty
about uncooperative in-flight calls.

A fresh verified token with the original scoped client key may recover the first
registration metadata after acknowledgement loss. Changing the key can create a
different registration; it is not a recovery mechanism. Neither metadata result
nor an HTTP201 grants approval, a permit, admission or dispatch. The response
always carries `executionEnabled:false`, and this module has no effectful port.

Synthetic local JWT/JWKS and database tests prove this closed registration path.
They do not prove AgentCore/Okta interoperability, cross-process replay security,
role revocation, current policy authority or a production TLS edge. Those require
independent contracts and evidence, not another flag in a request body.
