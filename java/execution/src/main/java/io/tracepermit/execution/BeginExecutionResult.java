package io.tracepermit.execution;

public record BeginExecutionResult(boolean started, ExecutionAttempt attempt) {}
