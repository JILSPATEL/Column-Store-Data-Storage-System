# Client, DDL, and API Module Architecture

## Overview
These components act as the entry point to the system, handling user interactions, orchestrating the internal subsystems, and managing database metadata.

## 1. Schema Manager (DDL Module)
**Purpose:** Manages structural metadata for all tables.
- **TableSchema**: Represents a table, containing the table name and a list of `ColumnSchema` objects.
- **ColumnSchema**: Contains column name, data type (INT, VARCHAR, etc.), and constraints (PRIMARY_KEY, NOT_NULL).
- **Working Flow**:
  - `createTable()`: Receives a `TableSchema` and serializes it to disk (typically as a `schema.json` file inside the table's directory).
  - `getTable()`: Deserializes the `schema.json` file on demand to serve schema information to the Query Engine and Storage Engine.

## 2. Database API (`DatabaseAPI.java`)
**Purpose:** The central orchestrator (Facade pattern) that initializes and connects all internal components.
- **Initialization Algorithm**:
  1. Instantiates `SchemaManager(dataDir)`.
  2. Instantiates `BinaryStorageEngine(dataDir)`.
  3. Instantiates `QueryParser()`.
  4. Instantiates `BitmapIndexManager(storageEngine, schemaManager)` and forces it to load all existing indexes into memory.
  5. Instantiates `QueryEngine(schemaManager, storageEngine, indexManager)`.
- **Execution**: The `execute(String query)` method simply pipes the query string to the parser, catches any parsing errors, and passes the parsed object to the `QueryEngine` for execution, returning the formatted string response.

## 3. CLI Client (`CLIClient.java`)
**Purpose:** An interactive Read-Eval-Print Loop (REPL) for the user.
- **Algorithm**:
  1. Initializes the `DatabaseAPI`.
  2. Enters an infinite `while(true)` loop.
  3. Displays a prompt (e.g., `cdb> `).
  4. Reads the user's input string from standard input.
  5. Checks for special terminal commands (`exit`, `quit`, `clear`).
  6. Passes SQL queries to `databaseAPI.execute()`.
  7. Prints the string result (which contains either tabular data, error messages, or success acknowledgments) to standard output.
  8. Tracks execution time (latency) for each query and displays it, allowing users to benchmark index vs non-index query performance.
