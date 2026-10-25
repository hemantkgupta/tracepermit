# Java environment selection

The Bash setup helper selects an existing Java21 JDK for repository developer
commands. It does not install or download a toolchain. An explicit
`TRACEPERMIT_JAVA_HOME` takes precedence; invalid explicit selections must fail
without falling back to another JDK.

Both executable `bin/java` and `bin/javac` must successfully report major21.
A compiler-only directory, JRE-only directory, failed version command or
compiler21/runtime25 pair is rejected. The helper consumes version output
internally and emits fixed diagnostics without rendering environment values.

Selection order remains:

1. Nonempty `TRACEPERMIT_JAVA_HOME`, with no fallback if invalid.
2. A valid existing `JAVA_HOME`.
3. The existing repository-relative managed JDK location.
4. The existing macOS Java21 selector, when applicable.
5. Executable `java` and `javac` on PATH, both major21. This fallback unsets
   `JAVA_HOME` so Gradle uses the selected PATH pair.

An invalid inherited `JAVA_HOME` can still fall through to the existing lower
priority candidates. An explicit valid selector takes precedence over it.
Existing task-specific cache selectors, trust-store and proxy handling remain
unchanged. No credentials or proxy values are needed in a bug report.

## Status and callers

Cleanup removes the helper functions while preserving configuration's exit
status. Before the repair, an invalid explicit JDK printed rejection but
`unset -f` replaced failure with status0 when the caller did not exit early.
Now plain sourcing, conditional sourcing and direct Bash execution return
failure consistently, with or without caller `errexit`.

```bash
source scripts/configure-java-environment.sh && java -version
```

Callers without `errexit` must still check status or use `if`/`&&` before
starting a Java command. Executing the setup script in a child Bash checks its
status; it cannot export configuration into its parent shell.

## Focused qualification

With an existing Java21 JDK selected, run:

```bash
pnpm java:env-check
```

The shell suite first checks the actual selected JDK and task-specific cache
locations. It then uses disposable fake compiler/runtime pairs, including a
path with spaces. Its152 scenarios cover the eight invalid pairs/selectors
through explicit and PATH selection, three valid selection paths, and each
combination of plain source/`if`/`&&`/direct execution with/without `errexit`.
It also checks explicit valid precedence over invalid inherited `JAVA_HOME`,
cleanup and failure before cache creation. Invalid explicit cases retain a
valid inherited and PATH JDK as no-fallback controls.

The relocated setup fixture and minimal utility PATH prevent a real managed
or macOS-discovered JDK from masking negative PATH cases. A fixed fake `uname`
selects that deterministic fallback path; it is not a native macOS discovery
test. A harmless Gradle sentinel detects accidental continuation; no real
Gradle command runs through a negative fixture. Captured fixture output is
temporary and is not rendered on failures.

The final repaired suite passed152 scenarios plus native setup and explicit
precedence on Linux Bash. Running the same new suite against an isolated copy
of the original setup script must fail, distinguishing the regression. The
implementation uses Bash3.2-compatible constructs; native macOS/Bash3.2
execution remains a separate qualification boundary. This tooling change
does not alter Java/Gradle versions, dependency or CI configuration, or the
warning-as-error gates. Full repository and exact-head hosted results are
recorded in the PR handoff separately.
