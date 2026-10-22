# Installed Auth0 approval qualification — 2026-10-22

## Result

**PASS for the named same-Mac simulator composition only.** A source-free
TracePermit approval archive built from clean commit
`79c95770990d54d3bc01cc68b710d86794795cf5` completed native/public Auth0
browser sign-in, distinct-human review, explicit requester execution and
restart-durability checks over numeric loopback. No real AWS, Jira or other
production target was connected.

This is sanitized operator-captured evidence, not an independently signed
attestation, published binary release, penetration-test report or general
Auth0/OIDC compatibility statement.

## Exact candidate

| Item          | Observed value                                                          |
| ------------- | ----------------------------------------------------------------------- |
| Source commit | `79c95770990d54d3bc01cc68b710d86794795cf5`                              |
| Source tree   | clean                                                                   |
| Package       | `tracepermit-simulator-approval-0.1.0.tar.gz`                           |
| Size          | `8,607,369` bytes                                                       |
| SHA-256       | `2d474d6e7e228109b3459a8070c2633208d277acd36f4f0faadf87ca50cac1ff`      |
| Manifest      | schema `1.2`; package `0.1.0`; required Java `21`; macOS/Linux declared |
| Host          | macOS `27.2` (`26B5086k`), arm64                                        |
| Java          | OpenJDK `21.0.12.1`                                                     |
| Browser       | Chrome `155.0.8059.85`, with a separate private reviewer profile        |
| Package test  | `pnpm test:distribution`, 8/8 passing                                   |

Every entry in the packaged `SHA256SUMS` verified. The archive contained the
manifest, checksums, license, notice, operator runbook, built React inbox and
executable launchers. The installed `--check` and explicit first-install
`--init-db` completed before the service bound to `127.0.0.1:8085`.

## Live sequence observed

1. The native/public registration used Authorization Code with S256 PKCE,
   token endpoint authentication method `none`, exact numeric-loopback callback
   and no client secret.
2. A synthetic requester signed in through the configured Auth0 organization
   and created the fixed simulator request for `sg-demo`, port `443`, source
   CIDR `192.0.2.0/24`. The first request expired while the separate reviewer
   profile was being prepared; it remained pending and was never executed.
3. A fresh request, prefix `0e959e69`, returned `pending / not_executed`.
   A distinct enrolled synthetic reviewer signed in using a separate private
   browser profile, inspected the exact request and approved it. The requester
   then observed `approved / not_executed` and explicitly confirmed execution.
4. The terminal state was `consumed / succeeded`. All downstream effects were
   simulator-only.
5. Stopping the installed process released the loopback listener. Restarting
   the same archive invalidated the old local browser session. After fresh
   requester sign-in, PostgreSQL still reported the exact request as
   `consumed / succeeded`.

One superseded login flow failed closed at the bounded callback-binding stage;
one fresh flow then succeeded without changing validation or trust rules. No
token, authorization code, PKCE verifier, cookie, password, client secret,
private key, provider identifier or full callback URL was retained here.

## What this does not prove

- It does not qualify arbitrary Auth0 tenants, other identity providers,
  confidential clients, public ingress, remote browsers, container deployment
  or production identity operations.
- It does not establish a signed or reproducible public release. The archive
  was built locally from a recorded clean commit and is not a public download.
- This run did not repeat explicit server replay, viewer mutation denial,
  combined-role self-review denial or requester withdrawal. Those boundaries
  retain automated regression coverage; an earlier sanitized operator record
  on [the MVP acceptance board](https://github.com/hemantkgupta/tracepermit/issues/71#issuecomment-6322549688)
  reports the separately observed withdrawal and role checks.
- It does not prove a real target effect or exactly-once behavior for an
  independent external target.

Requirements exercised: FR-003/007/012; SEC-002/003/004/009/011/012/015;
REL-001/004/006; OPS-001/002/004/005.
