package io.tracepermit.agentcore.cli;

/** Shared descriptor provenance check for closed installed commands. */
public final class InstalledPrivateInput {
  private InstalledPrivateInput() {}

  /** Privacy of the descriptor is not caller authority; both parent and worker must check it. */
  public static boolean anonymousPipe() {
    return RegistrationCli.anonymousInput();
  }
}
