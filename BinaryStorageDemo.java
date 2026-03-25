import cdb.api.DatabaseAPI;

/**
 * BinaryStorageDemo
 * -----------------
 * Seeds the "databases/companydb" database with three realistic tables and
 * demonstrates INSERT, SELECT, UPDATE, and DELETE operations - all persisted in
 * binary .bin column files by BinaryStorageEngine.
 *
 * Run from the project root:
 *   javac -cp . BinaryStorageDemo.java
 *   java  -cp . BinaryStorageDemo
 */
public class BinaryStorageDemo {

    private static final String DB_PATH = "databases/companydb";

    public static void main(String[] args) {

        DatabaseAPI db = new DatabaseAPI(DB_PATH);

        banner("TABLE: employees (BYTE | SHORT | INT | LONG)");
        exec(db, "CREATE TABLE employees (id INT PRIMARY_KEY, dept_id BYTE, office_room SHORT, salary INT, phone_number LONG)");

        // Insert employee records
        exec(db, "INSERT INTO employees VALUES (1, 10, 101, 85000, 1234567890)");
        exec(db, "INSERT INTO employees VALUES (2, 20, 205, 95000, 1987654321)");
        exec(db, "INSERT INTO employees VALUES (3, 10, 102, 60000, 1122334455)");
        exec(db, "INSERT INTO employees VALUES (4, 30, 301, 120000, 1555666777)");
        exec(db, "INSERT INTO employees VALUES (5, 40, 404, 75000, 1999888777)");

        exec(db, "SELECT id, dept_id, office_room, salary, phone_number FROM employees");

        System.out.println("  [UPDATE salary=90000 WHERE id=3]");
        exec(db, "UPDATE employees SET salary = 90000 WHERE id = 3");
        exec(db, "SELECT id, dept_id, office_room, salary, phone_number FROM employees");

        System.out.println("  [DELETE WHERE id=5]");
        exec(db, "DELETE FROM employees WHERE id = 5");
        exec(db, "SELECT id, dept_id, office_room, salary, phone_number FROM employees");

        // -----------------------------------------------------------------------
        banner("TABLE: finances (FLOAT | DOUBLE | BIGDECIMAL)");
        exec(db, "CREATE TABLE finances (id INT PRIMARY_KEY, tax_rate FLOAT, bonus_multiplier DOUBLE, annual_revenue BIGDECIMAL)");

        exec(db, "INSERT INTO finances VALUES (1, 0.15, 1.05, 1500000.50)");
        exec(db, "INSERT INTO finances VALUES (2, 0.20, 1.10, 2500000.75)");
        exec(db, "INSERT INTO finances VALUES (3, 0.10, 1.02, 500000.00)");
        exec(db, "INSERT INTO finances VALUES (4, 0.25, 1.15, 5500000.25)");
        exec(db, "INSERT INTO finances VALUES (5, 0.30, 1.20, 10000000.99)");

        exec(db, "SELECT id, tax_rate, bonus_multiplier, annual_revenue FROM finances");

        System.out.println("  [UPDATE bonus_multiplier=1.25 WHERE id=2]");
        exec(db, "UPDATE finances SET bonus_multiplier = 1.25 WHERE id = 2");
        exec(db, "SELECT id, tax_rate, bonus_multiplier, annual_revenue FROM finances");

        System.out.println("  [DELETE WHERE id=3]");
        exec(db, "DELETE FROM finances WHERE id = 3");
        exec(db, "SELECT id, tax_rate, bonus_multiplier, annual_revenue FROM finances");

        // -----------------------------------------------------------------------
        banner("TABLE: projects (INT | LONG | DOUBLE | BOOLEAN)");
        exec(db, "CREATE TABLE projects (id INT PRIMARY_KEY, budget DOUBLE, team_size LONG, is_active BOOLEAN)");

        exec(db, "INSERT INTO projects VALUES (1, 50000.0, 5, true)");
        exec(db, "INSERT INTO projects VALUES (2, 120000.5, 12, true)");
        exec(db, "INSERT INTO projects VALUES (3, 15000.0, 3, false)");
        exec(db, "INSERT INTO projects VALUES (4, 300000.0, 25, true)");
        exec(db, "INSERT INTO projects VALUES (5, 8000.0, 2, false)");

        exec(db, "SELECT id, budget, team_size, is_active FROM projects");

        System.out.println("  [UPDATE is_active=true WHERE id=3]");
        exec(db, "UPDATE projects SET is_active = true WHERE id = 3");
        exec(db, "SELECT id, budget, team_size, is_active FROM projects");

        System.out.println("  [DELETE WHERE id=4]");
        exec(db, "DELETE FROM projects WHERE id = 4");
        exec(db, "SELECT id, budget, team_size, is_active FROM projects");

        System.out.println();
        System.out.println("===================================================");
        System.out.println("  Demo complete. Binary .bin files are in:");
        System.out.println("  " + DB_PATH + "/tables/");
        System.out.println("===================================================");
    }

    private static void exec(DatabaseAPI db, String query) {
        System.out.println("  > " + query);
        System.out.println("  " + db.execute(query));
        System.out.println();
    }

    private static void banner(String title) {
        String line = "=".repeat(52);
        System.out.println();
        System.out.println("  " + line);
        System.out.println("  " + title);
        System.out.println("  " + line);
        System.out.println();
    }
}
