# Endpoint observation capture — automated preparation qualification

Date: 2026-10-16. Tested source contents:
`087b3f238a9d6bdecdd9503ad7fe5be8373c6764`, based on merged
`5f360af28125f4fe33cc5f8ecdc1f8e1e126a69b`. This record was added after the
source commit; it does not claim a native/model rehearsal at that revision.

Environment: macOS arm64, Java 21, Node 25.6.1, pnpm 11.25.0.
Requirements: FR-016/017; SEC-003/012/014; REL-001; OPS-004.

## Checks and actual outcomes

| Check                                                                | Result                                                                                                                                | Evidence level                                                                                    |
| -------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------- |
| `pnpm check`                                                         | Passed: 456 Java tests in 62 suites, no failures/errors/skips; 12 React tests, build/typecheck, environment and coverage gates passed | Local suite; unchanged Gradle tasks reused results, not a wholly fresh/cold build                 |
| `pnpm test:endpoint-connector-e2e`                                   | Forced fresh run: eight tests, zero failures/errors/skips; all 24 selected Gradle tasks executed                                      | Actual loopback HTTP plus generated trust/credential fixtures and a synthetic process observation |
| `pnpm test:endpoint-launcher`                                        | 28 tests passed                                                                                                                       | Stub native client, not real native-process evidence                                              |
| `pnpm demo:gate-c`                                                   | All six Java presenters passed                                                                                                        | Fixed simulator scenarios, not model-selected calls                                               |
| `pnpm demo:verify`                                                   | Syntax, cleanup/shutdown, launcher and runner tests passed                                                                            | Automated script and fixture-drift checks                                                         |
| Formatting, documentation links, flow synchronization and site build | Passed                                                                                                                                | Static/documentation verification                                                                 |

Fresh HTTP suite timestamp: `2026-10-16T05:15:10.204Z`
(10:45:10.204 IST). The verifier fixture itself uses a fixed test clock;
these timestamps are not measured native process facts.

## Four-case dispatch proof

`fourSourceProofRejectionsCannotDispatchAnOtherwiseAllowedRead` uses two
different, valid synthetic CLI credentials, runtime-generated Ed25519 keys and
fresh proofs for the independent mutation/credential tests. It asserts:

1. Missing proof: 401 / `MISSING_SOURCE_ATTESTATION`; dispatch count 0.
2. Proof bound to another valid credential: 403 / `INVALID_SOURCE_ATTESTATION`;
   dispatch count 0.
3. Changed read limit with an original-request proof: 403 /
   `INVALID_SOURCE_ATTESTATION`; dispatch count 0 and gateway ledger empty.
4. Exact valid read: 200 / simulated `executed`; dispatch count 1.
5. Replay that accepted proof: 409 / `SOURCE_ATTESTATION_REPLAYED`; dispatch
   count stays 1 and the gateway ledger does not grow.

The accepted control distinguishes source-verifier rejection from an already
denied action. The unchanged ledger is not a separate audit stream for the
pre-policy failures. These are HTTP verifier tests with fixture observations,
not a native client's or a model's attempts to tamper with source evidence.

## Capture and privacy assertions

Nine recorder tests and three added connector-composition tests check private
unique files, exact allowlisted fields, request/hash agreement with the signed
proof, ordering before forwarding, default-disabled bootstrap, explicit invalid
configuration, mode/link/replacement/growth failures, independent record/byte
bounds and closed capture. Failed stability produces no capture or forward;
requested-capture failure produces no forward. Raw token/proof values are
compared only in memory for absence from serialized capture.

No actual native process capture, model prompt, personal login, cloud target
call, global client configuration change or real target credential was used.
The ordinary demo was not started or restarted. Existing dated native
allow/block evidence remains unchanged; Gate B is still partial.

Next: use the [one-read native rehearsal](../../runbooks/native-endpoint-observation-rehearsal.md)
to retain actual measured facts and independently correlate a new gateway
result. Capture records mean stability checked **before forwarding**, not
gateway acceptance, authorization, execution or immutable evidence. See
[ADR 0038](../../architecture/0038-private-endpoint-observation-capture.md).
