# Security policy

TracePermit is a simulator-only proof of concept. It is not a supported
production gateway and must not be placed inline with real enterprise target
credentials or relied upon as the sole control for destructive actions.

## Private reporting

Use [GitHub private vulnerability reporting](https://github.com/hemantkgupta/tracepermit/security/advisories/new)
for a suspected vulnerability. Do not open a public issue or pull request with
exploit details, credentials, or personal data. A useful report includes the
affected commit, trust boundary, minimal simulated reproduction, expected and
observed behavior, and whether any side effect could occur before policy.

The maintainer will triage privately, reproduce against a simulator, and
coordinate a fix and disclosure. No response or remediation SLA is promised
for this POC. Please do not test against real AWS, Jira, another person's
account, or the public documentation site without authorization.

## Supported scope

Only the current public `main` source is maintained. Historical private
development snapshots are not supported.
Security fixes should include a regression test and preserve fail-closed
behavior; see [CONTRIBUTING.md](CONTRIBUTING.md).
