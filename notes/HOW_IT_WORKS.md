# CDB — How It Works: Workflow, Storage System & Simulated Examples

---

## Part 1: The Big Picture — What Is a Column-Store?

Traditional databases (like MySQL) store data **row by row**:

```
Row 0: [1,  "Alice",   25]
Row 1: [2,  "Bob",     30]
Row 2: [3,  "Charlie", 20]
```

CDB (Column Database) stores data **column by column** — each column lives in its own file:

```
id.col    →  1 / 2 / 3
name.col  →  Alice / Bob / Charlie
age.col   →  25 / 30 / 20
```

**Why?** When you do `SELECT name FROM users WHERE age > 20`, you only need to touch 2 files (`age.col` and `name.col`). The `id.col` file is never even opened. This is column-store efficiency.

---

## Part 2: System Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    You (the user)                        │
│              typing ColSQL at CDB >  prompt                 │
└────────────────────────┬────────────────────────────────┘
                         │ raw ColSQL string
                         ▼
              ┌─────────────────────┐
              │    CLIClient.java   │  ← REPL loop (reads input, prints output)
              └──────────┬──────────┘
                         │
                         ▼
              ┌─────────────────────┐
              │   DatabaseAPI.java  │  ← Public facade; wires all subsystems
              └──────────┬──────────┘
               ┌─────────┴─────────┐
               ▼                   ▼
   ┌──────────────────┐  ┌──────────────────────┐
   │  QueryParser.java │  │  SchemaManager.java  │
   │  (parses ColSQL →   │  │  (loads .schema files │
   │   Query object)  │  │   from disk at start) │
   └────────┬─────────┘  └──────────────────────┘
            │ Query AST object
            ▼
   ┌──────────────────────┐
   │   QueryEngine.java   │  ← Validates + executes
   │  (uses SchemaManager │
   │   to check schema,   │
   │   then calls Storage)│
   └──────────┬───────────┘
              │
              ▼
   ┌──────────────────────────┐
   │  TextStorageEngine.java  │  ← Reads/writes .col files on disk
   └──────────────────────────┘
              │
              ▼
   ┌──────────────────────────┐
   │  Disk (your filesystem)  │
   │  my_db/metadata/*.schema │
   │  my_db/tables/X/*.col    │
   └──────────────────────────┘
```

---

## Part 3: Full Simulated Walkthrough

We'll simulate running `test_script.txt` step by step, showing exactly what happens on disk and in memory at each stage.

**Script contents:**
```colsql
CREATE TABLE users (id INT PRIMARY_KEY, name STRING, age INT)
INSERT INTO users VALUES (1, "Alice", 25)
INSERT INTO users VALUES (2, "Bob", 30)
INSERT INTO users VALUES (3, "Charlie", 20)
SELECT name, age FROM users WHERE age > 20
UPDATE users SET age=35 WHERE id=1
SELECT name, age FROM users WHERE id = 1
DELETE FROM users WHERE id=3
SELECT name, age FROM users
EXIT
```

---

### ▶ STEP 0 — Startup

```powershell
java cdb.client.CLIClient my_db
```

**What happens internally:**

1. `CLIClient` creates a `DatabaseAPI("my_db")`
2. `DatabaseAPI` creates:
   - `SchemaManager("my_db")` → scans `my_db/metadata/` for `.schema` files → **none found** (new DB)
   - `TextStorageEngine("my_db")` → ensures `my_db/tables/` folder exists
   - `QueryParser` and `QueryEngine`

**Disk state:**
```
my_db/
├── metadata/   ← (empty)
└── tables/     ← (empty)
```

---

### ▶ STEP 1 — `CREATE TABLE users (id INT PRIMARY_KEY, name STRING, age INT)`

**QueryParser** matches regex `CREATE TABLE (\w+) \((.*)\)`:
- table name = `users`
- columns = `id INT PRIMARY_KEY`, `name STRING`, `age INT`
- Returns: `CreateTableQuery` object with a `TableSchema`

**QueryEngine.executeCreate()**:
- Calls **SchemaManager.createTable()** → writes file:
  ```
  my_db/metadata/users.schema
  ```
  Contents:
  ```
  TABLE users COLUMN id INT PRIMARY_KEY COLUMN name STRING COLUMN age INT
  ```
- Calls **TextStorageEngine.createTable()** → creates directory + empty files:

**Disk state after Step 1:**
```
my_db/
├── metadata/
│   └── users.schema         ← "TABLE users COLUMN id INT PRIMARY_KEY ..."
└── tables/
    └── users/
        ├── id.col            ← (empty)
        ├── name.col          ← (empty)
        └── age.col           ← (empty)
```

**Output:** `Table users created successfully.`

---

### ▶ STEP 2 — `INSERT INTO users VALUES (1, "Alice", 25)`

**QueryParser** extracts: table=`users`, values=`[1, Alice, 25]`  
(strips quotes automatically)

**QueryEngine.executeInsert()**:
1. Looks up `TableSchema` for `users` from `SchemaManager` (it's in memory now)
2. Checks `id` has `PRIMARY_KEY` → reads `id.col` → empty → ✅ no duplicate
3. **TextStorageEngine.appendValue()** called 3 times:
   - appends `1\n` to `id.col`
   - appends `Alice\n` to `name.col`
   - appends `25\n` to `age.col`

**Disk state after STEP 2:**
```
id.col     │ name.col  │ age.col
───────────┼───────────┼────────
1          │ Alice     │ 25
```

**Output:** `1 row inserted.`

---

### ▶ STEP 3 & 4 — Two more INSERTs (Bob and Charlie)

Same process repeats.

**Disk state after all 3 INSERTs:**
```
id.col  │ name.col  │ age.col
────────┼───────────┼────────
1       │ Alice     │ 25        ← row index 0
2       │ Bob       │ 30        ← row index 1
3       │ Charlie   │ 20        ← row index 2
```

> **Key concept:** The **line number = row index**. Line 0 in `id.col` and line 0 in `name.col` belong to the SAME row.

---

### ▶ STEP 5 — `SELECT name, age FROM users WHERE age > 20`

**QueryParser** extracts: columns=`[name, age]`, table=`users`, WHERE: `age > 20`

**QueryEngine.executeSelect()** — this is where columnar efficiency shines:

**Phase 1 — Filter (read only `age.col`):**
```
age.col contents: [25, 30, 20]
Evaluate each line: 25>20 ✅ (index 0), 30>20 ✅ (index 1), 20>20 ❌ (index 2)
Matching row indexes: [0, 1]
```
> `id.col` is **never opened!** ← column-store efficiency

**Phase 2 — Projection (read only `name.col` and `age.col`):**
```
name.col[0] = Alice,   age.col[0] = 25
name.col[1] = Bob,     age.col[1] = 30
```

**Output:**
```
name    age
--------------------
Alice   25
Bob     30
(2 rows)
```

---

### ▶ STEP 6 — `UPDATE users SET age=35 WHERE id=1`

**Phase 1 — Find matching rows:**
```
Reads id.col: [1, 2, 3]
id=1 found at index 0
```

**Phase 2 — Update:**  
`TextStorageEngine.updateValue("users", "age", 0, "35")`
- Reads `age.col` → `[25, 30, 20]`
- Sets index 0 to `35` → `[35, 30, 20]`
- Rewrites the file

**Disk state after UPDATE:**
```
id.col  │ name.col  │ age.col
────────┼───────────┼────────
1       │ Alice     │ 35   ← updated!
2       │ Bob       │ 30
3       │ Charlie   │ 20
```

**Output:** `1 rows updated.`

---

### ▶ STEP 7 — `SELECT name, age FROM users WHERE id = 1`

Filter on `id.col`: value `1` at index 0. Return `name.col[0]` and `age.col[0]`.

**Output:**
```
name    age
--------------------
Alice   35
(1 rows)
```

---

### ▶ STEP 8 — `DELETE FROM users WHERE id=3`

**Phase 1 — Find:** `id.col` has `3` at index 2.

**Phase 2 — Delete:**  
`TextStorageEngine.deleteRow("users", 2)`
- Opens **every** `.col` file in `users/`
- Removes line at index 2 from each file

> When deleting multiple rows, they are removed **bottom-to-top** to prevent index shifting issues.

**Disk state after DELETE:**
```
id.col  │ name.col  │ age.col
────────┼───────────┼────────
1       │ Alice     │ 35
2       │ Bob       │ 30
         ↑ Charlie's row (index 2) removed from ALL .col files
```

**Output:** `1 rows deleted.`

---

### ▶ STEP 9 — `SELECT name, age FROM users` (no WHERE)

No filter → all row indexes returned → reads `name.col` and `age.col` for all rows.

**Output:**
```
name    age
--------------------
Alice   35
Bob     30
(2 rows)
```

---

## Part 4: Constraint Enforcement (Simulated)

### PRIMARY_KEY violation
```colsql
CREATE TABLE pk_demo (id INT PRIMARY_KEY, name STRING)
INSERT INTO pk_demo VALUES (1, "Alice")   -- OK
INSERT INTO pk_demo VALUES (1, "Bob")     -- FAILS
```

At the second INSERT, `QueryEngine` reads `id.col` → finds `1` already there → throws:
```
Error: Constraint violation on id for value 1
```
Bob is **never written to disk**. Alice's row is safe.

---

### UNIQUE violation
```colsql
CREATE TABLE unique_demo (id INT, email STRING UNIQUE)
INSERT INTO unique_demo VALUES (1, "alice@example.com")   -- OK
INSERT INTO unique_demo VALUES (2, "alice@example.com")   -- FAILS
```
Same mechanism — `email.col` is scanned for duplicates before writing.

---

### NOT_NULL violation
```colsql
CREATE TABLE notnull_demo (id INT, username STRING NOT_NULL)
INSERT INTO notnull_demo VALUES (1, "alice")   -- OK
INSERT INTO notnull_demo VALUES (2, null)      -- FAILS
```
`QueryEngine` checks if value is `null`, empty, or the literal string `"null"` → throws:
```
Error: Column username cannot be null.
```

---

## Part 5: The Schema File Format

`.schema` files are plain text — human readable:

```
TABLE users COLUMN id INT PRIMARY_KEY COLUMN name STRING COLUMN age INT
```

| Token | Meaning |
|---|---|
| `TABLE users` | table name |
| `COLUMN id` | column name |
| `INT` | data type |
| `PRIMARY_KEY` | constraint on this column |

At startup, `SchemaManager` scans the `metadata/` folder and parses every `.schema` file into memory so `QueryEngine` knows what columns and constraints exist without reading disk repeatedly.

---

## Part 6: Module Responsibilities Summary

| Module | File | Role |
|---|---|---|
| **Entry Point** | `CLIClient.java` | REPL loop — reads input, prints output |
| **Facade** | `DatabaseAPI.java` | Wires all subsystems together |
| **Parsing** | `QueryParser.java` | Converts raw ColSQL string → typed Query object |
| **AST Nodes** | `querytypes/` | `SelectQuery`, `InsertQuery`, etc. |
| **Execution** | `QueryEngine.java` | Validates schema, enforces constraints, calls storage |
| **Schema Memory** | `SchemaManager.java` | Loads/saves `.schema` files, keeps schemas in HashMap |
| **Schema Model** | `TableSchema.java`, `ColumnSchema.java` | In-memory structure of a table |
| **Storage Interface** | `StorageEngine.java` | Interface contract for any storage backend |
| **Storage Impl** | `TextStorageEngine.java` | Reads/writes `.col` text files on disk |
| **File Helpers** | `FileUtils.java` | Ensures directories and files exist |

---

## Part 7: Why Column-Store?

| Query | Row-Store reads | Column-Store reads |
|---|---|---|
| `SELECT * FROM users` | All columns | All columns (same) |
| `SELECT name FROM users WHERE age > 20` | All rows × all columns | Only `age.col` + `name.col` |
| `SELECT COUNT(*)` | All data | Only 1 column file |

> For analytical queries (aggregations, filters on one column), column-store is dramatically faster because it reads only the relevant `.col` files and skips the rest entirely.
