package io.agenteval.operations;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;

/** Short-lived deployment commands; never starts Spring or a scheduling loop. */
public final class DeploymentOperations {
    private DeploymentOperations() {}
    public static int migrate(String url,String user,String password) {
        return Flyway.configure().dataSource(url,user,password).load().migrate().migrationsExecuted;
    }
    public static boolean healthy(String url,String user,String password) {
        try(var connection=DriverManager.getConnection(url,user,password)) { return connection.isValid(3); }
        catch(java.sql.SQLException unavailable) { return false; }
    }
    public static void main(String[] args) {
        String url=required("DATABASE_URL"),user=required("DATABASE_USER"),password=required("DATABASE_PASSWORD");
        if(args.length==1 && args[0].equals("migrate")) { migrate(url,user,password); return; }
        if(args.length==1 && args[0].equals("health")) { if(!healthy(url,user,password)) System.exit(1); return; }
        throw new IllegalArgumentException("Expected migrate or health");
    }
    private static String required(String name) {
        var value=System.getenv(name); if(value==null || value.isBlank()) throw new IllegalArgumentException("Missing deployment configuration: "+name); return value;
    }
}
