# Query Parsing and Execution Pipeline

This document outlines how our database translates a plain text SQL query typed by the user into actual data manipulation operations on the binary files.

## 1. The Parser (Understanding the User)

When a user types a command like `SELECT name FROM employees WHERE salary > 50000`, the system first needs to understand what they mean. This is the job of the `QueryParser`.

The parser acts like a translator. It uses **Regular Expressions (Regex)** to break down the raw SQL string into structured Java objects.

1. **Identifying the Command:** The parser looks at the first word (`SELECT`, `INSERT`, `CREATE`, etc.) to determine the intent.
2. **Extracting Data:** It uses Regex groups to capture table names, requested columns, and values.
3. **Parsing the WHERE Clause:** If a `WHERE` keyword is found, it splits the condition. For example, `salary > 50000` is split into:
   - Column: `salary`
   - Operator: `>`
   - Value: `50000`

The result is a strongly typed `Query` object (like `SelectQuery` or `InsertQuery`) that contains all the exact instructions the engine needs.

## 2. The Execution Engine (Doing the Work)

Once the `QueryParser` builds the `Query` object, it is handed off to the `QueryEngine` for execution.

### The Execution Flow

1. **Validation:** The engine checks the `SchemaManager` to ensure the table and columns exist. For inserts, it ensures data types match the schema and constraints (like `NOT_NULL` or `PRIMARY_KEY`) are respected.
2. **Index Lookup & Filtering:** If there is a `WHERE` clause, the engine first asks the `BitmapIndexManager` for matching rows. 
   - If an index exists, it instantly gets a bit-vector of the exact row numbers to fetch.
   - If no index exists, it performs a sequential scan of the column files to find matching rows.
3. **Storage Access:** Finally, the `QueryEngine` commands the `BinaryStorageEngine` to read, write, update, or delete the exact rows that matched the query.

### Ensuring Atomicity

For queries like `INSERT`, validation is done for **every column** before any data is actually written to the disk. This ensures that a row is only saved if the entire record is perfect. If the 3rd column fails validation, the whole insert is rejected, preventing corrupt or half-written data.

---

## 3. Summary for Viva

If the examiner asks: *"How does a query travel from the user input to the actual binary files?"*

**Answer:** "The user types a SQL string which is read by our `CLIClient`. It is passed to the `QueryParser`, which uses Regex to decompose the string into a structured Java `Query` object representing the parsed intent. This object is handed to the `QueryEngine`, which validates the schema, resolves conditions using our `BitmapIndexManager` for fast indexing, and finally issues read/write commands to the `BinaryStorageEngine` to manipulate the actual physical `.bin` files on disk."
