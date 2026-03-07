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

```sql
SHOW DATABASES                  -- list all databases (marks active one)
CREATE DATABASE <name>          -- create a new database
USE DATABASE <name>             -- switch to a database
```

**Example session:**
```
CDB > SHOW DATABASES
→ No databases found.

CDB > CREATE DATABASE university
→ Database 'university' created successfully.

CDB > USE DATABASE university
→ Switched to database 'university'.

CDB [university] >             ← prompt shows active database
```

---

## 4. SUPPORTED SQL COMMANDS

> SQL only works after `USE DATABASE <name>` is run.

### CREATE TABLE
```sql
CREATE TABLE <name> (<col> <TYPE> [CONSTRAINT], ...)
```
```sql
CREATE TABLE users (id INT PRIMARY_KEY, name STRING, age INT)
```
**Types:** `INT`, `STRING` | **Constraints:** `PRIMARY_KEY`, `NOT_NULL`, `UNIQUE`

---

### INSERT
```sql
INSERT INTO users VALUES (1, "Alice", 25)
INSERT INTO users VALUES (2, "Bob", 30)
```

---

### SELECT
```sql
SELECT <col1>, <col2> FROM <table>
SELECT <col1>, <col2> FROM <table> WHERE <col> <op> <value>
```
```sql
SELECT name, age FROM users
SELECT name, age FROM users WHERE age > 20
SELECT name, age FROM users WHERE id = 1
```
**WHERE operators:** `=`, `>`, `<`

---

### UPDATE
```sql
UPDATE users SET age=35 WHERE id=1
```

---

### DELETE
```sql
DELETE FROM users WHERE id=3
```

---

### EXIT / QUIT
```sql
EXIT
QUIT
```

---

## 5. WHERE DATA IS STORED

```
databases/
└── university/               ← one folder per database
    ├── metadata/
    │   └── users.schema      ← table structure
    └── tables/
        └── users/
            ├── id.col        ← one value per line
            ├── name.col
            └── age.col
```

Each `.col` file = one column. Same line number across all files = same row.

---

## 6. DEMO DATABASE (pre-existing data)

The `demo_database_folder/` at the project root is a legacy demo folder.
To use it, copy it into `databases/` first:

```powershell
Copy-Item -Recurse demo_database_folder databases\demo
```

Then inside CDB:
```sql
USE DATABASE demo
SELECT id, name FROM pk_demo
SELECT email FROM unique_demo
SELECT id, username FROM notnull_demo
```

---

## 7. COMMON ERRORS & FIXES

| Error | Cause | Fix |
|---|---|---|
| `No database selected` | SQL typed before `USE DATABASE` | Run `USE DATABASE <name>` first |
| `Table not found: X` | Wrong active database | Check with `SHOW DATABASES`, switch DB |
| `Constraint violation on id` | Duplicate PRIMARY_KEY or UNIQUE | Use a different value |
| `Column X cannot be null` | NOT_NULL constraint violated | Provide a real value |
| `Column count doesn't match` | Wrong number of VALUES | Match columns defined in CREATE TABLE |
| `unmappable character` (compile) | Encoding issue on Windows | Add `-encoding UTF-8` to javac |
