# Example Flow: How a Columnar RDBMS Uses This XML Schema

This document walks through a **dummy "university" database** step by step,
explaining what each section of the XML represents and how a columnar RDBMS
would use it.

---

## The Dummy Data (What We're Storing)

### students table (5 rows)

| student_id | name    | age | gpa  |
|------------|---------|-----|------|
| 1          | Alice   | 20  | 3.80 |
| 2          | Bob     | 21  | 3.50 |
| 3          | Charlie | 22  | 3.90 |
| 4          | Diana   | 23  | NULL |
| 5          | Eve     | 24  | 3.20 |

### enrollments table (8 rows)

| enrollment_id | student_id | course_name | grade |
|---------------|------------|-------------|-------|
| 1             | 1          | Math 101    | A     |
| 2             | 1          | CS 201      | A     |
| 3             | 2          | Math 101    | B     |
| 4             | 3          | CS 201      | A     |
| 5             | 3          | Phy 301     | B     |
| 6             | 4          | Math 101    | C     |
| 7             | 5          | CS 201      | B     |
| 8             | 5          | Phy 301     | A     |

---

## How Row-Store vs Column-Store Works

### Row Store (Traditional RDBMS like MySQL)
Stores data **row by row** on disk:

```
Row 1: [1, "Alice",   20, 3.80]
Row 2: [2, "Bob",     21, 3.50]
Row 3: [3, "Charlie", 22, 3.90]
Row 4: [4, "Diana",   23, NULL]
Row 5: [5, "Eve",     24, 3.20]
```

### Column Store (Columnar RDBMS)
Stores data **column by column** in separate files:

```
col_student_id.dat  -->  [1, 2, 3, 4, 5]
col_name.dat        -->  ["Alice", "Bob", "Charlie", "Diana", "Eve"]
col_age.dat         -->  [20, 21, 22, 23, 24]
col_gpa.dat         -->  [3.80, 3.50, 3.90, NULL, 3.20]
```

Each column has its **own file** on disk. This is what makes column stores
fast for analytical queries (e.g., "find average GPA") — they only read
the column they need.

---

## Walking Through Each XML Section

### Section 1, 2, 3: Database Catalog + Tables + Columns

```xml
<crdbms:database name="university_db" creationTimestamp="2026-02-15T10:00:00Z">
  <crdbms:tables>
    <crdbms:table tableId="tbl_students" name="students" rowCountEstimate="5">
      <crdbms:columns>
        <crdbms:column columnId="col_sid"  name="student_id" dataType="INTEGER" nullable="false"/>
        <crdbms:column columnId="col_sname" name="name" dataType="VARCHAR" precision="100" nullable="false"/>
        <crdbms:column columnId="col_sage" name="age" dataType="INTEGER" nullable="false"/>
        <crdbms:column columnId="col_sgpa" name="gpa" dataType="DECIMAL" precision="3" scale="2" nullable="true">
          <crdbms:defaultValue>0.00</crdbms:defaultValue>
        </crdbms:column>
      </crdbms:columns>
    </crdbms:table>
  </crdbms:tables>
```

**What this does:**
- `database` = the catalog (name, when it was created)
- `table` = each table with a unique ID
- `column` = each column's name, data type, and whether NULLs are allowed

---

### Section 4: Columnar Data Storage Mapping

This is the **key difference** from a row-store. Each column has its own file and pages:

```xml
<crdbms:columnStorage columnRef="col_sage" tableRef="tbl_students">
  <crdbms:dataFile filePath="/data/university/students/col_age.dat" fileSize="4096"/>
  <crdbms:dataPages>
    <crdbms:dataPage pageId="pg_sage_1" offset="0" size="2048" nullCount="0" rowCount="5">
      <crdbms:minValue>20</crdbms:minValue>
      <crdbms:maxValue>24</crdbms:maxValue>
    </crdbms:dataPage>
  </crdbms:dataPages>
</crdbms:columnStorage>
```

**What this does:**
- `dataFile` → points to the physical file on disk (`col_age.dat`)
- `dataPage` → a block inside that file
  - `minValue=20, maxValue=24` → the smallest and largest age values in this page
  - `nullCount=0` → no NULL values in this page
  - These min/max help **skip pages** that can't contain the answer

**Example Query: `SELECT * FROM students WHERE age = 25`**
- The system reads the page metadata: min=20, max=24
- Since 25 is NOT between 20 and 24, this page is **skipped entirely!**
- This is called **data pruning** — no need to read the actual data

---

### Section 5: Indexes

```xml
<crdbms:primaryIndex name="pk_students" tableRef="tbl_students">
  <crdbms:indexedColumn columnRef="col_sid"/>        <!-- student_id is the PK -->
</crdbms:primaryIndex>

<crdbms:secondaryIndex name="idx_enroll_student" indexType="BTREE" tableRef="tbl_enrollments">
  <crdbms:indexedColumn columnRef="col_e_sid"/>      <!-- B-Tree on student_id -->
</crdbms:secondaryIndex>

<crdbms:zoneMap columnRef="col_sage" tableRef="tbl_students">
  <crdbms:block blockId="zm_age_1">
    <crdbms:minValue>20</crdbms:minValue>
    <crdbms:maxValue>24</crdbms:maxValue>
  </crdbms:block>
</crdbms:zoneMap>
```

**What this does:**
- **Primary Index** → fast lookup by `student_id`
- **Secondary Index (B-Tree)** → fast lookup/join on `enrollments.student_id`
- **Zone Map** → min/max per data block, used to skip irrelevant blocks

---

### Section 6: Constraints

```xml
<crdbms:primaryKey name="pk_students" tableRef="tbl_students">
  <crdbms:column columnRef="col_sid"/>
</crdbms:primaryKey>

<crdbms:foreignKey name="fk_enroll_student" tableRef="tbl_enrollments"
                   referenceTable="tbl_students" onDelete="CASCADE">
  <crdbms:column columnRef="col_e_sid" referenceColumn="col_sid"/>
</crdbms:foreignKey>

<crdbms:check name="chk_age_positive" tableRef="tbl_students">
  <crdbms:expression>age > 0</crdbms:expression>
</crdbms:check>
```

**What this does:**
- **Primary Key** → `student_id` must be unique and NOT NULL
- **Foreign Key** → `enrollments.student_id` must exist in `students.student_id`
  - `onDelete="CASCADE"` → if a student is deleted, their enrollments are also deleted
- **Check** → age must be greater than 0

---

### Section 7: Statistics

```xml
<crdbms:tableStatistics tableRef="tbl_students" rowCount="5">
  <crdbms:columnStatistics columnRef="col_sid"  distinctCount="5" nullCount="0"
                           minValue="1" maxValue="5"/>
  <crdbms:columnStatistics columnRef="col_sgpa" distinctCount="4" nullCount="1"
                           minValue="3.20" maxValue="3.90"/>
</crdbms:tableStatistics>
```

**What this does:**
- Tells the **query optimizer** about the data:
  - `student_id` has 5 distinct values, no NULLs → every ID is unique
  - `gpa` has 4 distinct values, 1 NULL (Diana's GPA) → range is 3.20 to 3.90
- The optimizer uses this to pick the **best execution plan** for queries

---

## End-to-End Query Example

**Query:** `SELECT name, gpa FROM students WHERE age >= 22`

**Step 1 — Check Zone Map:**
- Zone map for `age` says: min=20, max=24
- Since 22 is within [20, 24], the page MIGHT have matching rows → read it

**Step 2 — Scan Only Needed Columns:**
- Column store reads ONLY `col_age.dat`, `col_name.dat`, and `col_gpa.dat`
- It does NOT read `col_student_id.dat` (not needed for this query!)

**Step 3 — Filter:**
- Read ages: [20, 21, **22**, **23**, **24**] → rows 3, 4, 5 match

**Step 4 — Return Results:**

| name    | gpa  |
|---------|------|
| Charlie | 3.90 |
| Diana   | NULL |
| Eve     | 3.20 |

**This is why columnar storage is fast for analytics!**
Only 3 out of 4 column files were read, and the zone map helped decide
whether to even bother reading them.
