# ADR 0031: Keep permissive remote-foundation defaults on loopback

- **Status:** Accepted for the simulator-only POC
- **Date:** 2026-10-14
- **Requirements:** SEC-016, SEC-017, SEC-020, REL-001

## Context

The remote MCP adapter has a convenience factory used by foundation tests.
It supplies allow-all limiter ports, always-ready state, discarded telemetry,
and no mandatory mTLS sender constraint. The hardened factory requires those
dependencies explicitly. The convenience factory was public and accepted an
arbitrary bind address, so future composition code could accidentally expose
development defaults outside loopback.

## Decision

Rename the convenience factory `startForLoopbackTests`, make it package-private,
and reject unresolved or non-loopback bind addresses before creating a server.
Keep `startHardened` as the explicit remote composition API. Add regression
tests for wildcard and unresolved addresses.

This change does not make the hardened factory a production deployment: its
rate state, readiness dependencies, telemetry sink, certificate lifecycle,
and anti-bypass controls must be provided and qualified separately.

## Consequences

- Foundation tests can still exercise a real loopback TLS server.
- The permissive path cannot be called from another Java package or bound to a
  public interface by mistake.
- Production composition remains an explicit, reviewed security boundary.
