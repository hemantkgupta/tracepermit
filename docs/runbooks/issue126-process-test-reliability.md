# Issue126 process-test reliability

Status: **local scoped repair and qualification in progress**. Base/main is
`3c89887f8e2398eab557091d27d97e9aa238035c`, accepted PR128. Local Mac owns
`codex/issue126-process-test-reliability`; Mac Air separately owns Track E and
the four permanent lifecycle-contract tests. No unpublished files are transferred.

This is test-source and documentation work. No production Java, CLI protocol,
identity/authority, database migration/grant/default, build, CI job or protection
rule is changed. Keep every existing test, timeout, session duration, request
count, concurrency assertion, cleanup requirement and coverage threshold active.
Issue126 remains open until the actual remaining failure/coverage gates are met.

## Preserved failures

The [PR128 merge ledger](https://github.com/hemantkgupta/tracepermit/issues/126#issuecomment-6445837272)
records source-head CI40525421560: six core failures, core/verify failed and
coverage skipped, plus the final local identity fixture4PASS/2FAIL and separate
historical failures. The three installed worker-kill cases failed worker
discovery before their termination assertions. Other failures occurred in the
durable HTTP child, approval-bootstrap startup and MariaDB11.4 replay-commit-loss
case. These are different stages; there is no demonstrated shared cause.

The earlier de7 run's nine failures and d1 run's three failures remain historical
with their exact case lists. Passing current local cases never relabels those
runs. PR127/128 merge exceptions apply to neither this branch nor a future
sandbox release.

## Evidence-supported repair

### Owned installed-worker discovery

The cached network-disabled Linux Java21 probe observed:

| Composition                                                 | Optional argument metadata      | Old expected-worker lookup | Bounded owned-child lookup             |
| ----------------------------------------------------------- | ------------------------------- | -------------------------- | -------------------------------------- |
| Short69-byte classpath probe                                | Present                         | Matched                    | Not needed                             |
| Long8196-byte classpath probe                               | Absent; kernel command8313bytes | Did not match              | Kernel record retained the actual main |
| Actual extracted source-free HTTP archive; command4130bytes | Absent                          | Did not match              | Exactly one match                      |

The actual-archive probe holds stdin incomplete: it supplies no configuration or
EOF and performs no JDBC/HTTP registration or target operation. Its owned parent,
worker and diagnostic container exit. It proves the metadata/discovery defect,
not the full HTTP lifecycle or the cause of the other CI failures. The image was
already cached; no image/dependency installation or network fallback was used.

`InstalledWorkerProcesses` accepts only a live direct child of this test's
parent, exact Java executable and exact worker/main/command/JVM option shape.
The Linux kernel record is read only if optional arguments are absent, at most
131072bytes plus an overflow check, and ownership/liveness is rechecked. No
matching by arbitrary descendant, raw class-name substring or unknown metadata.
Permanent tests cover short/long actual child JVMs, wrong-main arguments,
truncation/overflow and command-shape substitution. The original five-second
discovery deadline and all kill/outcome/count assertions are unchanged.

### Lifecycle cleanup and diagnostics

- `InstalledHttpArchive` owns the process and stderr reader before the READY
  assertion. Failed startup attempts cleanup before throwing the preserved
  assertion; validated phase/outcome make FINAL-vs-READY failures distinguishable.
- Finishing a group attempts every session and accumulates later failures.
  Session finish closes its own resources on both success and failure. Existing
  graceful/force/reader bounds remain; no process lifetime or test deadline grows.
  A new negative control initially failed because a captured worker survived its
  promptly exiting parent. Cleanup now forces and observes those captured owned
  children within the existing2s graceful plus5s force budget, preserves
  interruption, and never selects an unrelated process.
- Approval-bootstrap forced cleanup has a finite five-second observation bound
  rather than unbounded `waitFor()`, attempts every stream and restores interruption.
  Existing15s/20s/10s test assertions retain their values. Their diagnostic adds
  only a fixed marker, named phase and liveness; raw file output is never rendered.
- `RegistrationChild` preserves its30s exit,10s input,5s output/cleanup bounds and
  256-byte receipt cap. Failure reports add call ordinal, fixed mode, exit/byte
  count and closed-vocabulary stage/status. Stderr/configuration/token/body and
  exception text remain unreported. No successful receipt or retry semantics change.

The four-server startup sequence and all original14s/18s/12s/4s sessions remain
unchanged. Historical startup skew/resource-pressure hypotheses remain unproved;
this repair does not extend their lifetimes or reduce either sixteen-request race.

## Actual local qualification

| Invocation                                                              | Result                                                                                                                    | Execution limits                                                                                                                                                                                                                          |
| ----------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Initial installed-worker/start-failure/group-cleanup regressions        | 6PASS,0failure/error/skip                                                                                                 | 9s;34tasks,2executed/32up-to-date                                                                                                                                                                                                         |
| Bootstrap interruption/redaction regressions                            | 2PASS,0failure/error/skip                                                                                                 | 11s;24tasks,10executed/14up-to-date                                                                                                                                                                                                       |
| Initial complete installed HTTP module                                  | 39PASS,0failure/error/skip                                                                                                | 4m11s;34tasks,1executed/33up-to-date; before the final owned-worker cleanup strengthening                                                                                                                                                 |
| Durable HTTP child on three engines plus three approval-bootstrap cases | 6PASS,0failure/error/skip                                                                                                 | 47s;35tasks,3executed/32up-to-date                                                                                                                                                                                                        |
| Owned-worker negative control before repair                             | 1expected FAIL                                                                                                            | Worker survived its promptly exiting parent; original receipt retained                                                                                                                                                                    |
| Fresh harness regressions after that repair                             | 7PASS,0failure/error/skip                                                                                                 | 6s;34tasks,2executed/32up-to-date                                                                                                                                                                                                         |
| First complete repository execution                                     | 3701Java cases/220suites:3700PASS,0failure/error,1inherited native-PTY skip;124React and152environment scenarios PASS     | 25m;98tasks,46executed/9cache/43up-to-date; every Test task executed. Coverage gate FAILED at93.9672858%LINE/77.2705838%BRANCH. Init-script worker setting applied too late; default executor pool observed, not one-worker qualification |
| Three additional post-READY supervisor fault regressions                | 3PASS,0failure/error/skip                                                                                                 | 9s;34tasks,2executed/32up-to-date; actual startup-level one-worker setting                                                                                                                                                                |
| Final standard pnpm check                                               | PASS;3704recordedJava cases/221suites,0failure/error,1inherited native-PTY skip;124React and152environment scenarios PASS | 4m53s;99tasks,3executed/96up-to-date. Expanded installedHTTP43 executed fresh;21 unchanged Test tasks reused the earlier complete passing results                                                                                         |
| Final aggregate coverage                                                | 94.0227336%LINE,77.4026219%BRANCH;unchanged94%/75%gates PASS                                                              | Final standard aggregate uses all22current execution files; source/bytecode unchanged by the added test cases                                                                                                                             |
| Format, documentation and flow checks                                   | PASS at publication checkpoint                                                                                            | 1381Markdown links/3helpers,13flows+drift; final exact candidate receipt remains authoritative                                                                                                                                            |
| Exact published-head hosted Linux CI                                    | PENDING publication/observation                                                                                           | The cached Linux arm64 metadata probe is not hosted CI; no Linux/x86 result is inferred                                                                                                                                                   |

The added supervisor tests require bound UNKNOWN after interruption during a
SIGTERM-resistant worker's cleanup, idempotent explicit stop after READY, and
no acknowledged success after final-output loss. These exercise missing
process-failure behavior; runtime code and coverage rules are unchanged.

These are separate invocations, not combined unique-case totals. The first
pnpm preflight stopped before compilation/tests because prepared dependency
symlinks were refused. Physical copies of the same locked packages recovered
that preflight without installation or download. A diagnostic compilation typo
also ran no tests and was corrected/preserved separately.

The first full execution's failed coverage and default-worker-pool observation
remain failures/limits. A startup-level property in the task-local Gradle home
was independently checked with the actual one-worker lease message before the
final invocation. No repository, CI or shared user configuration was changed.
Final pnpm check uses normal unchanged-task reuse; cached suites are not called
fresh. Complete earlier execution data and subsequent fresh43-case C data match
the current production class bytes, and the normal aggregate independently
passes. No test was disabled, failed business case blindly retried, coverage
class excluded or threshold relaxed.

Original results, execution-file hashes, XML, archive probe and invocation
receipts remain in this lane's private local evidence folder. Public handoffs
contain exact candidate identities and bounded summaries only.

## Requirements and release limits

This adds test evidence relevant to SEC-012 diagnostic redaction and REL-002/005/009
failure/cleanup handling. It does not qualify those requirements for every
runtime/deployment and changes no trust boundary, so no new architecture decision
or authority contract is introduced. OPS-005, real-target bypass resistance,
protected credential custody and org deployment remain open acceptance work.
See the [learning note](../learning/optional-process-metadata-is-not-worker-identity.md)
and [organization sandbox acceptance plan](org-sandbox-acceptance-plan.md).

After local gates, publish a reviewable draft with exact head/tree/parents,
scope and checks. Observe automatic hosted CI honestly; final integration needs
an explicit human disposition and verified current gates. Never force push,
change rulesets, disable tests or treat earlier exceptions as a waiver.
Record resource cleanup and explicit writer release in the final handoff.
