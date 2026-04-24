# Column-Store Data Storage System: Architecture & Query Flow

This document provides an in-depth explanation of the `Column-Store-Data-Storage-System` project. It covers the responsibilities of each layer and file, and traces the complete lifecycle of a query from user input to physical disk storage.

## 1. System Layers and File Concerns

The system is organized into distinct layers, each handling a specific part of the database's operations. The source code resides in the `cdb` directory.

### 1.1 Client Layer (`cdb/client/`)
*   **`CLIClient.java`**: The entry point of the application. It provides a Read-Eval-Print Loop (REPL) interface for users to enter commands and ColSQL queries.
    *   **Concerns**: Handles system-level commands (`SHOW DATABASES`, `CREATE DATABASE`, `USE DATABASE`, `EXIT`). For ColSQL queries (like `SELECT`, `INSERT`), it passes the raw string to the API layer.

### 1.2 API Layer (`cdb/api/`)
*   **`DatabaseAPI.java`**: The main facade for database operations.
    *   **Concerns**: Orchestrates the underlying components (`QueryParser`, `QueryEngine`, `SchemaManager`, `StorageEngine`). It receives raw ColSQL strings from the client, passes them to the parser, and hands the resulting abstract query objects to the execution engine.

### 1.3 Query Parsing Layer (`cdb/query/` & `cdb/query/querytypes/`)
*   **`QueryParser.java`**: Evaluates the raw ColSQL string using regular expressions.
    *   **Concerns**: Identifies the query type (`CREATE TABLE`, `INSERT`, `SELECT`, `UPDATE`, `DELETE`) and extracts relevant components (table name, columns, values, `WHERE` conditions). It throws errors for invalid syntax.
*   **`querytypes/*.java` (`Query`, `SelectQuery`, `InsertQuery`, etc.)**: Abstract representations of parsed queries.
    *   **Concerns**: Serve as structured Data Transfer Objects (DTOs) that carry parsed information from the Parser to the Engine.

### 1.4 Query Execution Layer (`cdb/query/`)
*   **`QueryEngine.java`**: The brain of the query processing.
    *   **Concerns**: Executes the logic of the parsed query objects. It validates constraints (e.g., `NOT_NULL`, `PRIMARY_KEY`), resolves `WHERE` clauses into physical row indices, and interacts with both the DDL layer (for schemas) and the Storage layer (for data).

### 1.5 Data Definition Layer (DDL) (`cdb/ddl/`)
*   **`SchemaManager.java`**: Manages the metadata (schemas) of tables.
    *   **Concerns**: Loads, caches, and persists table definitions (as `.schema` files) in the `databases/<db_name>/metadata/` directory.
*   **`TableSchema.java` & `ColumnSchema.java`**: Represent the structure of a table and its individual columns, including types and constraints.

### 1.6 Storage Layer (`cdb/storage/`)
*   **`StorageEngine.java`**: An interface defining operations for physical data manipulation (`readColumn`, `appendValue`, `updateValue`, `deleteRow`).
*   **`BinaryStorageEngine.java`**: A fixed-width, column-oriented binary persistence implementation.
    *   **Concerns**: Writes schema-compliant binary data to disk. Each column of a table is stored in a separate `.bin` file in `databases/<db_name>/tables/<table_name>/<column_name>.bin`. It handles data encoding/decoding, binary file headers, and logical deletions via "tombstone" markers (0x00 for alive, 0xFF for deleted).

---

## 2. Complete Query Flow (From Input to Database)

Let's trace the journey of a query, using an `INSERT` and a `SELECT` statement as examples.

### Stage 1: User Input (Client Layer)
1.  The user selects a database using `USE DATABASE mydb;`. The `CLIClient` creates an instance of `DatabaseAPI("databases/mydb")`.
2.  The user types a query: `INSERT INTO users VALUES ('1', 'Alice', '25')`.
3.  The `CLIClient` captures this string and calls `db.execute("INSERT INTO users VALUES ('1', 'Alice', '25')")`.

### Stage 2: Parsing (API & Query Parsing Layer)
4.  Inside `DatabaseAPI.execute()`, the string is passed to `QueryParser.parse()`.
5.  `QueryParser` matches the string against the `INSERT` regex pattern.
6.  It extracts the table name (`users`) and the values (`['1', 'Alice', '25']`).
7.  It constructs and returns an `InsertQuery` object.

### Stage 3: Validation and Execution Setup (Query Execution Layer)
8.  `DatabaseAPI` passes the `InsertQuery` object to `QueryEngine.execute()`.
9.  `QueryEngine` checks the query type and routes it to `executeInsert(InsertQuery)`.
10. `QueryEngine` fetches the `TableSchema` for "users" from the `SchemaManager`. If the table doesn't exist, it throws an error.
11. **Constraint Checking**: Before writing, the engine iterates through the schema's columns and the provided values.
    *   If a column is `NOT_NULL`, it ensures the value is not empty.
    *   If a column has a `PRIMARY_KEY` or `UNIQUE` constraint, it calls `StorageEngine.readColumn()` to fetch all existing values and checks for duplicates.

### Stage 4: Physical Storage (Storage Layer)
12. Once validation passes, `QueryEngine` loops over each column defined in the schema.
13. For each column (e.g., `id`, `name`, `age`), it calls `StorageEngine.appendValue(tableName, columnName, value)`.
14. `BinaryStorageEngine` takes over:
    *   It determines the file path (e.g., `databases/mydb/tables/users/age.bin`).
    *   It reads the 8-byte header to find the specific binary type tag (e.g., `TYPE_INT` for age).
    *   It encodes the string value (`"25"`) into its precise binary representation using `java.io.DataOutputStream` (e.g., a 4-byte signed integer).
    *   It appends a 1-byte tombstone flag (`0x00` indicating the row is alive) followed by the encoded value bytes to the end of the file.
    *   It atomically increments the total record count stored in the file's header.
15. The `QueryEngine` returns a success message ("1 row inserted."), which ripples back through the API to the `CLIClient` and is printed to the console.

### Alternate Flow: A `SELECT` Query with a `WHERE` Clause
If the user executes `SELECT name, age FROM users WHERE age > 20`:
1.  **Parse**: `QueryParser` returns a `SelectQuery` detailing requested columns (`name`, `age`) and filter conditions (`age`, `>`, `20`).
2.  **Filter Resolution**: `QueryEngine.executeSelect()` first isolates the set of rows that match the condition. It calls `getFilteredRowIndexes()`, which reads *only* the `age.bin` column from the `StorageEngine`, evaluates the condition (`> 20`) in memory, and returns a list of matching physical row indices (e.g., `[0, 2, 5]`).
3.  **Column Fetching**: `QueryEngine` then loops over the requested columns (`name`, `age`). For each, it calls `StorageEngine.readColumn()`.
4.  **Skipping Deleted Rows**: Inside `BinaryStorageEngine.readColumn()`, as it reads the binary records sequentially, it checks the tombstone byte. If it's `0xFF` (deleted), it skips reading the value bytes. If `0x00` (alive), it decodes the value and adds it to the list.
5.  **Reconstruction**: `QueryEngine` receives lists of column data. It correlates the data using the previously resolved valid row indices to construct a cohesive table view.
6.  **Output**: It formats the data as a tab-separated string and returns it to the client.
