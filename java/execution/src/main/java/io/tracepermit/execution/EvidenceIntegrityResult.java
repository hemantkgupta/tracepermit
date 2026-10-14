package io.tracepermit.execution;

public record EvidenceIntegrityResult(boolean valid, int eventCount, String headHash, boolean anchored, int issueIndex, String issueCode) {}
