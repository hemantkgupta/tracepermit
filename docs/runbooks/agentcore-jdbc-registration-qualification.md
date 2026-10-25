# AgentCore ingress and JDBC registration qualification

Cloud2 claims sole-writer ownership of `codex/agentcore-jdbc-registration-qualification`
until a final pushed TRACEPERMIT HANDOFF releases it. This draft is stacked on
read-only PR109 at `bc2486a18558dc14998db840187024fd2a2faac9`.

Scope: new test-source composition of the existing strict token verifier,
operator enrollment, AgentCore registration ingress and real JDBC repository on
PostgreSQL16.4/MariaDB11.4.13/MariaDB10.6.25, narrow test-only dependencies and
scoped evidence. No runtime, migration, authority, credential, launcher, CI or
provider behavior changes. PR109 and all other producer branches stay untouched.

Startup: prepared tracepermit-java21-docker verified, clean exact starting head,
restricted networking and private subtree exclusion retained; two preflights and
nine tooling tests passed. No application/database tests have run for this new
qualification yet. Existing receipts are historical.

Contract limit from ADR0070/0078: same-key changed target/resource or generation
conflicts; policy/artifact/retention release proposals may differ and return the
original registration. A broader all-profile-change conflict is unsupported.
Tests must preserve this distinction. Registration remains metadata only;
provider interoperability, shared durable replay and role revocation remain
unqualified. No live provider/target action or automatic merge is authorized.
