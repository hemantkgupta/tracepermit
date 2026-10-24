# ADR 0076 — Bounded GitHub App installation credential provider

Status: implemented candidate; synthetic qualification only, unwired.
Requirements: FR-001/008/009/012; SEC-001/002/003/012/015/019;
REL-001/003/005/009; OPS-002/004. No whole-requirement promotion.

## Decision and authority

`GitHubInstallationCredentialProvider` implements the unchanged
`GitHubAppCredentialProvider` port in the new `github.credentials` subpackage.
It is not wired into a runtime, action route, operation bridge or executor.
Credential possession does not approve a request, consume a permit or dispatch
a target action. Existing adapter/preflight/action code is unchanged.

The trusted operator supplies an immutable App ID, installation ID, repository
ID, owner ID, the existing exact repository allowlist and an exact permission
profile: `contents:read`, `metadata:read`, `pull_requests:write`. IDs retain the
existing safe-integer ceiling. Extra permissions, other repositories, missing
identity or arbitrary request-body authority are rejected. Only organization
installations are in this foundation profile.

The public production factory additionally requires an explicit absolute path
to a private POSIX regular file containing RSA PKCS#8 DER. It does not search
the environment, home directory, repository or credential stores. Group/other
permissions, final-component symlinks, changed file identity/size, empty/oversized/malformed
encoding and RSA strength outside 2048..4096 bits fail closed. The source reads
at most16KiB plus one overflow byte and writes no copy. Production never creates
or installs a key. PEM/PKCS#1, encrypted keys, non-POSIX custody and rotation are
outside this source profile. Java key objects/strings are not a secure-erasure
guarantee; local-file operations require a trusted local operator filesystem and protected parent directories.

Each invocation signs RS256 with numeric App ID as textual `iss`, `iat=now-60`
and `exp=now+540` seconds (600-second span). GitHub permits App ID or client ID
and recommends client ID; choosing supported numeric App ID is explicit here.
The source/clock/signing boundaries contain every throwable and preserve direct
interruption. A backwards clock within an invocation or expired App JWT prevents the next exchange.
Local key-source/signing execution is not a universal blocking-I/O deadline.

## Restricted exchange and proof limits

Each call, with no cache or background refresh, performs exactly:

1. App-JWT-authenticated GET of the configured installation. Require exact
   typed installation/App/target/account identities, organization/account name,
   unsuspended state and exact permission profile.
2. One App-JWT-authenticated POST to that installation's access-token endpoint.
   Supply exactly one `repository_ids` value and the exact permission object.
   Require explicit selected-repository assurance, exactly one complete pinned
   private/non-fork/enabled/unarchived repository/owner and the exact permission
   response. Only then return the opaque token with its SHA-256 fingerprint.

The token must be bounded ASCII compatible with the existing port, not assumed
to be40 characters and not decoded as a self-authorizing JWT. Expiry must be an
explicit valid instant, strictly after the current clock and no more than one
hour ahead. It is checked again after cleanup before returning credentials.
Strict clock alignment is required; a mismatch fails rather than extending TTL.

GitHub's token schema requires only token/expiry; permissions/repositories and
selection are optional. This deliberately narrower profile rejects missing or
ambiguous assurance instead of treating request parameters as confirmation.
There is no fallback to a broader token, inferred permission, different endpoint
or changed preflight. Live response compatibility is unqualified.

The opaque token response does **not** independently expose/attest App and
installation identity. Provenance relies on GitHub's authenticated TLS/JWT
installation GET and documented installation-specific minting contract, not on
token introspection or a local assertion alone. No independent cryptographic
token-binding proof is claimed. Existing target preflight remains mandatory;
this library does not replace it or eliminate installation/repository races.

## Transport, redaction and lifecycle

Public callers cannot choose host, trust, resolver, clock or test transport.
Production uses only GitHub's fixed HTTPS origin, normal OkHttp platform TLS,
the existing `BoundedHostResolver.resolvePublic` classification/pinning, no
ambient proxy, redirects or connection retry, and the existing REST API version.
A one-exchange tag refuses HTTP-library follow-ups before another network
request. A single monotonic budget of at most10 seconds includes DNS and the
complete GET/POST response bodies. Each decoded body is bounded at64KiB plus
one overflow byte; strict JSON rejects duplicate keys, trailing documents and
non-object roots. Credentials and reply representations are redacted.

The existing target transport is package-private in another package. The new
private exchange mirrors its bounded OkHttp setup and uses the shared public
DNS resolver without widening that API or adding dependencies. Synthetic
numeric-loopback HTTP injection is package-private and clearly named; it is
not a production environment flag or localhost override.

Owned response/session cleanup is attempted on normal and error paths;
the executor has a separate fixed one-second cleanup join. Failure of cleanup
prevents returning credentials. No automatic retry or revocation occurs: a
failed response/cleanup can leave an unused issued token until expiry. Secret
material is never written by the provider, logged or included in exception
messages, causes, suppression or public representations. Fixed exception codes
are closed; caller attempts to add a cause/suppressed secret cannot attach it.

## Qualification

Runtime-generated RSA keys, private temporary DER sources, numeric-loopback
MockWebServer and package-local failing callbacks cover exact signed exchange,
identity/permission/repository/time/JSON/status/byte failures, no follow-up,
concurrency isolation, key/sign/transport/cleanup failures and nested/suppressed
sentinel redaction. Retain positive controls and existing GitHub/SSRF/TLS suites.
The [runbook](../runbooks/github-app-installation-credentials.md) records exact
commands, official sources, results and untested boundaries. This is not live
GitHub installation, target execution, durable operation composition,
exactly-once behavior, production custody or release readiness.
