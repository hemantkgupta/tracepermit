package io.tracepermit.application;

public record EvidencePublicationResult(int claimed, int published, int failed) {
  public EvidencePublicationResult {
    if (claimed < 0 || published < 0 || failed < 0 || published + failed != claimed) {
      throw new IllegalArgumentException("The evidence publication result is inconsistent.");
    }
  }
}
