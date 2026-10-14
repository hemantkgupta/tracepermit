package io.tracepermit.execution;

import java.time.Instant;

public record ExecutionTransition(int sequence, String from, String to, Instant at, String reasonCode) {}
