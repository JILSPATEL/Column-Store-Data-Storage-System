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
        java.util.List<String> history = new java.util.ArrayList<>();

        printWelcome();

        while (true) {
            // Prompt shows active database if one is selected
            String prompt = (currentDB != null) ? "CDB [" + currentDB + "] > " : "CDB > ";
            System.out.print(prompt);

            if (!scanner.hasNextLine())
                break;

            String line = scanner.nextLine().trim();
            if (line.isEmpty())
                continue;

            // Add to history
            if (history.isEmpty() || !history.get(history.size() - 1).equals(line)) {
                history.add(line);
            }

            String upperLine = line.toUpperCase();

            // ── EXIT ──────────────────────────────────────────────────────
            if (upperLine.equals("EXIT") || upperLine.equals("QUIT")) {
                break;
            }

            // ── HELP ──────────────────────────────────────────────────────
            if (upperLine.equals("HELP")) {
                printHelp();
                continue;
            }

            // ── CLEAR ─────────────────────────────────────────────────────
            if (upperLine.equals("CLEAR") || upperLine.equals("CLS")) {
                System.out.print("\033[H\033[2J");
                System.out.flush();
                continue;
            }

            // ── HISTORY ───────────────────────────────────────────────────
            if (upperLine.equals("HISTORY")) {
                System.out.println("Command History:");
                for (int i = 0; i < history.size(); i++) {
                    System.out.printf("%3d: %s\n", i + 1, history.get(i));
                }
                System.out.println("\nHint: You can re-run a command by typing its index, e.g., !5");
                continue;
            }

            // ── Re-run from History (!n) ──────────────────────────────────
            if (line.startsWith("!") && line.length() > 1) {
                try {
                    int index = Integer.parseInt(line.substring(1)) - 1;
                    if (index >= 0 && index < history.size()) {
                        line = history.get(index);
                        System.out.println("Executing: " + line);
                        upperLine = line.toUpperCase();
                        // Fall through to execute the recalled command
                    } else {
                        System.out.println("Error: History index out of range.");
                        continue;
                    }
                } catch (NumberFormatException e) {
                    // Ignore, maybe it's not a history recall
                }
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

            // ── DESCRIBE <table_name> ─────────────────────────────────────
            if (upperLine.startsWith("DESCRIBE ")) {
                if (db == null) {
                    System.out.println("No database selected.");
                } else {
                    String tableName = line.substring(9).trim();
                    System.out.println(describeTable(currentDB, tableName));
                }
                System.out.println();
                continue;
            }

            // ── DROP TABLE <table_name> ───────────────────────────────────
            if (upperLine.startsWith("DROP TABLE ")) {
                if (db == null) {
                    System.out.println("No database selected.");
                } else {
                    String tableName = line.substring(11).trim();
                    System.out.println(db.execute("DROP TABLE " + tableName));
                }
                System.out.println();
                continue;
            }

            // ── DROP DATABASE <name> ──────────────────────────────────────
            if (upperLine.startsWith("DROP DATABASE ")) {
                String dbName = line.substring(14).trim();
                File dbDir = new File(DATABASES_ROOT + "/" + dbName);
                if (!dbDir.exists()) {
                    System.out.println("Error: Database '" + dbName + "' does not exist.");
                } else {
                    if (dbName.equals(currentDB)) {
                        db = null;
                        currentDB = null;
                    }
                    deleteDirectory(dbDir);
                    System.out.println("Database '" + dbName + "' dropped successfully.");
                }
                System.out.println();
                continue;
            }

            // ── RELOAD ────────────────────────────────────────────────────
            if (upperLine.equals("RELOAD")) {
                if (db == null) {
                    System.out.println("No database selected.");
                } else {
                    db.initializeIndexes();
                    System.out.println("Indexes re-initialized successfully.");
                }
                System.out.println();
                continue;
            }

            // ── SQL commands ──────────────────────────────────────────────
            if (db == null) {
                System.out.println("No database selected. Use: USE DATABASE <name>");
                System.out.println();
                continue;
            }

            String result = db.execute(line);
            System.out.println(result);
            System.out.println();
        }
        System.out.println("Goodbye.");
    }

    private static void printWelcome() {
        System.out.println("==================================================");
        System.out.println("       Antigravity Column-Store DB (CDB)");
        System.out.println("==================================================");
        System.out.println("Type 'HELP' for a list of commands.");
        System.out.println();
    }

    private static void printHelp() {
        System.out.println("General Commands:");
        System.out.println("  SHOW DATABASES          List all databases");
        System.out.println("  CREATE DATABASE <name>  Create a new database");
        System.out.println("  DROP DATABASE <name>    Delete a database");
        System.out.println("  USE DATABASE <name>     Switch to a database");
        System.out.println("  SHOW TABLES             List tables in active database");
        System.out.println("  DESCRIBE <table_name>   Show schema of a table");
        System.out.println("  DROP TABLE <name>       Delete a table");
        System.out.println("  SHOW BITMAP INDEX <t>   Show bitmap index for a table");
        System.out.println("  RELOAD                  Re-initialize database indexes");
        System.out.println("  HISTORY                 Show command history");
        System.out.println("  !n                      Execute n-th command from history");
        System.out.println("  CLEAR                   Clear the screen");
        System.out.println("  EXIT / QUIT             Exit the client");
        System.out.println();
        System.out.println("SQL Commands:");
        System.out.println("  CREATE TABLE t (c type [PK|UNIQUE|NOT_NULL], ...)");
        System.out.println("  INSERT INTO t VALUES (v1, v2, ...)");
        System.out.println("  SELECT col1, col2 FROM t WHERE cond1 AND/OR cond2");
        System.out.println("  UPDATE t SET col = val WHERE cond");
        System.out.println("  DELETE FROM t WHERE cond");
        System.out.println();
        System.out.println("Supported Types: INT, LONG, BYTE, SHORT, FLOAT, DOUBLE, BOOLEAN, BIGDECIMAL, STRING");
    }

    private static String describeTable(String dbName, String tableName) {
        File schemaFile = new File("databases/" + dbName + "/metadata/" + tableName + ".json");
        if (!schemaFile.exists()) {
            return "Error: Table '" + tableName + "' not found.";
        }
        try {
            String json = new String(java.nio.file.Files.readAllBytes(schemaFile.toPath()));
            // Extract column info using regex (since we don't have a JSON parser)
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("\"name\"\\s*:\\s*\"(.*?)\".*?\"type\"\\s*:\\s*\"(.*?)\".*?\"constraints\"\\s*:\\s*\\[(.*?)\\]", java.util.regex.Pattern.DOTALL);
            java.util.regex.Matcher m = p.matcher(json);
            
            StringBuilder sb = new StringBuilder();
            sb.append("Table Schema for '").append(tableName).append("':\n");
            sb.append(String.format("%-20s | %-12s | %s\n", "Column", "Type", "Constraints"));
            sb.append("-".repeat(60)).append("\n");
            while (m.find()) {
                String colName = m.group(1);
                String colType = m.group(2);
                String constraints = m.group(3).replace("\"", "").replace("\n", "").trim();
                sb.append(String.format("%-20s | %-12s | [%s]\n", colName, colType, constraints));
            }
            return sb.toString();
        } catch (Exception e) {
            return "Error reading schema: " + e.getMessage();
        }
    }

    private static void deleteDirectory(File dir) {
        File[] allContents = dir.listFiles();
        if (allContents != null) {
            for (File file : allContents) {
                deleteDirectory(file);
            }
        }
        dir.delete();
    }
}
