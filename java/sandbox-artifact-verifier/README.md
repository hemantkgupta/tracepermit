# Offline sandbox artifact and evidence verifier (candidate)

This module is an unwired Java 21 checker. It reads local files only, never
extracts or runs an archive and does not contact DNS, networks, databases,
providers, clusters, signing services, or credential stores. An independently
supplied digest identifies the bytes read; it does not authenticate the source.
All qualification statements are supplied assertions, not independently
verified test execution, provenance, approval, deployment acceptance or
security qualification. No approval, permit or execution authority is created.

## Closed command and result

After the proposed root integration is reviewed and applied, invoke the main
class with exactly ten arguments, in this order:

`--archive PATH --sha256 64-lowercase-hex --commit 40-lowercase-hex --tree 40-lowercase-hex --evidence PATH`

The archive and evidence must each be an existing regular non-symlink file.
The expected commit, tree and digest are independently supplied operator inputs;
the tool does not look up Git objects. Files, byte contents, credential-like
values, paths and exception messages are never echoed. Exactly one JSON object
is written to stdout with fields `schemaVersion`, `status`, `archive`, `record`,
`reason`, `trust`; no stderr details are emitted. Values are fixed vocabulary:

- `schemaVersion`: `1`; `trust`: `SUPPLIED_ASSERTIONS_ONLY`.
- `status`: `COMPLETE_AS_SUPPLIED`, `INCOMPLETE`, or `REJECTED`.
- `archive`: `MATCH`, `REJECTED`, or `NOT_CHECKED`.
- `record`: `COMPLETE_AS_SUPPLIED`, `INCOMPLETE`, `REJECTED`, or `NOT_CHECKED`.
- `reason`: `NONE`, `INVALID_ARGUMENTS`, `ARCHIVE_REJECTED`, `DIGEST_MISMATCH`,
  `EVIDENCE_REJECTED`, or `EVIDENCE_INCOMPLETE`.

Exit code 0 means only `COMPLETE_AS_SUPPLIED`; 2 denotes malformed arguments,
3 rejects archive or digest, 4 rejects malformed evidence, and 5 means the
validly shaped evidence is incomplete. I/O failures use the relevant fixed
rejection. No status is named `ready`.

## Supported archive and limits

Only uncompressed POSIX ustar with `ustar\0`/`00` header signatures and octal
numeric fields is accepted; GNU, PAX, compressed tar, ZIP, ZIP64, extensions,
sparse entries, links, special files, encryption and other formats are rejected.
No archive-provided path is opened on the host. At most 4,096 entries, 32 MiB
per entry, 128 MiB total declared regular-file bytes, 256 MiB of archive bytes,
and 512 path characters are accepted. Archives must contain at least one file,
two zero end blocks, only zero full-sized trailing blocks, valid checksums,
zero payload padding, and complete headers/data; no extra nonzero trailer is
accepted. File/directory names are restricted to ASCII letters, digits,
`._+@=-` and `/`, with no empty, `.` or `..` components, leading slash,
backslash, repeated slash or regular-file trailing slash. Directory trailing
slash normalizes away. Duplicate normalized paths and file/ancestor conflicts
are rejected. Header link names and device numbers must be empty/zero. The
reader and digest consume the same bounded stream exactly once; a digest
mismatch rejects the record. These strict restrictions deliberately exclude
many valid archives rather than guessing about type or ambiguity. They attest
only to the bytes read during this check; an archive may change afterwards.
Use an operator-controlled immutable snapshot when a later consumer needs the
same bytes. This verifier cannot establish filesystem immutability or provenance.

## Evidence JSON version 1

The evidence file is UTF-8 JSON, at most 262,144 bytes, with strict duplicate
field detection, one root object, no trailing tokens and no unknown fields.
Every object uses exactly the fields below; missing required evidence is
`INCOMPLETE`, incorrect types, unknown fields and duplicate fields are rejected.
Text fields use fixed vocabulary and integers are nonnegative and at most
1,000,000,000. The candidate values must exactly equal the independent inputs.

```
{
  "schemaVersion": "1",
  "candidate": { "commit": "<40 hex>", "tree": "<40 hex>", "archiveSha256": "<64 hex>" },
  "gates": [
    { "name": "focused", "outcome": "PASS", "freshness": "FRESH", "executed": 1, "failed": 0, "skipped": 0 },
    { "name": "repository", "outcome": "PASS", "freshness": "FRESH", "executed": 1, "failed": 0, "skipped": 0 },
    { "name": "format", "outcome": "PASS", "freshness": "FRESH", "executed": 0, "failed": 0, "skipped": 0 },
    { "name": "docs-links", "outcome": "PASS", "freshness": "FRESH", "executed": 1, "failed": 0, "skipped": 0 },
    { "name": "docs-flows", "outcome": "PASS", "freshness": "FRESH", "executed": 1, "failed": 0, "skipped": 0 }
  ],
  "coverage": {
    "scope": "ALL_REQUIRED_MODULES", "freshness": "FRESH", "complete": true,
    "line": { "covered": 94, "missed": 6 },
    "branch": { "covered": 75, "missed": 25 }
  },
  "provenance": { "status": "PRESENT_AS_SUPPLIED", "referenceSha256": "<64 hex>" },
  "cleanup": "CONFIRMED_AS_SUPPLIED"
}
```

The five named gates must each occur exactly once. Outcomes are `PASS`, `FAIL`,
`SKIPPED`, or `UNRUN`; freshness is `FRESH`, `CACHED`, or `UNKNOWN`. A pass
with failed/skipped counters, or an unrun/skipped result with nonzero executed
or failed counters, is inconsistent and rejected. Focused, repository, docs-links
and docs-flows require at least one executed case. Missing gates, non-PASS,
non-FRESH, nonzero skipped/failed counts or absent required evidence are
incomplete. Coverage must include nonzero integer denominators and meet LINE
94% and BRANCH 75% by exact integer arithmetic; a percentage or a bare
`complete` assertion is not accepted without both counter pairs, scope and
freshness. Coverage and gate facts remain assertions supplied by the record.
Coverage scope may also be `PARTIAL` or `UNKNOWN`; `complete` may be false.
Provenance status accepts `PRESENT_AS_SUPPLIED`, `MISSING`, `UNKNOWN` and requires
a supplied 64-hex reference digest; the reference itself is not retrieved or
authenticated. Cleanup accepts
`CONFIRMED_AS_SUPPLIED`, `UNCERTAIN`, `FAILED`, `UNRUN`. Anything but the first
of each is incomplete. A matching digest and complete record prove no trust in
the identity of the submitter or actual tests.

## Owned files and integration

The implementation, tests, this design/runbook, the integration proposal and
the manual validation runner live under this directory. The authorized proposal
was integrated locally at commit `07b6cfdec578391d59f106a3381147eea2eb2e16`;
its zero-context patch remains for review and requires `git apply
--unidiff-zero` if applied to another matching checkout. The focused verifier
and full Java checks passed at that commit, but the final diff check failed on
whitespace inside the stored patch. These results do not qualify another tree.
