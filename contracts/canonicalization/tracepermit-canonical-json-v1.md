# `tracepermit-canonical-json-v1`

**Status:** Versioned canonicalization profile used by the Java POC

This profile defines the UTF-8 bytes hashed for current action and evidence
artifacts. It freezes existing POC behavior; it is not claimed to implement RFC 8785. A future standards migration requires a new profile name and an explicit
compatibility window.

## Algorithm

1. Validate and normalize the value through its versioned contract first.
2. Serialize `null` as `null`.
3. Serialize strings and booleans using JSON string/boolean syntax.
4. Reject non-finite numbers; serialize other numbers using the host JSON
   number representation demonstrated by the golden vectors.
5. Preserve array order and canonicalize each element recursively.
6. Sort object member names in ascending UTF-16 code-unit order.
7. Omit no validated field during canonicalization; optional absent fields are
   not object members.
8. Emit no insignificant whitespace.
9. Encode the canonical string as UTF-8.
10. Hash with SHA-256 and encode lowercase hexadecimal with a `sha256:` prefix.

## Compatibility rule

Signed claims identify this profile explicitly. Unknown profiles fail closed.
The Java implementation must pass every vector in
`contracts/vectors/canonicalization-v1.json`, including future Unicode, numeric,
escaping, array, and optional-field additions, before exchanging artifacts.

Because Java and JavaScript number models differ, security-sensitive contracts
should prefer bounded integers or decimal strings where exact cross-language
representation matters.

The Java helper `CanonicalJson.canonicalizeExactIntegers()` is an additive
candidate for a future versioned profile. It preserves `long` and `BigInteger`
values exactly, but it must not be used for v1 signatures or hashes. A future
profile needs its own name, portable vectors, contract versioning, and a
compatibility window before any signed artifact adopts it.
