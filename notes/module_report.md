# CDB (Column Database) Module Report

## Overview
The CDB prototype is a modular, column-oriented database implemented in Java. It separates query parsing, execution, schema management, and file storage into distinct components. This allows for seamless replacement of the storage engine in the future (e.g., swapping the text-based engine with a binary C++ engine).

## Modules and Responsibilities

### 1. DDL Layer (`cdb.ddl`)
- `ColumnSchema` & `TableSchema`: Model the structure of database tables, storing column names, data types, and constraints (`PRIMARY_KEY`, `NOT_NULL`, `UNIQUE`).
- `SchemaManager`: Responsible for reading and writing `.schema` metadata files to disk and keeping table definitions in memory. During execution, it provides definitions to ensure inserts and updates reflect the proper constraints and columns.

### 2. Storage Layer (`cdb.storage`)
- `StorageEngine`: An interface defining core data interactions: `readColumn`, `appendValue`, `updateValue`, `deleteRow`, `createTable`, and `dropTable`.
- `TextStorageEngine`: The current implementation writing data. Each column is stored in a separate `.col` file within a directory named after the table (e.g., `cdb_data/tables/users/age.col`). It reads and writes values line-by-line, maintaining row associations via identical line indices across a table's column files.

### 3. Query Execution Layer (`cdb.query`)
- `Query` & `querytypes.*`: AST (Abstract Syntax Tree) classes representing parsed ColSQL-like commands (`SelectQuery`, `InsertQuery`, etc.).
- `QueryParser`: Parses raw string inputs into typed `Query` objects using regular expressions and string splitting. It extracts table names, columns, values, and `WHERE` filter conditions.
- `QueryEngine`: The central orchestrator. It receives a `Query` object, coordinates with the `SchemaManager` to validate the operation, and calls the `StorageEngine` to execute it. It correctly applies constraints, evaluates `WHERE` conditions to find matching row indices, and only reads the specific `.col` files requested by a `SELECT` query (demonstrating column-store efficiency).

### 4. API and Client Layers
- `DatabaseAPI` (`cdb.api`): The public facade interacting with external applications. It instantiates the parser, engine, storage, and schema managers, routing string queries through the pipeline and returning textual results.
- `CLIClient` (`cdb.client`): A simple command-line REPL that continually accepts user input, passes it to the `DatabaseAPI`, and prints the operations' results or error messages to the screen.

## Conclusion
The prototype successfully meets the design requirements. It strictly enforces the `StorageEngine` interface for future C++ compatibility, stores data in column-oriented text files, natively parses a subset of ColSQL, and enforces structural integrity and constraints on DML operations.
