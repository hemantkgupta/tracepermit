# GitHub operator HTTP composition qualification

Cloud1 claims sole writer ownership of
`codex/github-operator-http-composition-qualification`, starting exactly at
released PR114 `76aa4fe19b290fda7d0813aa9f65dc85d96506ac`.
This draft slice adds only new test files and this runbook.

Requirements: SEC-003/007/012/015; REL-001/005/009; OPS-004.
Scope: generated-key concrete credential provider using its real synthetic HTTP
transport, followed by the existing exact four-GET target preflight using its
real synthetic HTTP transport. Only numeric127.0.0.1 fixtures are used.
Keys, JWTs, tokens and request bodies remain in memory and never enter test
assertion output, logs, files or evidence. Configuration/CLI/archive and live
GitHub compatibility remain separate qualification levels. Final case coverage,
gates, failures/cache facts and writer release will be recorded in the draft PR.
