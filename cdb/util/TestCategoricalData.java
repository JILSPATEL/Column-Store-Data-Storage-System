package cdb.util;

import cdb.api.DatabaseAPI;
import java.io.File;

public class TestCategoricalData {
    public static void main(String[] args) {
        String testDbDir = "databases/test_categorical_db";
        // Clean up previous test run
        deleteDirectory(new File(testDbDir));

        DatabaseAPI api = new DatabaseAPI(testDbDir);

        System.out.println("--- Creating Table ---");
        System.out.println(api.execute("CREATE TABLE users (id INT PRIMARY_KEY, name STRING, dept STRING, salary DOUBLE)"));

        System.out.println("\n--- Inserting Data ---");
        System.out.println(api.execute("INSERT INTO users VALUES (1, 'Alice', 'Engineering', 80000.0)"));
        System.out.println(api.execute("INSERT INTO users VALUES (2, 'Bob', 'HR', 50000.0)"));
        System.out.println(api.execute("INSERT INTO users VALUES (3, 'Charlie', 'Engineering', 90000.0)"));
        System.out.println(api.execute("INSERT INTO users VALUES (4, 'David', 'Finance', 75000.0)"));
        System.out.println(api.execute("INSERT INTO users VALUES (5, 'Eve', 'HR', 55000.0)"));

        System.out.println("\n--- Select All ---");
        System.out.println(api.execute("SELECT * FROM users"));

        System.out.println("\n--- Select with Categorical Filter (STRING) ---");
        System.out.println(api.execute("SELECT name, salary FROM users WHERE dept = 'Engineering'"));

        System.out.println("\n--- Select with Complex AND Filter (Numerical + Categorical) ---");
        System.out.println(api.execute("SELECT * FROM users WHERE dept = 'HR' AND salary > 52000"));

        System.out.println("\n--- Select with Complex OR Filter ---");
        System.out.println(api.execute("SELECT name FROM users WHERE dept = 'Finance' OR name = 'Alice'"));

        System.out.println("\n--- Updating Categorical Data ---");
        System.out.println(api.execute("UPDATE users SET dept = 'Management' WHERE name = 'Charlie'"));
        System.out.println(api.execute("SELECT * FROM users WHERE name = 'Charlie'"));

        System.out.println("\n--- Deleting with Categorical Condition ---");
        System.out.println(api.execute("DELETE FROM users WHERE dept = 'HR'"));
        System.out.println(api.execute("SELECT * FROM users"));

        System.out.println("\n--- Final Verification ---");
        System.out.println(api.execute("SELECT * FROM users"));
    }

    private static void deleteDirectory(File directoryToBeDeleted) {
        File[] allContents = directoryToBeDeleted.listFiles();
        if (allContents != null) {
            for (File file : allContents) {
                deleteDirectory(file);
            }
        }
        directoryToBeDeleted.delete();
    }
}
