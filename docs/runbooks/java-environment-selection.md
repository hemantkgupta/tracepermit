# Java environment selection

The Bash setup helper selects an existing Java21 JDK for repository developer
commands. It does not install or download a toolchain. An explicit
`TRACEPERMIT_JAVA_HOME` takes precedence; invalid explicit selections must fail
without falling back to another JDK.

Cloud1 owns the isolated `codex/java-environment-failure-status` repair branch
from main `0bc6b618aa9eac7df3bacf7a6d4bcafe2a824c4f`. This tooling-only slice
preserves the stopped audit checkout and does not change any Java runtime,
database, schema, dependency or CI configuration. Qualification follows in this
runbook before handoff.
