# Offline approval review checker

The Java `review-verifier` module checks the closed v1 unsigned simulator
approval-review export defined by ADR0064. It is a local, read-only mechanical
checker for requirements FR-007/010/012, SEC-003/005/009/012, DATA-001/003 and
REL-002. It does not authenticate an author, authorize execution or contact a
TracePermit service.

## Build and run

Use a Java 21 JDK to create the source-free Gradle application distribution:

```bash
source scripts/configure-java-environment.sh
java/gradlew -p java :review-verifier:installDist
java/review-verifier/build/install/review-verifier/bin/review-verifier \
  /private/path/to/tracepermit-approval-review.json
```

The installed launcher needs Java 21 on `PATH` (or `JAVA_HOME`). It does not
need Gradle, Node, pnpm, a database, network access, environment credentials or
the source tree. Supply exactly one local regular JSON file. Standard input,
URLs, directories, symlinks and recursive scanning are unsupported. The checker
reads at most 32,768 bytes and strictly decodes UTF-8.

On success it prints exactly:

```text
STRUCTURE_AND_CHECKSUM_VALID UNSIGNED SIMULATOR_ONLY NOT_AUTHORITY
```

That result means the document has the closed schema/version/kind/profile,
simulator modes, six false assurance flags, bounded v1 values and a
`CanonicalJson.sha256(payload)` matching `payloadHash`. Object-key order and
insignificant JSON whitespace do not affect that checksum.

## Fixed outcomes

Failures print only a fixed category, never the supplied path, document,
identifier, parser message or exception:

| Exit | Category              | Meaning                                                 |
| ---: | --------------------- | ------------------------------------------------------- |
|    0 | success wording above | Structure and checksum mechanically match               |
|    2 | `USAGE_ERROR`         | Exactly one file argument was not supplied              |
|    3 | `UNSUPPORTED_INPUT`   | Input is absent, inaccessible, non-regular or a symlink |
|    4 | `INPUT_TOO_LARGE`     | The bounded read observed more than 32,768 bytes        |
|    5 | `MALFORMED_DOCUMENT`  | UTF-8 or bounded strict JSON parsing failed             |
|    6 | `CONTRACT_INVALID`    | The closed v1 structure or value constraints failed     |
|    7 | `CHECKSUM_MISMATCH`   | The well-formed checksum differs from the payload       |
|    8 | `CHECK_FAILED`        | A safe, non-diagnostic internal failure occurred        |

## Trust boundary

The checksum is unkeyed. Anyone able to modify a payload can recompute it, and
the checker will still return the explicit `UNSIGNED SIMULATOR_ONLY
NOT_AUTHORITY` limitations. A matching checksum proves none of the following:

- authorship or possession of a TracePermit trust key;
- binding to the original canonical action or request arguments;
- complete history or a transactional database snapshot;
- an independent timestamp or external anchor;
- a real target effect, approval authority or execution authority.

A true assurance flag is rejected even when its checksum is recomputed. The
export can contain correlatable pseudonymous identifiers and policy metadata;
keep downloaded copies private. Do not use this checker as an authorization
gate, compliance certificate or retry signal. The required browser CI gate
downloads through the real Java route and passes that saved file to this
installed launcher with a credential-free environment. Live-provider and
fresh-host qualification remain separate reviewed gates.
