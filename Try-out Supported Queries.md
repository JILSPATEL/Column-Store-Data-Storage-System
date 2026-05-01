# Column-Store Database Demonstration

This document provides a step-by-step demonstration of the functionality of our Column-Store Database system. A completely fresh database `company_db` was initialized with the following tables:

- `employees` (id INT PRIMARY_KEY, name STRING NOT_NULL, department STRING, salary DOUBLE, is_active BOOLEAN)
- `products` (pid INT PRIMARY_KEY, pname STRING NOT_NULL, category STRING, price FLOAT, stock SHORT)

Below is an execution trace showcasing the SQL commands, internal processing (like Bitmap Index lookups), and formatting.

---
## How To Start Database?
## COMPILE (one-time setup)

> Run from `e:\Column-Store-Data-Storage-System\`

```powershell
javac -encoding UTF-8 -d . cdb/ddl/*.java cdb/util/*.java cdb/storage/*.java cdb/query/querytypes/*.java cdb/query/*.java cdb/api/*.java cdb/client/*.java
```

---

## START THE DATABASE

```powershell
java cdb.client.CLIClient
```

No arguments needed. A `databases/` folder is auto-created on first run.

## 1. View All Data
We have pre-filled our tables with some starter data. Here we retrieve all columns and all rows.

```sql
CDB > USE DATABASE company_db

CDB > SELECT * FROM employees
id	name	department	salary	is_active
---------------------------------------------------------------------------
1	Alice	Engineering	85000.0	true
2	Bob	HR	52000.0	true
3	Charlie	Engineering	91000.0	true
4	David	Finance	74000.0	true
5	Eve	HR	58000.0	false
6	Frank	Engineering	95000.0	true
7	Grace	Finance	67000.0	true
8	Heidi	Marketing	61000.0	true
9	Ivan	HR	53000.0	false
10	Judy	Marketing	72000.0	true
(10 rows)

CDB > SELECT * FROM products
pid	pname	category	price	stock
---------------------------------------------------------------------------
101	Laptop	Electronics	999.99	50
102	Mouse	Electronics	29.99	200
103	Desk Chair	Furniture	349.5	30
104	Monitor	Electronics	449.0	75
105	Notebook	Stationery	5.99	500
106	Pen Pack	Stationery	12.5	300
107	Bookshelf	Furniture	189.99	20
(7 rows)
```

---

## 2. Select Specific Columns
Because this is a column-store, querying strict subsets of columns is highly efficient. The engine only needs to read the `.bin` files for the requested columns.

```sql
CDB > SELECT name, salary FROM employees
name	salary
------------------------------
Alice	85000.0
Bob	52000.0
Charlie	91000.0
David	74000.0
Eve	58000.0
Frank	95000.0
Grace	67000.0
Heidi	61000.0
Ivan	53000.0
Judy	72000.0
(10 rows)

CDB > SELECT pname, price FROM products
pname	price
------------------------------
Laptop	999.99
Mouse	29.99
Desk Chair	349.5
Monitor	449.0
Notebook	5.99
Pen Pack	12.5
Bookshelf	189.99
(7 rows)
```

---

## 3. WHERE with Equals (`=`)
Filtering using exact matches. The engine utilizes the **Bitmap Index** to rapidly filter out matching rows without reading the rest of the records sequentially.

```sql
CDB > SELECT * FROM employees WHERE department = Engineering
[Bitmap Index] Used fast lookup for employees (1 condition(s) joined by AND), matched 3 logical row(s).
id	name	department	salary	is_active
---------------------------------------------------------------------------
1	Alice	Engineering	85000.0	true
3	Charlie	Engineering	91000.0	true
6	Frank	Engineering	95000.0	true
(3 rows)

CDB > SELECT pname, price FROM products WHERE category = Electronics
[Bitmap Index] Used fast lookup for products (1 condition(s) joined by AND), matched 3 logical row(s).
pname	price
------------------------------
Laptop	999.99
Mouse	29.99
Monitor	449.0
(3 rows)
```

---

## 4. WHERE with Comparison Operators
We provide strong numerical matching (`>`, `<`, `>=`, `<=`, `!=`) directly against columns natively mapping to Bitmap Indexes.

```sql
CDB > SELECT name, salary FROM employees WHERE salary > 80000
[Bitmap Index] Used fast lookup for employees (1 condition(s) joined by AND), matched 3 logical row(s).
name	salary
------------------------------
Alice	85000.0
Charlie	91000.0
Frank	95000.0
(3 rows)

CDB > SELECT name, salary FROM employees WHERE salary <= 60000
[Bitmap Index] Used fast lookup for employees (1 condition(s) joined by AND), matched 3 logical row(s).
name	salary
------------------------------
Bob	52000.0
Eve	58000.0
Ivan	53000.0
(3 rows)

CDB > SELECT pname, price FROM products WHERE price >= 100
[Bitmap Index] Used fast lookup for products (1 condition(s) joined by AND), matched 4 logical row(s).
pname	price
------------------------------
Laptop	999.99
Desk Chair	349.5
Monitor	449.0
Bookshelf	189.99
(4 rows)

CDB > SELECT pname, stock FROM products WHERE stock != 200
[Bitmap Index] Used fast lookup for products (1 condition(s) joined by AND), matched 6 logical row(s).
pname	stock
------------------------------
Laptop	50
Desk Chair	30
Monitor	75
Notebook	500
Pen Pack	300
Bookshelf	20
(6 rows)
```

---

## 5. WHERE with AND Condition
Combining multiple filters dynamically queries Bitmap Indexes recursively via fast Bitwise `AND` evaluation.

```sql
CDB > SELECT name, salary FROM employees WHERE department = Engineering AND salary > 90000
[Bitmap Index] Used fast lookup for employees (2 condition(s) joined by AND), matched 2 logical row(s).
name	salary
------------------------------
Charlie	91000.0
Frank	95000.0
(2 rows)

CDB > SELECT * FROM employees WHERE is_active = true AND salary > 70000
[Bitmap Index] Used fast lookup for employees (2 condition(s) joined by AND), matched 5 logical row(s).
id	name	department	salary	is_active
---------------------------------------------------------------------------
1	Alice	Engineering	85000.0	true
3	Charlie	Engineering	91000.0	true
4	David	Finance	74000.0	true
6	Frank	Engineering	95000.0	true
10	Judy	Marketing	72000.0	true
(5 rows)

CDB > SELECT pname, price FROM products WHERE category = Electronics AND price < 100
[Bitmap Index] Used fast lookup for products (2 condition(s) joined by AND), matched 1 logical row(s).
pname	price
------------------------------
Mouse	29.99
(1 rows)
```

---

## 6. WHERE with OR Condition
Just as easily, `OR` relies on Bitwise `OR` indexing for filtering rows that meet at least one listed criteria.

```sql
CDB > SELECT name, department FROM employees WHERE department = HR OR department = Finance
[Bitmap Index] Used fast lookup for employees (2 condition(s) joined by OR), matched 5 logical row(s).
name	department
------------------------------
Bob	HR
David	Finance
Eve	HR
Grace	Finance
Ivan	HR
(5 rows)

CDB > SELECT pname, category FROM products WHERE category = Furniture OR category = Stationery
[Bitmap Index] Used fast lookup for products (2 condition(s) joined by OR), matched 4 logical row(s).
pname	category
------------------------------
Desk Chair	Furniture
Notebook	Stationery
Pen Pack	Stationery
Bookshelf	Furniture
(4 rows)
```

---

## 7. Boolean Column Filter
BOOLEAN types act just like other structures natively under our standard syntax (`is_active = true/false`).

```sql
CDB > SELECT name, is_active FROM employees WHERE is_active = false
[Bitmap Index] Used fast lookup for employees (1 condition(s) joined by AND), matched 2 logical row(s).
name	is_active
------------------------------
Eve	false
Ivan	false
(2 rows)

CDB > SELECT name, department FROM employees WHERE is_active = true
[Bitmap Index] Used fast lookup for employees (1 condition(s) joined by AND), matched 8 logical row(s).
name	department
------------------------------
Alice	Engineering
Bob	HR
Charlie	Engineering
David	Finance
Frank	Engineering
Grace	Finance
Heidi	Marketing
Judy	Marketing
(8 rows)
```

---

## 8. UPDATE Operations
Updates correctly modify the `.bin` storage records in-place physically, and then also rewrite to match the dynamic runtime updates in the Bitmap index structure immediately without restart tracking.

```sql
CDB > SELECT name, salary FROM employees WHERE name = Bob
[Bitmap Index] Used fast lookup for employees (1 condition(s) joined by AND), matched 1 logical row(s).
name	salary
------------------------------
Bob	52000.0
(1 rows)

CDB > UPDATE employees SET salary = 60000 WHERE name = Bob
[Bitmap Index] Used fast lookup for employees (1 condition(s) joined by AND), matched 1 logical row(s).
1 rows updated.

CDB > SELECT name, salary FROM employees WHERE name = Bob
[Bitmap Index] Used fast lookup for employees (1 condition(s) joined by AND), matched 1 logical row(s).
name	salary
------------------------------
Bob	60000.0
(1 rows)
```

---

## 9. UPDATE with AND Condition
Multiple rows can be accurately matched by complicated bitwise queries during bulk UPDATE evaluation.

```sql
CDB > SELECT name, department, salary FROM employees WHERE department = HR AND is_active = false
[Bitmap Index] Used fast lookup for employees (2 condition(s) joined by AND), matched 2 logical row(s).
name	department	salary
---------------------------------------------
Eve	HR	58000.0
Ivan	HR	53000.0
(2 rows)

CDB > UPDATE employees SET salary = 55000 WHERE department = HR AND is_active = false
[Bitmap Index] Used fast lookup for employees (2 condition(s) joined by AND), matched 2 logical row(s).
2 rows updated.

CDB > SELECT name, department, salary FROM employees WHERE department = HR
[Bitmap Index] Used fast lookup for employees (1 condition(s) joined by AND), matched 3 logical row(s).
name	department	salary
---------------------------------------------
Bob	HR	60000.0
Eve	HR	55000.0
Ivan	HR	55000.0
(3 rows)
```

---

## 10. DELETE Operations
Deleted records are replaced via Tombstone `0xFF` byte markers in storage, ensuring fixed width positions are unbroken. Bitmap indexing seamlessly skips the ghost indices across physical bounds.

```sql
CDB > SELECT * FROM products
pid	pname	category	price	stock
---------------------------------------------------------------------------
101	Laptop	Electronics	999.99	50
102	Mouse	Electronics	29.99	200
103	Desk Chair	Furniture	349.5	30
104	Monitor	Electronics	449.0	75
105	Notebook	Stationery	5.99	500
106	Pen Pack	Stationery	12.5	300
107	Bookshelf	Furniture	189.99	20
(7 rows)

CDB > DELETE FROM products WHERE category = Stationery
[Bitmap Index] Used fast lookup for products (1 condition(s) joined by AND), matched 2 logical row(s).
2 rows deleted.

CDB > SELECT * FROM products
pid	pname	category	price	stock
---------------------------------------------------------------------------
101	Laptop	Electronics	999.99	50
102	Mouse	Electronics	29.99	200
103	Desk Chair	Furniture	349.5	30
104	Monitor	Electronics	449.0	75
107	Bookshelf	Furniture	189.99	20
(5 rows)
```

---

## 11. DELETE with AND Condition
As normal for compound logical matches, DELETE follows the accurate resolution tree successfully prior to purging records permanently bottom-up.

```sql
CDB > SELECT name, is_active FROM employees WHERE is_active = false
[Bitmap Index] Used fast lookup for employees (1 condition(s) joined by AND), matched 2 logical row(s).
name	is_active
------------------------------
Eve	false
Ivan	false
(2 rows)

CDB > DELETE FROM employees WHERE is_active = false AND department = HR
[Bitmap Index] Used fast lookup for employees (2 condition(s) joined by AND), matched 2 logical row(s).
2 rows deleted.

CDB > SELECT * FROM employees
id	name	department	salary	is_active
---------------------------------------------------------------------------
1	Alice	Engineering	85000.0	true
2	Bob	HR	60000.0	true
3	Charlie	Engineering	91000.0	true
4	David	Finance	74000.0	true
6	Frank	Engineering	95000.0	true
7	Grace	Finance	67000.0	true
8	Heidi	Marketing	61000.0	true
10	Judy	Marketing	72000.0	true
(8 rows)
```

---

## 12. Constraint Violations
Integrity matches correctly catch conflicts before evaluating actual modifications inside queries via upfront checks.

```sql
--- PRIMARY_KEY Violation ---
CDB > INSERT INTO employees VALUES (1, TestDuplicate, Engineering, 50000.0, true)
Error: Constraint violation on id for value 1

--- NOT_NULL Violation ---
CDB > INSERT INTO employees VALUES (20, null, Engineering, 50000.0, true)
Error: Column name cannot be null.

--- Type Violation ---
CDB > INSERT INTO employees VALUES (abc, TestUser, Engineering, 50000.0, true)
Error: Invalid value for type INT: "abc". For input string: "abc"
```

---

## 13. Insert New Row After Deletions
The system correctly handles tombstoning physics, so new entries align natively at the end offset securely without corruption.

```sql
CDB > INSERT INTO employees VALUES (11, Karen, Engineering, 88000.0, true)
1 row inserted.

CDB > SELECT * FROM employees
id	name	department	salary	is_active
---------------------------------------------------------------------------
1	Alice	Engineering	85000.0	true
2	Bob	HR	60000.0	true
3	Charlie	Engineering	91000.0	true
4	David	Finance	74000.0	true
6	Frank	Engineering	95000.0	true
7	Grace	Finance	67000.0	true
8	Heidi	Marketing	61000.0	true
10	Judy	Marketing	72000.0	true
11	Karen	Engineering	88000.0	true
(9 rows)
```

---

## 14. Bitmap Index Visualization
We can inspect the exact logic representation our indexes are carrying during matches. Bit 0 to Bit N mapping out index coordinates exactly.

```
Bitmap Index for table 'products':
  Column: pname
    Value 'Desk Chair': 00100 {2}
    Value 'Laptop': 10000 {0}
    Value 'Monitor': 00010 {3}
    Value 'Mouse': 01000 {1}
    Value 'Bookshelf': 00001 {4}
  Column: price
    Value '189.99': 00001 {4}
    Value '999.99': 10000 {0}
    Value '349.5': 00100 {2}
    Value '29.99': 01000 {1}
    Value '449.0': 00010 {3}
  Column: pid
    Value '101': 10000 {0}
    Value '102': 01000 {1}
    Value '103': 00100 {2}
    Value '104': 00010 {3}
    Value '107': 00001 {4}
  Column: category
    Value 'Electronics': 11010 {0, 1, 3}
    Value 'Furniture': 00101 {2, 4}
  Column: stock
    Value '200': 01000 {1}
    Value '50': 10000 {0}
    Value '30': 00100 {2}
    Value '20': 00001 {4}
    Value '75': 00010 {3}
```