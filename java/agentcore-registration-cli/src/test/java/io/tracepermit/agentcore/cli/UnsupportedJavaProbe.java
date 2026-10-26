package io.tracepermit.agentcore.cli;

/** Test-only exercise of the real bootstrap's rejection branch; this is not another installed VM. */
public final class UnsupportedJavaProbe {
  private UnsupportedJavaProbe() {}
  public static void main(String[] args) throws Exception {
    System.setProperty("java.specification.version", "17");
    var urls = new java.util.ArrayList<java.net.URL>();
    try (var jars = java.nio.file.Files.list(java.nio.file.Path.of(args[0]))) {
      for (var jar : jars.filter(p -> p.toString().endsWith(".jar")).toList()) urls.add(jar.toUri().toURL());
    }
    try (var loader = new java.net.URLClassLoader(urls.toArray(java.net.URL[]::new), ClassLoader.getPlatformClassLoader())) {
      Class.forName("io.tracepermit.agentcore.cli.Bootstrap", true, loader)
          .getMethod("main", String[].class).invoke(null, (Object)new String[]{"check-config"});
    }
  }
}
