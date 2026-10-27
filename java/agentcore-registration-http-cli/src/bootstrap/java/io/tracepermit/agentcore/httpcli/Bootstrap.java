package io.tracepermit.agentcore.httpcli;

/** Runs on older VMs so unsupported Java never loads the Java 21 implementation. */
public final class Bootstrap {
  private Bootstrap() {}

  public static void main(String[] args) {
    if (!"21".equals(System.getProperty("java.specification.version"))) {
      fail("JAVA_21_REQUIRED");
      return;
    }
    try {
      Class.forName("io.tracepermit.agentcore.httpcli.InstalledHttpRegistrationCli")
          .getMethod("main", String[].class)
          .invoke(null, (Object) args);
    } catch (Throwable failure) {
      fail("UNKNOWN");
    }
  }

  private static void fail(final String code) {
    Thread writer =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                System.out.print(code + "\n");
                System.out.flush();
              }
            },
            "registration-http-launch-diagnostic");
    writer.setDaemon(true);
    writer.start();
    try {
      writer.join(1_000);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    Runtime.getRuntime().halt(64);
  }
}
