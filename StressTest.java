import cdb.api.DatabaseAPI;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Stress test for the CDB Column-Store system — Two-Gate Bitmap Index Policy.
 *
 * GATE 1 (type): Only categorical types (VARCHAR, STRING, BOOLEAN) get indexed.
 * GATE 2 (cardinality): Even categorical columns are skipped/evicted if
 *   distinct values exceed MAX_DISTINCT_VALUES (100) OR
 *   distinct/total ratio exceeds MAX_CARDINALITY_RATIO (0.50) once >= 20 rows exist.
 *
 * Tests:
 *  1.  Gate 1: numeric columns never indexed (INT, DOUBLE)
 *  2.  Gate 1: categorical columns ARE indexed when cardinality is low
 *  3.  Gate 2 at build time: high-cardinality VARCHAR column skipped
 *  4.  Gate 2 at insert time: index evicted when cardinality grows past threshold
 *  5.  Low-cardinality categorical WHERE uses fast bitmap path
 *  6.  High-cardinality categorical WHERE falls back to sequential scan
 *  7.  Numeric WHERE always sequential scan
 *  8.  UPDATE correctly moves bitmap bit
 *  9.  DELETE + rebuild keeps index consistent
 * 10.  10,000-row performance: bitmap vs sequential timing
 * 11.  Correctness: sequential scan row count validation
 */
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

        // ── Summary ───────────────────────────────────────────────────────
        System.out.println();
        System.out.println("=".repeat(65));
        System.out.printf("  RESULTS:  %d passed  |  %d failed%n", passed, failed);
        System.out.println("=".repeat(65));

        // Cleanup
        for (String d : new String[]{"st_gate1","st_gate2_build","st_gate2_insert",
                                     "st_routing","st_dml","st_perf"}) {
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
