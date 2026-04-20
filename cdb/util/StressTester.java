package cdb.util;

import cdb.api.DatabaseAPI;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class StressTester {
    private static final String STRESS_DB = "databases/stress_test_db";
    private static DatabaseAPI api;

    public static void main(String[] args) {
        setup();
        List<TestResult> results = new ArrayList<>();

        results.add(testIllegalNumericValues());
        results.add(testNumericOverflow());
        results.add(testConstraintViolations());
        results.add(testMalformedQueries());
        results.add(testNonExistentEntities());
        results.add(testLargeStringValues());
        results.add(testEmptyValues());
        results.add(testBulkOperations());

        printReport(results);
    }

    private static void setup() {
        deleteDirectory(new File(STRESS_DB));
        api = new DatabaseAPI(STRESS_DB);
        api.execute("CREATE TABLE stress_table (id INT PRIMARY_KEY, age BYTE, score FLOAT, name STRING NOT_NULL, city STRING UNIQUE)");
    }

    private static TestResult testIllegalNumericValues() {
        System.out.println("Running: Illegal Numeric Values");
        String res = api.execute("INSERT INTO stress_table VALUES (1, 'abc', 95.5, 'Alice', 'NY')");
        return new TestResult("Illegal Numeric Insert", res.startsWith("Error"), res);
    }

    private static TestResult testNumericOverflow() {
        System.out.println("Running: Numeric Overflow");
        String res = api.execute("INSERT INTO stress_table VALUES (2, 200, 95.5, 'Bob', 'LA')");
        return new TestResult("Numeric Overflow (BYTE)", res.startsWith("Error"), res);
    }

    private static TestResult testConstraintViolations() {
        System.out.println("Running: Constraint Violations");
        api.execute("INSERT INTO stress_table VALUES (10, 25, 80.0, 'Charlie', 'Chicago')");
        
        String resPK = api.execute("INSERT INTO stress_table VALUES (10, 30, 70.0, 'Duplicate', 'Miami')");
        String resUnique = api.execute("INSERT INTO stress_table VALUES (11, 30, 70.0, 'UniqueTest', 'Chicago')");
        String resNotNull = api.execute("INSERT INTO stress_table VALUES (12, 30, 70.0, null, 'Seattle')");
        
        boolean ok = resPK.contains("Constraint violation") && resUnique.contains("Constraint violation") && resNotNull.contains("cannot be null");
        return new TestResult("Constraint Violations", ok, "PK: " + resPK + " | Unique: " + resUnique + " | NotNull: " + resNotNull);
    }

    private static TestResult testMalformedQueries() {
        System.out.println("Running: Malformed Queries");
        String res1 = api.execute("SELECT * FROM");
        String res2 = api.execute("INSERT INTO stress_table (1, 2)");
        String res3 = api.execute("UPDATE stress_table SET age 30 WHERE id = 10");
        
        boolean ok = res1.contains("Error") && res2.contains("Error") && res3.contains("Error");
        return new TestResult("Malformed Queries", ok, "Res1: " + res1 + " | Res2: " + res2 + " | Res3: " + res3);
    }

    private static TestResult testNonExistentEntities() {
        System.out.println("Running: Non-existent Entities");
        String resTable = api.execute("SELECT * FROM non_existent");
        String resCol = api.execute("SELECT non_col FROM stress_table");
        
        boolean ok = resTable.contains("Table not found") && resCol.contains("Column not found");
        return new TestResult("Non-existent Entities", ok, "Table: " + resTable + " | Col: " + resCol);
    }

    private static TestResult testLargeStringValues() {
        System.out.println("Running: Large String Values");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) sb.append("A");
        String largeStr = sb.toString();
        
        api.execute("INSERT INTO stress_table VALUES (100, 20, 50.0, '" + largeStr + "', 'LargeCity')");
        String res = api.execute("SELECT name FROM stress_table WHERE id = 100");
        
        boolean ok = res.contains(largeStr);
        return new TestResult("Large String Storage", ok, "Found large string: " + ok);
    }

    private static TestResult testEmptyValues() {
        System.out.println("Running: Empty Values");
        api.execute("INSERT INTO stress_table VALUES (200, 0, 0.0, ' ', '')");
        String res = api.execute("SELECT name, city FROM stress_table WHERE id = 200");
        boolean ok = res.contains("\t") && !res.contains("null");
        return new TestResult("Empty String Storage", ok, "Output: " + res.replace("\n", "\\n"));
    }

    private static TestResult testBulkOperations() {
        System.out.println("Running: Bulk Operations");
        for (int i = 1000; i < 1100; i++) {
            api.execute("INSERT INTO stress_table VALUES (" + i + ", 25, 100.0, 'User" + i + "', 'City" + i + "')");
        }
        String countRes = api.execute("SELECT id FROM stress_table");
        boolean countOk = countRes.contains("(103 rows)");
        
        api.execute("DELETE FROM stress_table WHERE id >= 1000");
        String afterDelete = api.execute("SELECT id FROM stress_table");
        boolean deleteOk = afterDelete.contains("(3 rows)");
        
        return new TestResult("Bulk Insert & Delete", countOk && deleteOk, "Before: " + countRes.substring(countRes.lastIndexOf("(")) + " | After: " + afterDelete.substring(afterDelete.lastIndexOf("(")));
    }

    private static void printReport(List<TestResult> results) {
        System.out.println("\n==================================================");
        System.out.println("               STRESS TEST REPORT");
        System.out.println("==================================================");
        int passed = 0;
        for (TestResult r : results) {
            System.out.printf("[%s] %-25s | %s\n", r.passed ? "PASS" : "FAIL", r.name, r.message);
            if (r.passed) passed++;
        }
        System.out.println("==================================================");
        System.out.printf("TOTAL: %d | PASSED: %d | FAILED: %d\n", results.size(), passed, results.size() - passed);
        System.out.println("==================================================");
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

    static class TestResult {
        String name;
        boolean passed;
        String message;
        TestResult(String n, boolean p, String m) { name = n; passed = p; message = m; }
    }
}
