import cdb.api.DatabaseAPI;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public class StressTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(65));
        System.out.println("  CDB Two-Gate Bitmap Index Stress Test");
        System.out.println("=".repeat(65));

        // ── TEST GROUP A: Gate 1 — Type gate ─────────────────────────────
        {
            DatabaseAPI db = freshDB("databases/st_gate1");
            exec(db, "CREATE TABLE T (ID INT PRIMARY_KEY, Dept VARCHAR, Score DOUBLE, Active BOOLEAN)");
            exec(db, "INSERT INTO T VALUES (1, HR, 99.5, true)");
            exec(db, "INSERT INTO T VALUES (2, IT, 45.0, false)");
            exec(db, "INSERT INTO T VALUES (3, HR, 77.0, true)");

            section("Test 1 — Gate 1: numeric columns (ID, Score) must NOT be indexed");
            String idx = db.dumpIndex("T");
            System.out.println(idx);
            assertNotContains("ID not in index",    idx, "Column: ID");
            assertNotContains("Score not in index", idx, "Column: Score");

            section("Test 2 — Gate 1: low-cardinality categorical columns ARE indexed");
            assertContains("Dept in index",   idx, "Column: Dept");
            assertContains("Active in index", idx, "Column: Active");
        }

        // ── TEST GROUP B: Gate 2 — Cardinality gate (build-time) ─────────
        {
            // Build a table where Name is VARCHAR but ALL values are unique (high cardinality)
            DatabaseAPI db = freshDB("databases/st_gate2_build");
            exec(db, "CREATE TABLE People (ID INT PRIMARY_KEY, Name VARCHAR, Status VARCHAR)");
            // Insert 30 rows with 30 unique names (60/30 ratio far > 0.50) but Status has 2 values
            for (int i = 1; i <= 30; i++) {
                exec(db, String.format("INSERT INTO People VALUES (%d, Person%d, %s)",
                        i, i, (i % 2 == 0 ? "ACTIVE" : "INACTIVE")));
            }

            section("Test 3 — Gate 2 (build-time): unique Name VARCHAR must be skipped");
            String idx = db.dumpIndex("People");
            System.out.println(idx);
            assertNotContains("Name (unique) must NOT be indexed", idx, "Column: Name");
            assertContains("Status (2 values) must still be indexed", idx, "Column: Status");
        }

        // ── TEST GROUP C: Gate 2 — Cardinality gate (insert-time eviction) ─
        {
            // Start with a low-cardinality Tag column, then insert enough unique tags to evict it
            DatabaseAPI db = freshDB("databases/st_gate2_insert");
            exec(db, "CREATE TABLE Events (ID INT PRIMARY_KEY, Tag VARCHAR, Region VARCHAR)");

            section("Test 4a — Gate 2 (insert): index initially built for Tag (low cardinality)");
            // First 5 rows — only 3 distinct tags, well within limits
            exec(db, "INSERT INTO Events VALUES (1, CLICK, NORTH)");
            exec(db, "INSERT INTO Events VALUES (2, VIEW, SOUTH)");
            exec(db, "INSERT INTO Events VALUES (3, CLICK, EAST)");
            exec(db, "INSERT INTO Events VALUES (4, PURCHASE, WEST)");
            exec(db, "INSERT INTO Events VALUES (5, VIEW, NORTH)");

            String idxEarly = db.dumpIndex("Events");
            assertContains("Tag indexed initially", idxEarly, "Column: Tag");

            section("Test 4b — Gate 2 (insert): eviction when unique Tag values exceed ratio");
            // Now insert 20 more rows with completely unique tag names so that
            // distinctTags / totalRows blows past 0.50 threshold once >= 20 rows exist
            for (int i = 6; i <= 25; i++) {
                exec(db, String.format("INSERT INTO Events VALUES (%d, UNIQUE_TAG_%d, NORTH)", i, i));
            }

            String idxLate = db.dumpIndex("Events");
            System.out.println(idxLate);
            // Tag should now be evicted; Region (5 distinct out of 25) stays
            assertNotContains("Tag evicted after cardinality growth", idxLate, "Column: Tag");
            assertContains("Region still indexed",  idxLate, "Column: Region");
        }

        // ── TEST GROUP D: Query routing ───────────────────────────────────
        {
            DatabaseAPI db = freshDB("databases/st_routing");
            exec(db, "CREATE TABLE Emp (ID INT PRIMARY_KEY, Name VARCHAR, Dept VARCHAR, Age INT)");
            String[] depts = {"HR","IT","IT","Finance","HR","IT","Finance","HR","IT","Finance"};
            String[] names = {"Alice","Bob","Carol","Dave","Eve","Frank","Grace","Hank","Ivy","Jake"};
            int[] ages = {28,34,22,45,31,27,38,52,29,41};
            for (int i = 0; i < 10; i++) {
                exec(db, String.format("INSERT INTO Emp VALUES (%d, %s, %s, %d)",
                        i+1, names[i], depts[i], ages[i]));
            }

            section("Test 5 — Low-cardinality categorical WHERE uses bitmap path");
            String r1 = exec(db, "SELECT Name FROM Emp WHERE Dept = IT");
            assertContains("Bob in IT",   r1, "Bob");
            assertContains("Carol in IT", r1, "Carol");
            assertNotContains("Alice not IT", r1, "Alice");

            section("Test 6 — High-cardinality categorical (Name) falls to sequential scan");
            // Name has 10 unique values in 10 rows = ratio 1.0 > 0.50, so NOT indexed
            String idxEmp = db.dumpIndex("Emp");
            assertNotContains("Name not indexed (high cardinality)", idxEmp, "Column: Name");
            // Query still works — sequential scan fallback
            String r2 = exec(db, "SELECT Dept FROM Emp WHERE Name = Alice");
            assertContains("Alice's dept returned via seq scan", r2, "HR");

            section("Test 7 — Numeric WHERE always uses sequential scan");
            String r3 = exec(db, "SELECT Name FROM Emp WHERE Age > 40");
            assertContains("Dave age 45", r3, "Dave");
            assertContains("Hank age 52", r3, "Hank");
            assertNotContains("Alice age 28 not >40", r3, "Alice");
        }

        // ── TEST GROUP E: UPDATE / DELETE consistency ─────────────────────
        {
            DatabaseAPI db = freshDB("databases/st_dml");
            exec(db, "CREATE TABLE S (ID INT PRIMARY_KEY, Status VARCHAR, Region VARCHAR)");
            exec(db, "INSERT INTO S VALUES (1, ACTIVE, NORTH)");
            exec(db, "INSERT INTO S VALUES (2, INACTIVE, SOUTH)");
            exec(db, "INSERT INTO S VALUES (3, ACTIVE, EAST)");

            section("Test 8 — UPDATE moves bitmap bit correctly");
            exec(db, "UPDATE S SET Status = INACTIVE WHERE Region = NORTH");
            String afterUpdate = exec(db, "SELECT ID FROM S WHERE Status = INACTIVE");
            assertContains("Row 1 now INACTIVE", afterUpdate, "1");
            assertContains("Row 2 still INACTIVE", afterUpdate, "2");
            String activeRows = exec(db, "SELECT ID FROM S WHERE Status = ACTIVE");
            // Use "\n1\n" to avoid matching "(1 rows)" in the footer
            assertNotContains("Row 1 no longer ACTIVE", activeRows, "\n1\n");
            assertContains("Row 3 still ACTIVE", activeRows, "3");

            section("Test 9 — DELETE + rebuild keeps index consistent");
            exec(db, "DELETE FROM S WHERE Status = INACTIVE");
            String afterDelete = exec(db, "SELECT ID FROM S WHERE Status = INACTIVE");
            assertNotContains("Row 1 deleted", afterDelete, "1");
            assertNotContains("Row 2 deleted", afterDelete, "2");
            String remaining = exec(db, "SELECT ID FROM S WHERE Status = ACTIVE");
            assertContains("Row 3 remains", remaining, "3");
        }

        // ── TEST GROUP F: 10k-row performance ────────────────────────────
        {
            section("Test 10 — Mass INSERT: 10,000 rows timing");
            DatabaseAPI db = freshDB("databases/st_perf");
            exec(db, "CREATE TABLE Log (ID INT PRIMARY_KEY, Type VARCHAR, Region VARCHAR, Score DOUBLE)");

            String[] types   = {"CLICK","VIEW","PURCHASE","SCROLL","SHARE"};
            String[] regions = {"NORTH","SOUTH","EAST","WEST","CENTRAL"};

            long t0 = System.currentTimeMillis();
            for (int i = 0; i < 10_000; i++) {
                exec(db, String.format("INSERT INTO Log VALUES (%d, %s, %s, %.2f)",
                        i, types[i % 5], regions[i % 5], i * 0.5));
            }
            System.out.printf("  10,000 inserts: %d ms%n", System.currentTimeMillis() - t0);

            // Bitmap path (Type = CLICK — 5 distinct values, very low ratio)
            long t1 = System.currentTimeMillis();
            String bmp = exec(db, "SELECT ID FROM Log WHERE Type = CLICK");
            System.out.printf("  Bitmap  (Type=CLICK):  %d ms, %s%n",
                    System.currentTimeMillis() - t1, extractRowCount(bmp));

            // Sequential path (Score > 2500 — numeric)
            long t2 = System.currentTimeMillis();
            String seq = exec(db, "SELECT ID FROM Log WHERE Score > 2500");
            System.out.printf("  Seq scan (Score>2500): %d ms, %s%n",
                    System.currentTimeMillis() - t2, extractRowCount(seq));

            assertContains("Bitmap returns results", bmp, "rows)");
            assertContains("Seq scan returns results", seq, "rows)");

            section("Test 11 — Sequential scan correctness (Score > 2500 → 4999 rows)");
            String seqCheck = exec(db, "SELECT ID FROM Log WHERE Score > 2500.0");
            assertContains("Exactly 4999 rows", seqCheck, "4999 rows");
        }

        // ── TEST GROUP G: Mixed AND/OR logic ──────────────────────────────
        {
            DatabaseAPI db = freshDB("databases/st_mixed_logic");
            exec(db, "CREATE TABLE M (ID INT PRIMARY_KEY, Shape VARCHAR, Color VARCHAR)");
            exec(db, "INSERT INTO M VALUES (1, SQUARE, RED)");
            exec(db, "INSERT INTO M VALUES (2, CIRCLE, BLUE)");
            exec(db, "INSERT INTO M VALUES (3, SQUARE, BLUE)");
            exec(db, "INSERT INTO M VALUES (4, TRIANGLE, RED)");

            section("Test 12 — Mixed AND/OR on Bitmap Fast Path");
            // OR logic precedence test: (Shape = SQUARE AND Color = RED) OR (Color = BLUE)
            // Should match: 1 (SQUARE RED), 2 (CIRCLE BLUE), 3 (SQUARE BLUE). Should NOT match 4.
            String r1 = exec(db, "SELECT ID FROM M WHERE Shape = SQUARE AND Color = RED OR Color = BLUE");
            assertContains("Matches Row 1 (SQUARE AND RED)", r1, "1");
            assertContains("Matches Row 2 (OR BLUE)", r1, "2");
            assertContains("Matches Row 3 (OR BLUE)", r1, "3");
            assertNotContains("Does not match Row 4", r1, "4");

            section("Test 13 — Mixed AND/OR on Sequential Scan Fallback");
            exec(db, "CREATE TABLE M2 (ID INT PRIMARY_KEY, Shape VARCHAR, Size INT)");
            exec(db, "INSERT INTO M2 VALUES (1, SQUARE, 10)");
            exec(db, "INSERT INTO M2 VALUES (2, CIRCLE, 20)");
            exec(db, "INSERT INTO M2 VALUES (3, SQUARE, 20)");
            exec(db, "INSERT INTO M2 VALUES (4, TRIANGLE, 10)");

            // Numeric column forces sequential scan
            String r2 = exec(db, "SELECT ID FROM M2 WHERE Shape = SQUARE AND Size = 10 OR Size = 20");
            assertContains("Matches Row 1 (SQUARE AND 10)", r2, "1");
            assertContains("Matches Row 2 (OR 20)", r2, "2");
            assertContains("Matches Row 3 (OR 20)", r2, "3");
            assertNotContains("Does not match Row 4", r2, "4");
        }

        // ── Summary ───────────────────────────────────────────────────────
        System.out.println();
        System.out.println("=".repeat(65));
        System.out.printf("  RESULTS:  %d passed  |  %d failed%n", passed, failed);
        System.out.println("=".repeat(65));

        // Cleanup
        for (String d : new String[]{"st_gate1","st_gate2_build","st_gate2_insert",
                                     "st_routing","st_dml","st_perf","st_mixed_logic"}) {
            deleteDirectory(new File("databases/" + d));
        }

        if (failed > 0) System.exit(1);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static DatabaseAPI freshDB(String path) throws Exception {
        deleteDirectory(new File(path));
        new File(path).mkdirs();
        return new DatabaseAPI(path);
    }

    private static String exec(DatabaseAPI db, String sql) {
        String result = db.execute(sql);
        System.out.println("  SQL: " + sql);
        System.out.println("  ->  " + result.replace("\n", "\n       "));
        return result;
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("── " + title);
        System.out.println("─".repeat(65));
    }

    private static void assertContains(String label, String haystack, String needle) {
        if (haystack.contains(needle)) {
            System.out.printf("  [PASS] %s%n", label);
            passed++;
        } else {
            System.out.printf("  [FAIL] %s  (expected '%s')%n", label, needle);
            failed++;
        }
    }

    private static void assertNotContains(String label, String haystack, String needle) {
        if (!haystack.contains(needle)) {
            System.out.printf("  [PASS] %s%n", label);
            passed++;
        } else {
            System.out.printf("  [FAIL] %s  (unexpected '%s' found)%n", label, needle);
            failed++;
        }
    }

    private static String extractRowCount(String result) {
        int open = result.lastIndexOf('('), close = result.lastIndexOf(')');
        return (open >= 0 && close > open) ? result.substring(open + 1, close) : "?";
    }

    private static void deleteDirectory(File dir) {
        if (dir == null || !dir.exists()) return;
        try {
            Files.walk(dir.toPath()).sorted(Comparator.reverseOrder())
                 .map(Path::toFile).forEach(File::delete);
        } catch (Exception ignored) {}
    }
}
