# Query Engine Module Architecture & Working

## Overview
The Query Engine is the "brain" of the database. It is responsible for parsing SQL-like query strings, validating their semantics against the database schema, and coordinating the `StorageEngine` and `BitmapIndexManager` to fetch, modify, or store data. 

The query lifecycle consists of two main phases: **Parsing** and **Execution**.

---

## 1. Query Parsing Phase (`QueryParser.java`)
Before a query can be executed, it must be translated from a raw string into a programmatic object.

1. **Tokenization & Type Identification**: The parser reads the first word of the query (e.g., `CREATE`, `INSERT`, `SELECT`, `UPDATE`, `DELETE`) to determine the operation type.
2. **Object Generation**: Based on the type, it parses the rest of the string and generates a specific subclass of `Query`:
   - **`CreateTableQuery`**: Extracts the table name, column names, column types (`INT`, `VARCHAR`), and constraints (`PRIMARY_KEY`, `NOT_NULL`).
   - **`InsertQuery`**: Extracts the target table and parses the comma-separated literals inside the `VALUES (...)` block.
   - **`SelectQuery`**: Extracts the requested columns (or `*`), the target table, and parses the `WHERE` clause.
   - **`UpdateQuery`**: Extracts the target table, the `SET col = val` assignment, and the `WHERE` clause.
   - **`DeleteQuery`**: Extracts the target table and the `WHERE` clause.
3. **`WHERE` Clause Parsing**: A dedicated method parses conditions (e.g., `Age > 30 AND Dept = 'IT'`) into a `WhereClause` object. This object holds a list of `WhereCondition` objects (column, operator, value) and the logical operators (`AND`, `OR`) connecting them.

---

## 2. Execution Phase Algorithms (`QueryEngine.java`)

Once the query is parsed into a `Query` object, `QueryEngine.execute()` routes it to the specific execution algorithm.

### 2.1 `CREATE TABLE` Execution
**Step-by-Step Algorithm:**
1. **Schema Generation**: The query object contains a `TableSchema` definition.
2. **Metadata Registration**: Calls `schemaManager.createTable(schema)` to write the schema definition to disk as a `.idx` file and register it in memory.
3. **Physical Storage Creation**: Calls `storageEngine.createTable(schema)`, which creates the physical directory for the table and an empty `.dat` file for every column defined in the schema.

### 2.2 `INSERT` Execution
**Step-by-Step Algorithm:**
1. **Table Validation**: Fetches the `TableSchema`. If the table doesn't exist, throws an error.
2. **Arity Check**: Validates that the number of values provided in the query exactly matches the number of columns defined in the schema.
3. **Constraint Validation Loop**: Iterates through every value to be inserted:
   - **`NOT_NULL` Check**: Rejects the insert if a constrained column receives an empty string or `"null"`.
   - **Type Check**: Attempts to parse the literal into the declared column type (e.g., parsing `"abc"` into an `INT` throws an error).
   - **Uniqueness Check**: If the column is `PRIMARY_KEY` or `UNIQUE`, the engine queries the `StorageEngine` to read the entire column from disk and checks if the value already exists. Throws a constraint violation if a duplicate is found.
4. **Physical Append**: If all validations pass, it iterates through the columns and calls `storageEngine.appendValue()` for each, appending the data to the respective `.dat` files on disk.
5. **Index Maintenance**: Calls `indexManager.insertRow()` so the `BitmapIndexManager` can dynamically update the in-memory bitsets for categorical columns without doing a full disk scan.

### 2.3 `SELECT` Execution
**Step-by-Step Algorithm:**
1. **Table Validation**: Fetches the `TableSchema`.
2. **Column Resolution**: If the query requested `*`, it replaces `*` with an ordered list of all column names from the schema. It then validates that all requested columns actually exist.
3. **WHERE Clause Validation**: Validates that the columns mentioned in the `WHERE` clause exist in the table.
4. **Row Filtering (Routing)**: Calls the core `getFilteredRowIndexes()` method.
   - The engine first asks the `BitmapIndexManager` to evaluate the WHERE clause.
   - **Fast Path (Index Hit)**: If the index manager successfully resolves the query using bitmaps, it returns the exact list of matching row indices.
   - **Slow Path (Sequential Scan)**: If the index manager returns `null` (meaning a column is numeric, unindexed, or evicted), the Query Engine falls back to a full Sequential Scan. It reads the columns involved in the WHERE clause from disk, loops over every single row, and manually evaluates the conditions in Java logic (`evaluateRow()`).
5. **Data Hydration**: For each column requested in the `SELECT`, it reads the entire column from the `StorageEngine` into memory.
6. **Result Assembly**: It iterates through the list of matching row indices obtained in Step 4. For each index, it pulls the specific value from the loaded column arrays and formats them into a tab-separated string output.

### 2.4 `UPDATE` Execution
**Step-by-Step Algorithm:**
1. **Validation**: Validates the table exists, the `SET` column exists, and the `WHERE` clause columns exist.
2. **Row Filtering**: Calls `getFilteredRowIndexes()` (using the exact same Fast Path / Slow Path routing as `SELECT`) to get the list of row indices that need to be updated.
3. **Current State Retrieval**: Reads the entire column being updated from the `StorageEngine` into memory. This is required so the engine knows the *old* value before it is overwritten.
4. **Update Loop**: Iterates through the list of matching row indices:
   - Identifies the `oldValue` for the current row.
   - Calls `storageEngine.updateValue()` to perform a random-access overwrite in the `.dat` file.
   - Calls `indexManager.updateValue(tableName, colName, rowIndex, oldValue, newValue)`. This allows the Bitmap Index to clear the bit from the old value's bitset and set it in the new value's bitset in O(1) time.

### 2.5 `DELETE` Execution
**Step-by-Step Algorithm:**
1. **Validation**: Validates the table and the `WHERE` clause columns.
2. **Row Filtering**: Calls `getFilteredRowIndexes()` to get the list of row indices targeted for deletion.
3. **Reverse Deletion Loop**: Iterates through the matching row indices **in reverse order** (from highest index to lowest).
   - *Why reverse?* Deleting row 5 causes row 6 to shift down and become the new row 5. If we deleted sequentially, the row indices we intended to delete would shift out from under us. Deleting from bottom to top prevents this shifting bug.
   - Calls `storageEngine.deleteRow()` for each index.
4. **Index Rebuild**: Deleting rows inherently shifts the logical indices of all subsequent rows. Because the `BitmapIndexManager` relies on precise positional indices to map bits to rows, an in-place update is impossible. The engine calls `indexManager.buildIndex(tableName)` to force a complete re-read and rebuild of the bitmap indexes from the new state of the disk.

---

## 3. The `getFilteredRowIndexes()` Core Logic
This is the central routing method that determines how fast a query runs.

1. **No WHERE Clause**: Returns all row indices `[0, 1, ... N-1]`.
2. **Try Fast Path**: Calls `indexManager.getFilteredRowIndexes()`.
   - If the WHERE clause contains **only** categorical columns that passed both the Type Gate and the Cardinality Gate, the Bitmap Manager will perform rapid bitwise operations (`AND`/`OR`) and return a `List<Integer>`.
3. **Fallback to Slow Path**: If the Bitmap Manager returns `null` (because it encountered an `INT` column, or a `VARCHAR` column that was evicted due to having 10,000 unique names), the Query Engine executes a fallback scan:
   - Identifies which columns are needed to evaluate the WHERE clause.
   - Loads those specific columns entirely into RAM.
   - Loops `for (int i = 0; i < totalRows; i++)`
   - Calls `evaluateRow(i, ...)` which tests operators like `>`, `<`, `=`, `!=` on the raw strings/numbers.
   - Returns the accumulated list of matching `i` indices.
