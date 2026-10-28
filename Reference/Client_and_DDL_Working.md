# Client, DDL, and API Module Architecture

## Overview
These components act as the entry point to the system, handling user interactions, orchestrating the internal subsystems, and managing database metadata.

## 1. Schema Manager (DDL Module)
**Purpose:** Manages structural metadata for all tables.
- **Table schema**: Represents a table, containing the table name and a list of `Column schema` objects.
- **Column schema**: Contains column name, data type (INT, VARCHAR, etc.), and constraints (PRIMARY_KEY, NOT_NULL).
- **Working Flow**:
  - `createTable()`: Receives a `Table schema` and serializes it to disk (typically as a `schema.json` file inside the table's directory).
  - `getTable()`: Deserializes the `schema.json` file on demand to serve schema information to the Query Engine and Storage Engine.

## 2. Database API (`C++ database API`)
**Purpose:** The central orchestrator (Facade pattern) that initializes and connects all internal components.
- **Initialization Algorithm**:
  1. Instantiates `Schema manager(dataDir)`.
  2. Instantiates `Binary storage engine(dataDir)`.
  3. Instantiates `Query parser()`.
  4. Instantiates `Bitmap index manager(storage engine, Schema manager)` and forces it to load all existing indexes into memory.
  5. Instantiates `Query engine(Schema manager, storage engine, indexManager)`.
- **Execution**: The `execute(String query)` method simply pipes the query string to the parser, catches any parsing errors, and passes the parsed object to the `Query engine` for execution, returning the formatted string response.

## 3. CLI Client (`C++ CLI`)
**Purpose:** An interactive Read-Eval-Print Loop (REPL) for the user.
- **Algorithm**:
  1. Initializes the `C++ database API`.
  2. Enters an infinite `while(true)` loop.
  3. Displays a prompt (e.g., `cdb> `).
  4. Reads the user's input string from standard input.
  5. Checks for special terminal commands (`exit`, `quit`, `clear`).
  6. Passes SQL queries to `C++ database API.execute()`.
  7. Prints the string result (which contains either tabular data, error messages, or success acknowledgments) to standard output.
  8. Tracks execution time (latency) for each query and displays it, allowing users to benchmark index vs non-index query performance.
