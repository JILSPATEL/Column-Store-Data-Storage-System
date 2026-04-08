# Bitmap Index Integration Report

This document outlines the architecture, algorithmic efficiency, and visual structures behind the integration of the Bitmap Index module into the Column-Store Database system.

## 1. Architectural Overview

The Bitmap Index is a secondary indexing layer (`BitmapIndexManager`) running alongside the default `BinaryStorageEngine`. Rather than reading gigabytes of data files sequentially to find matching values, a bitmap index leverages heavily optimized Java `BitSet` structures to maintain an in-memory mapping of which rows correspond to which discrete values.

### Query Routing Flow:
1. **Initialize (`DatabaseAPI`)**: Uses `IndexManager.initializeAll()` to eagerly cache bitsets indexing all values currently physically placed inside the database.
2. **Execute Lookup (`QueryEngine`)**: Before filtering rows for `SELECT`, `UPDATE`, or `DELETE`, the engine yields control to `IndexManager.getFilteredRowIndexes()`.
3. **Index Access (`BitmapIndexManager`)**: Retrieves a constant-time mapping of values and evaluates conditions using bit operations (like `BitSet.or()`). It returns the logical array indexes directly.

---

## 2. Theoretical Complexity Improvements

Integrating the Bitmap Index shifts the time complexity of core query clauses dramatically.

| Operation | Previous Model (No Index) | Bitmap Indexing | Context |
| :--- | :--- | :--- | :--- |
| **Exact Match Lookup (`=`)** | `O(N)` Scanning | **`O(1)` Constant** | Direct dictionary fetch yields the `BitSet`. |
| **Range Lookups (`<, >`)** | `O(N)` Scanning | **`O(K * b/64)`** | `K` = unique keys matched. Merging subsets uses hardware 64-bit word operations (`BitSet.or()`). Highly parallel and cache efficient. |
| **Insertion (`INSERT`)** | `O(1)` Append | **`O(1)` Write** | Setting `.set(idx)` natively adds 1 integer mapping at `O(1)`. |
| **Update (`UPDATE`)** | `O(N)` Lookups | **`O(1)` Matrix Swap** | Erasing an old bit and injecting the new bit index is `2 × O(1)` operations. |
| **Deletions (`DELETE`)** | `O(N)` Search | **`O(N)` Rebuild** | To align with `BinaryStorageEngine`'s hidden dynamic shifting (where missing indexes cause arrays to shrink), the indexes must perform an `O(N)` rebuild post-delete. |

---

## 3. Structural Byte-Mapping Visualizer

Here is a visual map depicting how the **Value to BitSet Mapping** natively transforms an individual logical table column into bitwise identifiers.

Let's assume a table `employees` with a column `dept_id` and `5` currently alive records:
```text
Row 0: dept_id = 10
Row 1: dept_id = 20
Row 2: dept_id = 10
Row 3: dept_id = 30
Row 4: dept_id = 40
```

Inside `BitmapIndexManager`, the data structure expands and aligns these row indices into memory bit vectors:

```text
< Bitmap Index Map for Table "employees", Column "dept_id" >

[ Value = "10" ] Memory Allocated: 1 Byte
Logical Row Index (Offsets):
0          1          2          3          4          5          6          7
+----------+----------+----------+----------+----------+----------+----------+----------+
|    1     |    0     |    1     |    0     |    0     |    0     |    0     |    0     |
+----------+----------+----------+----------+----------+----------+----------+----------+

[ Value = "20" ] Memory Allocated: 1 Byte
Logical Row Index (Offsets):
0          1          2          3          4          5          6          7
+----------+----------+----------+----------+----------+----------+----------+----------+
|    0     |    1     |    0     |    0     |    0     |    0     |    0     |    0     |
+----------+----------+----------+----------+----------+----------+----------+----------+

[ Value = "30" ] Memory Allocated: 1 Byte
Logical Row Index (Offsets):
0          1          2          3          4          5          6          7
+----------+----------+----------+----------+----------+----------+----------+----------+
|    0     |    0     |    0     |    1     |    0     |    0     |    0     |    0     |
+----------+----------+----------+----------+----------+----------+----------+----------+

[ Value = "40" ] Memory Allocated: 1 Byte
Logical Row Index (Offsets):
0          1          2          3          4          5          6          7
+----------+----------+----------+----------+----------+----------+----------+----------+
|    0     |    0     |    0     |    0     |    1     |    0     |    0     |    0     |
+----------+----------+----------+----------+----------+----------+----------+----------+
```

Notice how a query execution for `SELECT * FROM employees WHERE dept_id = 10` processes data:
Instead of looping:
`if (val[0] == 10)`, `if (val[1] == 10)` ... which invokes $N$ expensive conditional CPU branch instructions,
The system queries: `Map.get("10")` -> instantly returning the entire populated vector block `[ 1, 0, 1, 0, 0 ]`.

### Storage Efficacy

Because `BitSet` allocates precisely 1-bit per mapped row, it uses drastically less memory overhead than retaining Java primitive types. For an index of a million rows, `1,000,000` bits equals roughly `122 KB`. This makes retaining column indices entirely inside high-speed RAM highly efficient and desirable for sub-millisecond filtering.
