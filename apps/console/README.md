# React console status

**Status: active product UI.**

The default TypeScript/React console consumes the loopback-only Java read API
and has no approval authority. The separate `/approvals` entry renders a
role-scoped simulator approval inbox served by the optional Java browser
adapter, not the default Vite proxy. Tokens stay server-side; UI capabilities
never replace API role/scope/ownership checks. It can request, review and
execute only the fixed simulator action through the durable approval service.

Run `pnpm demo:approvals:browser` from the repository root for a disposable
synthetic IdP/PostgreSQL browser qualification. See the
[browser runbook](../../docs/runbooks/browser-approval-inbox.md). This does not
qualify a real provider or make the default read API remotely authenticated.
