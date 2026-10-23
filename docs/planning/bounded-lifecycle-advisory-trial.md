# Observed lifecycle-proposal advisory trial

Source baseline: `b84b5ee904a20348e169f32b8b453e397c9b0935` (merged PR #98).
This is a development-work receipt, not a product feature or audit certification.

## Actual loop used

1. Primary coordinator freezes a small public-text plan and the existing v1 state graph.
2. One Flash test-advice request and one GLM adversarial-advice request run in separate
   empty temporary workspaces, without a product checkout or private customer input.
3. Accept only bounded closed-schema JSON as advisory data. Never execute model
   output, apply its code, accept its test claims or retry a failed response automatically.
4. Primary coordinator implements the Java slice and deterministic tests, runs the
   required gates, pushes a checkpoint/PR and releases ownership. No model merges.

The controller used existing subscription routes only: Antigravity account auth
with credit overages disabled, and the existing Z.ai Coding Plan route through
Claude Code. Unrelated API/cloud credential environment variables were not
forwarded. No paid API fallback, permission bypass or automatic model repair
was enabled. Billing and upstream model identity are not independently attested.

## Results, not assumed success

| Worker                                     | Task                      | Result                                                                    |
| ------------------------------------------ | ------------------------- | ------------------------------------------------------------------------- |
| Requested Gemini Flash through Antigravity | Negative-test advice      | Closed-schema advice accepted                                             |
| Requested GLM-5.3 through Claude Code/Z.ai | Adversarial design advice | Response failed schema validation; rejected, no retry, no accepted review |

Flash's reported usage was 9,563 input / 1,608 output / 1,193 thinking / 11,171
total tokens. These are provider counters, not a price estimate or independent
billing receipt. GLM usage was not accepted/reported by this failed response path.

Accepted suggestions became tests for stale token components, wrong scope,
unknown/terminal edges, backward time and malformed reasons, snapshot overflow,
immutable fields and defensive copies. The coordinator also tests that two
proposals are not an atomic winner and that registration rejects progressed
proposals on all three engines. Advice was independently checked against source;
it is not a review of the final implementation.

## Deliberate limits

The local controller/helper is not a transferable, version-controlled product
runner. Its qualification checks included 14 existing helper tests and three
advice-schema tests. GLM ran with native tools/MCP disabled. Flash used plan mode
and the CLI's terminal sandbox with a no-tools instruction; filesystem/network
containment was not independently proven. Neither worker was given the product
checkout, but that is not an operating-system isolation guarantee.

This is the first observed advisory batch, **not** an unattended coding loop.
The controller attempted each worker once; native CLI/provider internal retries
and retained state were not independently attested. Three primary-run stale-state
regressions failed when their guard was deliberately removed; it was restored
before final verification. The workers neither ran nor certified those tests.
Before an overnight writer batch, version/control and independently review the
runner, qualify negative permission probes, enforce exclusive branch leases and
path allowlists, bound repair/no-progress limits, and prove cleanup/cancellation.
Stop on quota/authentication/policy/time/output failure; never switch to billed
credentials or retry indefinitely. Customer planning files and private values
must remain outside prompts, Git, receipts and PR text.
