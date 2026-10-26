# GitHub pilot operator diagnostic bundle

Requirements: SEC-001/002/003/007/012/015; REL-001/005/009; OPS-002/004.
See [ADR0081](../architecture/0081-github-pilot-operator-diagnostics.md).
The bundle diagnoses operator inputs and observes the exact pinned target.
Every result is explicitly NON_AUTHORITATIVE. It cannot create a PR, admit,
approve, issue operation permits, dispatch or reconcile execution.

## Install without source or build tools

Build only from a clean, committed checkout with the prepared Java21 toolchain:

```bash
bash scripts/build-pilot-operator-distribution.sh --max-workers=2
```

The result is
`java/pilot-operator/build/distributions/tracepermit-pilot-operator-0.1.0.tar.gz`
and its `.sha256` sidecar. The archive has one versioned root containing
`bin/tracepermit-pilot-operator`, allowlisted runtime `lib/*.jar`,
`docs/setup-runbook.md`, `docs/LICENSE`, `docs/NOTICE`, `manifest.json` and
`SHA256SUMS`. Verify the sidecar against an independently obtained expected
checksum, extract into an operator-owned directory and verify every SHA256SUMS
entry with standard host utilities. Dependency hashes and clean-source commit/
timestamp are in the manifest; they are not a code signature or current policy.
Keep the entire installation outside an agent-writable directory.

Installed operation needs only Java21, standard `/bin/bash` and `/bin/sleep`; set `JAVA_HOME`
to the approved Java21 installation (or select `java` on PATH). It needs no
Node, pnpm, Gradle, source checkout, Docker or runtime download. Paths with spaces
work. The primary intended operator profile is macOS; current cloud Linux
synthetic/source-free tests do not qualify a Mac installation. Validate locally
before making any Mac support claim. Windows is outside this POSIX profile.

## Private operator configuration

Independently obtain/approve all App, installation, owner and repository pins,
current exact lowercase40-character head/base SHAs and the private signing key
through the operator's normal secure process. Never obtain these from an agent
request or a claimed verified/authenticated/delegated field. No actual private
configuration, real identifiers, keys or tokens belong in Git, tickets or chat.

Create a private0700 operator directory and private0400/0600 regular files for
configuration and RSA2048..4096 PKCS#8 DER key. Files must be owned by the
current OS user, single-link and at most16KiB each. The immediate directory of
each must be0700 and owned by that user. Use absolute normalized paths without
symbolic links in any component; on macOS this includes resolving aliases such
as `/var` to its physical directory. Key conversion/custody/rotation is a separate
operator procedure. The tool never writes or displays a key or installation token.

The JSON object requires exactly these fields, with no extras or duplicates:

| Field                                                | Type and constraint                                                     |
| ---------------------------------------------------- | ----------------------------------------------------------------------- |
| `schemaVersion`                                      | Exact string `tracepermit-github-pilot-diagnostic-v1`                   |
| `appId`, `installationId`, `repositoryId`, `ownerId` | Operator-pinned integers1..9007199254740991; no numeric strings/floats  |
| `repository`                                         | Exact existing allowlist `tracepermit/agentcore-pr-pilot`               |
| `permissions`                                        | Exactly `{"contents":"read","metadata":"read","pull_requests":"write"}` |
| `privateKeyFile`                                     | Absolute normalized private RSA PKCS#8 DER file path                    |
| `head`                                               | Valid explicit branch different from `main`                             |
| `base`                                               | Exact string `main`                                                     |
| `headSha`, `baseSha`                                 | Independently pinned exact40-character lowercase hex commits            |
| `draft`                                              | Boolean `true`                                                          |

Strict UTF8 JSON only: no comments, trailing input, unknown fields, duplicate
keys, malformed Unicode or coercions. No origin, proxy, trust, clock, timeout,
token, authority, policy claim or test flag is configurable. The `pull_requests:write`
permission is inherited from the actual restricted provider contract; this tool
still makes no PR write. It does not invent a different provider profile.

Set `TRACEPERMIT_PILOT_CONFIGURATION` to the private JSON file path. This
variable carries a path, never key/token content. Do not pass secrets as argv
or environment values. Use the installed absolute executable path, quoted when
needed. Choose exactly one command; no command starts a service by default:

- `--check`: genuinely offline structural validation; no provider construction,
  DNS, network, credential minting or signing. It cannot prove live key ownership,
  installation permissions, token validity, target readiness or current policy.
- `--preflight`: explicitly mint one short-lived restricted installation token
  with the existing concrete provider, then perform exactly the existing four
  GETs for repository selection, identity and head/base commits. Production is
  fixed to GitHub HTTPS with public DNS pinning/platform TLS. Missing optional
  GitHub assurance fields are a stop, never inferred success. No retry/fallback.
- `--help`: fixed command guidance without reading private files.

## Results and failure handling

Output consists of a fixed code ending `_NON_AUTHORITATIVE` and a newline:

| Exit | Code prefix               | Meaning                                                      |
| ---- | ------------------------- | ------------------------------------------------------------ |
| 0    | `CHECK_VALID`             | Offline input structure accepted                             |
| 0    | `PREFLIGHT_OBSERVED`      | Exact read-only observations matched after cleanup           |
| 2    | `CONFIGURATION_REJECTED`  | Input/file/key/Java21 requirements not met                   |
| 3    | `PREFLIGHT_INDETERMINATE` | Credential, target, payload, expiry or cleanup refusal       |
| 4    | `DEADLINE_EXCEEDED`       | Diagnostic worker exceeded30seconds or host deadline expired |
| 5    | `CANCELLED`               | Caller interruption while awaiting the worker                |
| 64   | `USAGE_REJECTED`          | Missing/extra/unsupported command or arguments               |
| 70   | `RUNTIME_UNAVAILABLE`     | Launcher/JVM infrastructure failed                           |

The worker bounds configuration, signing, provider requests, target reads and
cleanup together. Existing provider and target sequences each retain at most
ten-second network budgets. The launcher also bounds JVM startup/runtime to35seconds plus at most one
second for forced cleanup and reaps its child. External process termination
may yield a host signal exit without a diagnostic.
Never infer target absence or authority from cancellation, timeout or missing
output. Token mint response loss/cleanup failure can leave an unused token
until expiry. There is no automatic retry. Review and resolve the fixed failure
through the secure operator process, never by weakening permissions or trust.
A point-in-time matching SHA/repository can change immediately afterward;
this is no atomic precondition, permit or permission to dispatch.

## Qualification commands and limits

All qualification must use runtime-generated test keys and synthetic provider/
numeric-loopback target fixtures. This runbook does not authorize a live call.

```bash
java/gradlew -p java :pilot-operator:test --max-workers=2 --rerun-tasks
pnpm check
pnpm format:check
pnpm docs:links
pnpm docs:flows:check
bash scripts/test-pilot-operator-distribution.sh
```

Tests prove offline zero-DNS/zero-provider behavior with an active denying DNS
SPI, strict files/JSON/Unicode, spaces/empty PATH/source-free JARs, exact target
reads/no target POST, assurance/cleanup failure, redaction, cancellation and
bounds. Run the archive checks after committing/building the package. The script checks
all hashes/inventory, extracts a temporary copy and repeats the installed process
tests with `-PpilotArchiveRoot` pointing to those exact archived bytes. Preserve all failures,
skips/cache facts and final source provenance in the PR handoff. No live
provider, installation token response compatibility, hardware key custody,
production authority, macOS host or cross-platform certification is implied.

The installed zero-DNS/zero-provider probe uses one isolated helper JAR containing
only the probe, denying DNS SPI and service descriptor. All production classes and
dependencies are loaded from the extracted distribution's `lib/*.jar`; the probe
checks the complete runtime classpath, helper inventory and exact code-source JARs
for the operator/configuration/provider classes before running the DNS control.
The former build test runtime classpath is retained only as a bounded negative
control and must be rejected as installed-byte evidence.

### Temporary-path fixture portability

Independent coordinator review on unmodified `b1a8847` found three failures in
56 configuration tests with default Mac temporary paths: the valid offline
case and both private-file mode positive controls. The Mac `/var` prefix
aliases `/private/var`; the fixture had embedded that alias in valid operator
pins. The same tree passed56/56 when the coordinator selected `/private/tmp`
only for a diagnostic JVM run. That diagnostic control is not default-Mac
qualification or an approved permanent environment workaround.

Test setup now resolves trusted temporary fixture roots before creating private
configuration/key pins. The configuration suite also resolves its setup root so
explicit negative links are tested in isolation; installed fixtures resolve
their setup root for bundle and fake-JVM paths. Preflight tests share the fixed
private-file fixture. Archive extraction paths are only trusted copy inputs,
not operator configuration/key pins, and need no production-path relaxation.
New controlled alias-prefix tests exercise both the in-process checker and the
source-free launcher: physical fixture pins pass, while explicitly aliased
configuration and key paths still fail. Existing symlink, hardlink, private-mode
and provider/DNS controls remain intact. No production validator or path input
is canonicalized; all-component no-follow enforcement remains unchanged.

Cloud Linux tests of this repair are synthetic portability regressions. They do
not replace the coordinator's default-Mac rerun of the pushed exact head or
qualify a native Mac archive, provider or target.

### Pre-existing cancellation

An already-interrupted Java caller returns the fixed cancellation diagnostic
before creating a worker/provider and preserves the interrupt flag.
`PilotPreflightTest.interruptedCallerGetsCancellationAndPreservesInterrupt`
asserts cancellation and zero provider construction. No timeout, retry or
authority rule is relaxed; see [ADR0088](../architecture/0088-selective-ci-and-complete-coverage.md).
