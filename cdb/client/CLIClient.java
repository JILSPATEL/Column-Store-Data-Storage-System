package cdb.client;

import cdb.api.DatabaseAPI;

import java.util.Scanner;

public class CLIClient {
    public static void main(String[] args) {
        String dbDir = args.length > 0 ? args[0] : "cdb_data";
        System.out.println("Starting CDB Prototype using directory: " + dbDir + "...");
        DatabaseAPI db = new DatabaseAPI(dbDir);
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("CDB > ");
            if (!scanner.hasNextLine())
                break;

            String line = scanner.nextLine().trim();
            if (line.equalsIgnoreCase("EXIT") || line.equalsIgnoreCase("QUIT")) {
                break;
            }

            if (line.isEmpty()) {
                continue;
            }

            String result = db.execute(line);
            System.out.println(result);
        }
        System.out.println("Goodbye.");
    }
}
