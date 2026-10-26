package io.tracepermit.agentcore.cli;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;
import javax.sql.DataSource;

/** Worker-only datasource; the operator cannot provide URLs, properties or alternate hosts. */
final class LoopbackSource implements DataSource {
  private final OperatorConfig.Database config;
  LoopbackSource(OperatorConfig.Database config){this.config=config;}
  @Override public Connection getConnection() throws SQLException {
    boolean pg=config.engine().equals("POSTGRESQL_16_4");
    String url=(pg?"jdbc:postgresql":"jdbc:mariadb")+"://127.0.0.1:"+config.port()+"/"+config.catalog()
        +(pg?"?connectTimeout=5&socketTimeout=10":"?connectTimeout=5000&socketTimeout=10000");
    Properties properties=new Properties();properties.setProperty("user",config.user());properties.setProperty("password",config.password());
    Connection connection=DriverManager.getConnection(url,properties);
    try {
      String expected=switch(config.engine()){case "POSTGRESQL_16_4"->"16.4";case "MARIADB_11_4_13"->"11.4.13";default->"10.6.25";};
      if(!connection.getMetaData().getDatabaseProductVersion().matches(java.util.regex.Pattern.quote(expected)+"(?:[^0-9.].*)?"))
        throw new SQLException("LOCAL_ENGINE_PROFILE_MISMATCH");
      try(var statement=connection.createStatement()){
        statement.setQueryTimeout(5);statement.execute(pg?"SET lock_timeout = '5s'":"SET SESSION innodb_lock_wait_timeout = 5");
      }
      return connection;
    }catch(SQLException|RuntimeException failure){try{connection.close();}catch(SQLException ignored){}throw new SQLException("LOCAL_STORAGE_UNAVAILABLE");}
  }
  @Override public Connection getConnection(String u,String p)throws SQLException{throw new SQLFeatureNotSupportedException();}
  @Override public PrintWriter getLogWriter(){return null;}
  @Override public void setLogWriter(PrintWriter value){}
  @Override public void setLoginTimeout(int seconds){}
  @Override public int getLoginTimeout(){return 5;}
  @Override public Logger getParentLogger()throws SQLFeatureNotSupportedException{throw new SQLFeatureNotSupportedException();}
  @Override public <T>T unwrap(Class<T> type)throws SQLException{throw new SQLFeatureNotSupportedException();}
  @Override public boolean isWrapperFor(Class<?> type){return false;}
  @Override public String toString(){return "LocalLoopbackSource[redacted]";}
}
