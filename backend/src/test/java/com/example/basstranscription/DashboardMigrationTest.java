package com.example.basstranscription;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="RUN_DASHBOARD_MIGRATION_TESTS",matches="true")
class DashboardMigrationTest {
 @Test void freshAndLegacySchemasMigrateWithoutLosingJobs() throws Exception {
  String url=System.getenv().getOrDefault("DB_URL","jdbc:postgresql://localhost:5432/bass_transcription");
  String user=System.getenv().getOrDefault("DB_USER","bass_app");
  String password=System.getenv("DB_PASSWORD");
  for(boolean legacy:new boolean[]{false,true}) {
   String schema="dashboard_test_"+UUID.randomUUID().toString().replace("-","");
   try(Connection db=DriverManager.getConnection(url,user,password); Statement statement=db.createStatement()) {
    statement.execute("create schema "+schema);
    try {
     statement.execute("set search_path to "+schema);
     if(legacy) {
      statement.execute("create table jobs (id uuid primary key,status varchar(32) not null,input_path varchar(255) not null,"+
       "result_path varchar(255),error_message varchar(2000),size_bytes bigint,created_at timestamptz not null,updated_at timestamptz not null,user_file_name varchar(255))");
      for(String name:new String[]{"Song","Song","song",null,"C:\\music\\Bäss"}) {
       try(var insert=db.prepareStatement("insert into jobs(id,status,input_path,created_at,updated_at,user_file_name) values (?,'COMPLETED','inputs/test.wav',now(),now(),?)")) {
        insert.setObject(1,UUID.randomUUID()); insert.setString(2,name); insert.executeUpdate();
       }
      }
     }
     var flyway=Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema)
      .baselineOnMigrate(true).baselineVersion("0").load();
     flyway.migrate();
     try(var rows=statement.executeQuery("select count(*),count(distinct lower(download_name)),count(download_name) from jobs")) {
      rows.next(); assertEquals(legacy ? 5 : 0,rows.getInt(1));
      assertEquals(rows.getInt(1),rows.getInt(2)); assertEquals(rows.getInt(1),rows.getInt(3));
     }
     assertEquals(0,flyway.migrate().migrationsExecuted);
    } finally { statement.execute("drop schema "+schema+" cascade"); }
   }
  }
 }
}
