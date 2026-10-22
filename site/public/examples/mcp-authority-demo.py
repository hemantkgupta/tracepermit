"""Offline teaching model: fictional tools, synthetic identities, no executor.

This is not an MCP server, credential verifier, or TracePermit policy contract.
Run with Python 3.10+; only standard-library modules are used.
"""

from dataclasses import dataclass
from enum import Enum
from typing import Any


class Decision(str, Enum):
    ALLOW = "allow"
    BLOCK = "block"
    REQUIRE_APPROVAL = "require_approval"


@dataclass(frozen=True)
class TrustedContext:
    """Synthetic fixture for facts a real adapter would have to verify."""

    subject: str
    scopes: frozenset[str]
    log_groups: frozenset[str]


# Registered locally by the application; a request cannot add another tool.
TOOLS = {
    "demo.logs.read": "demo:logs:read",
    "demo.instances.stop": "demo:instances:request-stop",
}


def evaluate(proposal: dict[str, Any], caller: TrustedContext) -> Decision:
    """Evaluate a proposal without issuing a permit or executing anything."""
    if type(proposal) is not dict or not set(proposal) <= {
        "name", "arguments", "_meta"
    }:
        return Decision.BLOCK
    name = proposal.get("name")
    arguments = proposal.get("arguments")
    if type(name) is not str or name not in TOOLS:
        return Decision.BLOCK
    if type(arguments) is not dict:
        return Decision.BLOCK
    if "_meta" in proposal and type(proposal["_meta"]) is not dict:
        return Decision.BLOCK
    # Metadata is lower-trust input and never supplies identity or approval.
    if TOOLS[name] not in caller.scopes:
        return Decision.BLOCK
    if name == "demo.instances.stop":
        if set(arguments) != {"instanceId"}:
            return Decision.BLOCK
        if arguments["instanceId"] != "i-simulated-01":
            return Decision.BLOCK
        return Decision.REQUIRE_APPROVAL
    if set(arguments) != {"logGroupName", "limit"}:
        return Decision.BLOCK
    group = arguments["logGroupName"]
    limit = arguments["limit"]
    if type(group) is not str or group not in caller.log_groups:
        return Decision.BLOCK
    if type(limit) is not int or not 1 <= limit <= 1000:
        return Decision.BLOCK
    return Decision.REQUIRE_APPROVAL if limit > 100 else Decision.ALLOW


def main() -> None:
    reader = TrustedContext(
        "user:synthetic-reader",
        frozenset({"demo:logs:read"}),
        frozenset({"/demo/staging"}),
    )
    operator = TrustedContext(
        "user:synthetic-operator",
        frozenset({"demo:instances:request-stop"}),
        frozenset(),
    )
    small_read = {
        "name": "demo.logs.read",
        "arguments": {"logGroupName": "/demo/staging", "limit": 20},
    }
    stop = {
        "name": "demo.instances.stop",
        "arguments": {"instanceId": "i-simulated-01"},
    }
    cases = [
        ("Bounded read", small_read, reader, Decision.ALLOW),
        (
            "Unknown tool",
            {"name": "demo.unregistered", "arguments": {}},
            reader,
            Decision.BLOCK,
        ),
        (
            "Resource outside grant",
            {**small_read, "arguments": {"logGroupName": "/demo/prod", "limit": 20}},
            reader,
            Decision.BLOCK,
        ),
        ("Stop without scope", stop, reader, Decision.BLOCK),
        (
            "Forged approval metadata",
            {
                **small_read,
                "arguments": {"logGroupName": "/demo/staging", "limit": 500},
                "_meta": {"approved": True, "subject": "admin", "role": "admin"},
            },
            reader,
            Decision.REQUIRE_APPROVAL,
        ),
        ("Scoped stop still needs review", stop, operator, Decision.REQUIRE_APPROVAL),
    ]
    checks = 0
    for label, proposal, caller, expected in cases:
        actual = evaluate(proposal, caller)
        if actual != expected:
            raise AssertionError(f"{label}: expected {expected}, got {actual}")
        print(f"{label}: {actual.value}")
        checks += 1

    # Additional malformed-input and authority checks, all without execution.
    for limit in (True, "20", 1.5, 0, 1001):
        proposal = {
            **small_read,
            "arguments": {"logGroupName": "/demo/staging", "limit": limit},
        }
        if evaluate(proposal, reader) != Decision.BLOCK:
            raise AssertionError("Invalid limit was accepted")
        checks += 1
    for proposal in (
        {**small_read, "subject": "admin"},
        {**small_read, "_meta": []},
        {**small_read, "arguments": {**small_read["arguments"], "approved": True}},
        {**stop, "_meta": {"scope": "demo:instances:request-stop"}},
    ):
        if evaluate(proposal, reader) != Decision.BLOCK:
            raise AssertionError("Malformed or unauthorized proposal was accepted")
        checks += 1
    print(f"PASS {checks} checks; no credentials, network, or target execution")


if __name__ == "__main__":
    main()
