# An organization synthetic receipt is not GitHub delivery

The organization profile deliberately makes its target identity unusually
specific: App, installation, owner, repository, refs, SHAs and minimum
permissions are all pinned. That specificity prevents substitution, but it does
not supply a credential or contact GitHub.

`DELIVERY_SUCCEEDED` from the installed operator means the accepted durable
authority path reached the fixed in-memory `SyntheticGitHubDeliveryTarget` after
`dispatch_started` committed and its connection cleanup acknowledged. The same
receipt says `executionEnabled:false`. It proves ordering and fail-closed
composition, not that a pull request exists.

`DELIVERY_UNKNOWN` is also a known synthetic result, not retry authority.
Generic `UNKNOWN` is stricter: the command itself cannot establish whether a
durable boundary completed. In both cases, a new permit, attempt or delivery can
duplicate an external effect. The only supported follow-up is fresh-owner,
read-only reconciliation, which cannot invoke a target.

A live GitHub stage would require separately approved provider credential
custody, exact organization inputs, authenticated ingress/sender binding, EKS
containment and target-specific reconciliation. None may be inferred from a
local archive, a signed Okta fixture token or a green synthetic receipt.
