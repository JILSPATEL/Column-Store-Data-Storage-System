# The Lifecycle of the Bitmap Index

This document explores the life events of the Bitmap Index within the Column-Store Database. It explains **when** the index is born, **how** it remembers data, and **whether** it survives a system reboot.

---

## 1. Does the Bitmap Index Persist on Disk?

**No.** The current implementation of the `BitmapIndexManager` is strictly an **In-Memory Cache**. 

Unlike the core table data which is securely serialized to disk as persistent binary `*.bin` files, the Bitmap Index is entirely volatile. It lives inside the high-speed RAM (Random Access Memory) allocated to the Java Virtual Machine.

- **Why?** Writing indexes to disks (like creating `.idx` B-Tree files) requires complex persistence logic and consumes hard drive space. Because `BitSet` arrays are extraordinarily compact (thousands of rows take just a few kilobytes), storing them entirely inside ultra-fast RAM offers the most extreme query performance boost with negligible memory overhead. 

Because it doesn't persist, the database **must rebuild** the index from scratch every time it starts up!

---

## 2. When is the Index Created?

The index experiences four distinct life events:

### Event A: The Database Boots (Full Build)
When the user initializes the `DatabaseAPI`, the engine powers up. Before accepting any user queries, the API calls `indexManager.initializeAll()`. 
The engine scans the `metadata/` files to discover existing tables, reads every single `.bin` file, and actively caches the structural maps.

### Event B: A New Row is Inserted (Incremental Update)
When an `INSERT INTO` command runs, the index does not rebuild. That would be too slow! 
Instead, it looks at the newly appended row (let's say it's row `#5`) and injects a single `1` into the specific `BitSet` vectors corresponding to the inserted values.

### Event C: A Value is Updated (Incremental Swap)
During an `UPDATE` command, the index retrieves the `BitSet` for the *Old Value* and clears the bit at that row via `.clear(idx)`. It then retrieves the `BitSet` for the *New Value* and sets the bit via `.set(idx)`.

### Event D: A Row is Deleted (Full Rebuild)
Because the `BinaryStorageEngine` hides deleted records and dynamically shifts the "Logical Row Indices" for all rows that come after a deletion, the existing `BitSet` integer offsets instantly become obsolete. 
To correct this alignment safely, the `BitmapIndexManager` clears everything and aggressively reads the `.bin` files again to perform a full system rebuild.

---

## 3. Visual Representation: How is the Index Built?

Let's visualize the exact programmatic loop that takes place during **Event A** (System Boot or Rebuild).

Imagine the `fincances` table currently has `3` rows in `tax_rate.bin`.

### Time Step 1: Blank Slate Array
The `IndexManager` reads `tax_rate.bin` into a standard `List<String>`.
```text
List [ Index 0: "0.15", Index 1: "0.20", Index 2: "0.15" ]
```

### Time Step 2: Iteration Loop `i = 0`
The index processes the first value: `"0.15"` at index `0`.
It creates a new `BitSet` block for `"0.15"` and marks bit `0`.

```text
Map Key: "0.15"
+----+----+----+
|  1 |  0 |  0 |  (Bit 0 is enabled!)
+----+----+----+
```

### Time Step 3: Iteration Loop `i = 1`
The index processes the second value: `"0.20"` at index `1`.
It creates a new `BitSet` block for `"0.20"` and marks bit `1`.

```text
Map Key: "0.15"
+----+----+----+
|  1 |  0 |  0 |
+----+----+----+

Map Key: "0.20"
+----+----+----+
|  0 |  1 |  0 |  (Bit 1 is enabled!)
+----+----+----+
```

### Time Step 4: Iteration Loop `i = 2`
The index processes the third value: `"0.15"` at index `2`.
It accesses the *existing* `BitSet` block for `"0.15"` and marks bit `2`.

```text
Map Key: "0.15"
+----+----+----+
|  1 |  0 |  1 |  (Bit 2 is enabled alongside Bit 0!)
+----+----+----+

Map Key: "0.20"
+----+----+----+
|  0 |  1 |  0 |
+----+----+----+
```

### The Build is Complete!
The Java program has successfully transformed a linear list traversal into a high-speed clustered dictionary mapping framework utilizing lightweight 1-bit boolean flags!
