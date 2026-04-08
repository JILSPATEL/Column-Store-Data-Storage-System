package cdb.client;

import cdb.api.DatabaseAPI;

import java.io.File;
import java.util.Scanner;

public class CLIClient {

    private static final String DATABASES_ROOT = "databases";

    public static void main(String[] args) {
        // Ensure the root databases folder exists
        File root = new File(DATABASES_ROOT);
        if (!root.exists())
            root.mkdirs();

        DatabaseAPI db = null;
        String currentDB = null;
        Scanner scanner = new Scanner(System.in);

        while (true) {
            // Prompt shows active database if one is selected
            String prompt = (currentDB != null) ? "CDB [" + currentDB + "] > " : "CDB > ";
            System.out.print(prompt);

            if (!scanner.hasNextLine())
                break;

            String line = scanner.nextLine().trim();
            if (line.isEmpty())
                continue;

            // ── EXIT ──────────────────────────────────────────────────────
            if (line.equalsIgnoreCase("EXIT") || line.equalsIgnoreCase("QUIT")) {
                break;
            }

            // ── SHOW DATABASES ────────────────────────────────────────────
            if (line.equalsIgnoreCase("SHOW DATABASES")) {
                File[] dirs = root.listFiles(File::isDirectory);
                if (dirs == null || dirs.length == 0) {
                    System.out.println("No databases found.");
                    // System.out.println("Hint: use CREATE DATABASE <name> to create one.");
                } else {
                    System.out.println("+--------------------------+");
                    System.out.println("| Databases                |");
                    System.out.println("+--------------------------+");
                    for (File dir : dirs) {
                        String marker = dir.getName().equals(currentDB) ? "  <- active" : "";
                        System.out.printf("| %-24s|%s%n", dir.getName(), marker);
                    }
                    System.out.println("+--------------------------+");
                }
                System.out.println();
                continue;
            }

            // ── SHOW TABLES ───────────────────────────────────────────────
            if (line.equalsIgnoreCase("SHOW TABLES")) {
                if (currentDB == null) {
                    System.out.println("No database selected. Use: USE DATABASE <name>");
                } else {
                    File tablesDir = new File(DATABASES_ROOT + "/" + currentDB + "/tables");
                    File[] tables = tablesDir.exists() ? tablesDir.listFiles(File::isDirectory) : null;
                    if (tables == null || tables.length == 0) {
                        System.out.println("No tables found in database '" + currentDB + "'.");
                    } else {
                        System.out.println("+--------------------------+");
                        System.out.println("| Tables                   |");
                        System.out.println("+--------------------------+");
                        for (File t : tables) {
                            System.out.printf("| %-24s|%n", t.getName());
                        }
                        System.out.println("+--------------------------+");
                    }
                }
                System.out.println();
                continue;
            }

            // ── SHOW BITMAP INDEX <name> ──────────────────────────────────
            if (line.toUpperCase().startsWith("SHOW BITMAP INDEX")) {
                if (db == null) {
                    System.out.println("No database selected. Use: USE DATABASE <name>");
                } else {
                    String tableName = line.substring("SHOW BITMAP INDEX".length()).trim();
                    if (tableName.isEmpty()) {
                        System.out.println("Error: Please provide a table name. Example: SHOW BITMAP INDEX <tableName>");
                    } else {
                        System.out.println(db.dumpIndex(tableName));
                    }
                }
                System.out.println();
                continue;
            }

            // ── CREATE DATABASE <name> ────────────────────────────────────
            if (line.toUpperCase().startsWith("CREATE DATABASE ")) {
                String dbName = line.substring("CREATE DATABASE ".length()).trim();
                if (dbName.isEmpty()) {
                    System.out.println("Error: Please provide a database name.");
                    System.out.println();
                    continue;
                }
                File dbDir = new File(DATABASES_ROOT + "/" + dbName);
                if (dbDir.exists()) {
                    System.out.println("Database '" + dbName + "' already exists.");
                } else {
                    dbDir.mkdirs();
                    System.out.println("Database '" + dbName + "' created successfully.");
                    // System.out.println("Hint: type USE DATABASE " + dbName + " to start using
                    // it.");
                }
                System.out.println();
                continue;
            }

            // ── USE DATABASE <name> ───────────────────────────────────────
            if (line.toUpperCase().startsWith("USE DATABASE ")) {
                String dbName = line.substring("USE DATABASE ".length()).trim();
                if (dbName.isEmpty()) {
                    System.out.println("Error: Please provide a database name.");
                    System.out.println();
                    continue;
                }
                File dbDir = new File(DATABASES_ROOT + "/" + dbName);
                if (!dbDir.exists()) {
                    System.out.println("Error: Database '" + dbName + "' does not exist.");
                    // System.out.println("Hint: CREATE DATABASE " + dbName);
                } else {
                    db = new DatabaseAPI(DATABASES_ROOT + "/" + dbName);
                    currentDB = dbName;
                    System.out.println("Switched to database '" + dbName + "'.");
                }
                System.out.println();
                continue;
            }

            // ── SQL commands ──────────────────────────────────────────────
            if (db == null) {
                System.out.println("No database selected.");
                // System.out.println("Hint: SHOW DATABASES or USE DATABASE <name>");
                System.out.println();
                continue;
            }

            String result = db.execute(line);
            System.out.println(result);
        }
        System.out.println("Goodbye.");
    }
}
