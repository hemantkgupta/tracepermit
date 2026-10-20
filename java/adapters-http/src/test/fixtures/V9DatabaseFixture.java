import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;

/** Test setup only: genuine V1-V9 Flyway history in an owned disposable database. Never packaged. */
class V9DatabaseFixture {
  public static void main(String[] args) {
    try {
      Path config = Path.of(System.getenv("TRACEPERMIT_APPROVAL_CONFIG"));
      var pg = new ObjectMapper().readTree(Files.readString(config)).path("postgres");
      if (!pg.path("host").asText().equals("127.0.0.1")
          || !pg.path("database").asText().equals("upgrade_fixture")) throw new IllegalArgumentException();
      var source = new PGSimpleDataSource();
      source.setServerNames(new String[] {"127.0.0.1"});
      source.setPortNumbers(new int[] {pg.path("port").intValue()});
      source.setDatabaseName("upgrade_fixture");
      source.setCurrentSchema("public");
      source.setUser(pg.path("user").asText());
      source.setPassword(Files.readString(config.getParent().resolve(pg.path("passwordFile").asText())).strip());
      source.setConnectTimeout(3); source.setSocketTimeout(15);
      Flyway.configure().dataSource(source).loggers(new String[0]).target("9").load().migrate();
      System.out.println("SYNTHETIC_V9_FIXTURE_READY");
    } catch (Exception failure) {
      System.err.println("SYNTHETIC_V9_FIXTURE_FAILED"); System.exit(2);
    }
  }
}
