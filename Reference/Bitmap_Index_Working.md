# Bitmap Index Module — Working (Current Implementation)

## Overview
The `BitmapIndexManager` accelerates `WHERE` clause lookups by maintaining in-memory bit-arrays that map each distinct value in a column to the set of rows containing that value. It uses a strict **two-gate policy** to decide which columns to index, preventing both numeric and high-cardinality string columns from blowing up memory.

---

## The Core Problem: When NOT to Index

Consider `Age INT` with 10,000 rows (range 18–80): 62 distinct values, each needing a 10,000-bit BitSet — that's 62 vectors. For `Salary DOUBLE` with near-unique values, you'd get one vector per row, completely defeating the purpose.

But the same problem exists for `VARCHAR` too: a `Name VARCHAR` column where every entry is unique (Alice, Bob, Carol…) also creates one vector per row, just as wasteful as `Salary DOUBLE`.

The system solves this with two independent gates:

---

## Gate 1: Type Gate — `ColumnSchema.isCategorical()`

The **first and unconditional gate**. Implemented in `ColumnSchema.java`:

```java
private static final Set<String> CATEGORICAL_TYPES = Set.of(
    "STRING", "VARCHAR", "TEXT", "CHAR",
    "BOOLEAN", "BOOL"
);

public boolean isCategorical() {
    return CATEGORICAL_TYPES.contains(type.toUpperCase());
}
```

**Rule:** If a column's declared type is numeric (`INT`, `LONG`, `DOUBLE`, `FLOAT`, etc.), it is **unconditionally excluded**. No cardinality check is ever run. No exception.

**Why type-based, not data-based?** Numeric types represent continuous measurements. Even if an `Age` column happens to have only 3 distinct values today, the type guarantee is gone tomorrow when new ages are added. The decision is deterministic at schema-definition time.

| Type | Categorical? | Index Built? |
|---|:---:|:---:|
| `STRING`, `VARCHAR`, `TEXT`, `CHAR` | ✅ | Checked by Gate 2 |
| `BOOLEAN`, `BOOL` | ✅ | Checked by Gate 2 |
| `INT`, `LONG`, `SHORT`, `BYTE` | ❌ | Never |
| `FLOAT`, `DOUBLE`, `DECIMAL` | ❌ | Never |
| `BIGINT`, `NUMERIC`, `BIGDECIMAL` | ❌ | Never |

---

## Gate 2: Cardinality Gate — `exceedsCardinalityThreshold()`

The **second gate**, applied only after Gate 1 passes. Even a `VARCHAR` column gets skipped if its distinct values are too dense:

```java
private static final int    MAX_DISTINCT_VALUES   = 100;
private static final double MAX_CARDINALITY_RATIO = 0.50;
private static final int    MIN_ROWS_FOR_RATIO    = 10;

private boolean exceedsCardinalityThreshold(int distinctCount, int totalRows) {
    // Absolute cap — always enforced regardless of row count
    if (distinctCount > MAX_DISTINCT_VALUES) return true;
    // Ratio cap — only enforced once table is large enough to be meaningful
    if (totalRows >= MIN_ROWS_FOR_RATIO) {
        double ratio = (double) distinctCount / totalRows;
        if (ratio > MAX_CARDINALITY_RATIO) return true;
    }
    return false;
}
```

**Two conditions trigger eviction:**

| Condition | Threshold | Always Applied? |
|---|---|:---:|
| Absolute cap: too many distinct values | `distinctCount > 100` | ✅ Yes |
| Ratio cap: too sparse relative to rows | `distinct/total > 0.50` when `rows >= 10` | Only if ≥ 10 rows |

**Why defer the ratio check until 10 rows?** A table with 3 rows and 2 distinct values (ratio = 0.67) looks high-cardinality, but it's a tiny table with genuinely categorical data. We shouldn't penalize small tables.

**Examples:**

| Column | Distinct | Rows | Gate 2 Result |
|---|---|---|---|
| `Department VARCHAR` | 3 | 10,000 | ratio=0.0003 ✅ INDEXED |
| `Status VARCHAR` | 2 | 5,000 | ratio=0.0004 ✅ INDEXED |
| `Name VARCHAR` | 10 | 10 | ratio=1.0 > 0.50, rows≥10 ❌ SKIPPED |
| `Email VARCHAR` | 9,800 | 10,000 | absolute 9800 > 100 ❌ SKIPPED |
| `Tag VARCHAR` | 3 | 5 | rows < 10, no ratio check ✅ INDEXED |

---

## In-Memory Index Structure

```
indexes
 └── "Employees"
      ├── "Department"           ← categorical, low-cardinality → INDEXED
      │    ├── "HR"      → BitSet{0, 4, 7}
      │    ├── "IT"      → BitSet{1, 2, 5, 8}
      │    └── "Finance" → BitSet{3, 6, 9}
      ├── "Active"               ← BOOLEAN → INDEXED
      │    ├── "true"   → BitSet{0, 2, 5, 7, 8}
      │    └── "false"  → BitSet{1, 4, 6, 9}
      └── (Name absent)         ← 10 unique values in 10 rows → EVICTED by Gate 2
          (ID absent)           ← INT → excluded by Gate 1
          (Salary absent)       ← DOUBLE → excluded by Gate 1

evictedColumns
 └── "Employees" → {"Name"}     ← evicted columns are permanently tracked
```

---

## Index Lifecycle

### Startup — `buildIndex(tableName)`
1. Reads `TableSchema` for the table.
2. For each column, applies **Gate 1** (`isCategorical()`). Numeric types → skip, logged.
3. Reads all values for categorical columns via `StorageEngine.readColumn()`.
4. Applies **Gate 2** (cardinality check). High-cardinality → skip, logged, added to `evictedColumns`.
5. Both gates passed → builds `colIndex` HashMap (value → BitSet) by iterating all rows.

### INSERT — `insertRow()`
1. Checks **Gate 1** per column. Non-categorical → skip.
2. Checks `evictedColumns`. Previously evicted → **permanently skip** (no re-indexing).
3. For all other categorical columns: uses `computeIfAbsent` so new tables created *after* startup get their index lazily initialized on the first insert.
4. Sets bit `newIdx` in the appropriate value's BitSet.
5. Post-insert **Gate 2 check**: if the new distinct value pushed the column over threshold → remove from `tableIndexes`, add to `evictedColumns`. Column uses sequential scan from this point forward.

### UPDATE — `updateValue()`
Atomic bit move — O(1):
1. If column not in `tableIndexes` → no-op (not indexed or evicted).
2. **Clear** bit `rowIndex` from `oldValue`'s BitSet.
3. **Set** bit `rowIndex` in `newValue`'s BitSet.

### DELETE — triggers `buildIndex()` rebuild
Row deletion shifts all logical indices. `QueryEngine` calls `buildIndex()` after any DELETE. This clears `evictedColumns` for that table and re-evaluates all columns from the current data on disk.

---

## Query-Time Filtering — `getFilteredRowIndexes()`

### Case A: No WHERE clause
Returns `[0, 1, ..., N-1]` from `tableRowCounts`. No disk I/O.

### Case B: All conditions reference indexed (categorical, low-cardinality) columns → **Bitmap Fast Path**

Example: `WHERE Department = IT AND Active = true`

1. For each condition, calls `evaluateConditionWithIndex()`.
2. If column is absent from `tableIndexes` (numeric or evicted by Gate 2) → returns `null` → triggers Case C.
3. For `=`: returns exact `BitSet` for the value.
4. For `!=`: creates all-ones BitSet of `totalRows`, then `andNot(exactMatch)`.
5. Range operators (`>`, `<`, etc.) on categorical columns → **empty BitSet** + warning (string ordering is meaningless for bitmaps).
6. Combines all condition BitSets: `AND` → `.and()`, `OR` → `.or()`.
7. Converts resulting BitSet to `List<Integer>` of matching row indices.

### Case C: ANY condition references an unindexed column → **Sequential Scan Fallback**

If `evaluateConditionWithIndex()` returns `null` for any condition, the entire WHERE clause falls back to a full sequential scan in `QueryEngine`. This handles:
- Numeric comparisons: `Age > 30`, `Salary >= 50000`
- Evicted categorical columns: `Name = Alice` (after eviction)
- Mixed clauses: `Department = IT AND Age > 30` (mixed → full scan)

---

## Decision Flow

```
INSERT/SELECT arrives
       │
       ▼
For each column in WHERE or schema:
  ┌─────────────────────────────────┐
  │ Gate 1: col.isCategorical()?    │
  │  NO  → skip forever (numeric)  │
  │  YES → proceed to Gate 2        │
  └─────────────────────────────────┘
               │ YES
               ▼
  ┌─────────────────────────────────────────────┐
  │ Gate 2: exceedsCardinalityThreshold()?       │
  │  YES → add to evictedColumns, skip forever  │
  │  NO  → build/update BitSet                  │
  └─────────────────────────────────────────────┘
               │ NO
               ▼
         BitSet updated in memory
               │
               ▼
  Query: all conditions indexed?
    YES → AND/OR BitSets → row list (fast)
    NO  → return null → QueryEngine sequential scan (fallback)
```
