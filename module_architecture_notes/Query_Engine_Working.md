# Query Engine Module Architecture & Working

## Overview
The Query Engine module is responsible for parsing SQL-like queries, understanding their structure, validating them against the schema, and executing them using the underlying Storage and Indexing engines.

## 1. Query Parsing (`QueryParser.java`)
**Flow & Logic:**
- The `QueryParser` takes a raw SQL string and tokenizes it.
- It identifies the query type by the leading keyword (`CREATE`, `INSERT`, `SELECT`, `UPDATE`, `DELETE`).
- **Algorithm for parsing:**
  - **SELECT**: Extracts requested columns, the `FROM` table name, and optionally parses the `WHERE` clause by splitting at `AND`/`OR`. It generates a `SelectQuery` object.
  - **INSERT**: Extracts the table name and the values inside the `VALUES (...)` clause. Generates an `InsertQuery`.
  - **CREATE TABLE**: Extracts column names, types (e.g., INT, VARCHAR), and constraints (e.g., PRIMARY KEY, NOT_NULL). Generates a `CreateTableQuery`.
  - **UPDATE / DELETE**: Parses the `SET` clause (for update) and the `WHERE` clause. Generates an `UpdateQuery` or `DeleteQuery`.

## 2. Query Execution (`QueryEngine.java`)
The `QueryEngine` receives a parsed `Query` object and processes it step-by-step.

### 2.1 INSERT Execution
**Algorithm:**
1. **Validation**: Fetches `TableSchema` from `SchemaManager`. Validates that the number of values matches the number of columns.
2. **Constraint Checks**: Iterates through each value. 
   - If `NOT_NULL`, ensures value is not empty/null.
   - If `PRIMARY_KEY` or `UNIQUE`, queries the `StorageEngine` to read the entire column and verifies the value does not already exist.
   - Type-checks the value (e.g., tries parsing as Integer if the column is INT).
3. **Storage Append**: Calls `storageEngine.appendValue()` for each column. Since it's a columnar store, each column gets the value appended to its respective file.
4. **Index Update**: Calls `indexManager.insertRow()` to update the bitmap indexes for the newly appended row.

### 2.2 SELECT Execution
**Algorithm:**
1. **Resolution**: Resolves `*` to all column names using the schema.
2. **Filtering**: Calls `getFilteredRowIndexes(tableName, schema, whereClause)`.
   - **Fast Path**: Asks `BitmapIndexManager` for the row IDs. If the index exists for the columns in the WHERE clause, it performs bitwise AND/OR and returns the row IDs instantly.
   - **Slow Path (Sequential Scan)**: If indexes are missing, it reads the full column data for the columns involved in the WHERE clause. It loops through all rows and evaluates the conditions (e.g., `A > 10 AND B = 'test'`), adding matching row indices to a result list.
3. **Data Retrieval**: For each requested column, reads the full column data into memory.
4. **Projection**: Iterates through the matching row indices and picks out the values from the loaded column data, formatting them into a tabular string result.

### 2.3 UPDATE and DELETE Execution
- **UPDATE**: Finds matching row indexes (same as SELECT). Then iterates over these indexes and calls `storageEngine.updateValue()`. Also updates the bitmap index by passing the old and new values to `indexManager.updateValue()`.
- **DELETE**: Finds matching row indexes. Reverses the list of indexes (deletes from bottom to top so that row indices of upper rows don't shift during the loop). Calls `storageEngine.deleteRow()` for each. Finally, rebuilding the bitmap index is triggered since row positions shifted.

## Key Design Considerations
- **Sequential Scan Fallback**: Ensures queries still work even if an index is missing, though at the cost of O(N) full column scans.
- **Columnar Projection**: Only the columns explicitly requested in SELECT (and those in WHERE) are read from disk, maximizing I/O efficiency typical of column-store databases.
