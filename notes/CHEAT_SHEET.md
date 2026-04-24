# CDB Column-Store — Cheat Sheet

---

## 1. COMPILE (one-time setup)

> Run from `e:\Column-Store-Data-Storage-System\`

```powershell
javac -encoding UTF-8 -d . cdb/ddl/*.java cdb/util/*.java cdb/storage/*.java cdb/query/querytypes/*.java cdb/query/*.java cdb/api/*.java cdb/client/*.java
```

---

## 2. START THE DATABASE

```powershell
java cdb.client.CLIClient
```

No arguments needed. A `databases/` folder is auto-created on first run.

---

## 3. DATABASE MANAGEMENT COMMANDS

```colsql
SHOW DATABASES                  -- list all databases (marks active one)
CREATE DATABASE <name>          -- create a new database
USE DATABASE <name>             -- switch to a database
```

**Example session:**
```
CDB > SHOW DATABASES
→ No databases found.

CDB > CREATE DATABASE emp
→ Database 'emp' created successfully.

CDB > USE DATABASE emp
→ Switched to database 'emp'.

CDB [emp] >             ← prompt shows active database
```

---

## 4. SUPPORTED ColSQL COMMANDS

> ColSQL only works after `USE DATABASE <name>` is run.

### CREATE TABLE
```colsql
CREATE TABLE <name> (<col> <TYPE> [CONSTRAINT], ...)
```
```colsql
CREATE TABLE integers (id INT PRIMARY_KEY, byte_col BYTE, short_col SHORT, long_col LONG)
CREATE TABLE decimals (id INT PRIMARY_KEY, float_col FLOAT, double_col DOUBLE, big_col BIGDECIMAL)
CREATE TABLE mixed (id INT PRIMARY_KEY, score DOUBLE, active BOOLEAN)
```
**Numeric Types:** `BYTE`, `SHORT`, `INT`, `LONG`, `FLOAT`, `DOUBLE`, `BOOLEAN`, `BIGDECIMAL`  
*(Note: String/text data is not supported in the current Binary Engine)*  
**Constraints:** `PRIMARY_KEY`, `NOT_NULL`, `UNIQUE`

---

### INSERT
```colsql
INSERT INTO integers VALUES (1, 127, 32767, 9223372036854775807)
INSERT INTO mixed VALUES (2, 99.5, true)
```

---

### SELECT
```colsql
SELECT <col1>, <col2> FROM <table>
SELECT <col1>, <col2> FROM <table> WHERE <col> <op> <value>
```
```colsql
SELECT id, long_col FROM integers
SELECT id, score FROM mixed WHERE score > 50.0
SELECT id, score FROM mixed WHERE active = true
```
**WHERE operators:** `=`, `>`, `<`

---

### UPDATE
```colsql
UPDATE mixed SET score=100.0 WHERE id=2
```

---

### DELETE
```colsql
DELETE FROM mixed WHERE id=2
```

---

### EXIT / QUIT
```colsql
EXIT
QUIT
```

---

## 5. WHERE DATA IS STORED (BINARY ENGINE)

```
databases/
└── numericdb/         ← one folder per database
    ├── metadata/
    │   └── mixed.schema      ← table structure (text definition)
    └── tables/
        └── mixed/
            ├── id.bin       ← one binary column file per column
            ├── score.bin
            └── active.bin
```

Data is stored in **fixed-width binary format** (`.bin` files):
- 8-byte global header (type tag + record count).
- Fixed-width records (1 byte tombstone flag + raw value bytes).
- Live rows have a `0x00` flag, deleted rows skip over a `0xFF` flag.
- Enables O(1) in-place byte overwriting for `UPDATE` operations.

---

## 6. DEMO SCRIPT

A standalone demo is included to quickly seed and test the system:

```powershell
javac -encoding UTF-8 -cp . BinaryStorageDemo.java
java -cp . BinaryStorageDemo
```
This generates `databases/numericdb` with populated sample data.

---

## 7. COMMON ERRORS & FIXES

| Error | Cause | Fix |
|---|---|---|
| `No database selected` | ColSQL typed before `USE DATABASE` | Run `USE DATABASE <name>` first |
| `Table not found: X` | Wrong active database | Check with `SHOW DATABASES`, switch DB |
| `Constraint violation on id` | Duplicate PRIMARY_KEY or UNIQUE | Use a different value |
| `Column X cannot be null` | NOT_NULL constraint violated | Provide a real value |
| `Column count doesn't match` | Wrong number of VALUES | Match columns defined in CREATE TABLE |
| `unmappable character` (compile) | Encoding issue on Windows | Add `-encoding UTF-8` to javac |
