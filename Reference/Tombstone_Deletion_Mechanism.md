# Tombstone Deletion Mechanism

This document explains how data is deleted in our database system. Unlike a text editor where deleting a character actually removes it, deleting data from binary files requires a special approach called Tombstoning.

## 1. The Deletion Problem

In our column-store system, every column is saved as a solid `.bin` file on the hard drive. 

If we have 1,000,000 records and a user deletes record #5, physically removing those bytes from the middle of the file would require the computer to shift the remaining 999,995 records up by one position. This massive shifting operation is incredibly slow and would freeze the database for every single `DELETE` command.

## 2. The Tombstone Solution

Instead of physically removing the data, we use a **Tombstone Flag**. 

In our binary format, every single piece of data written to a file is prefixed with a 1-byte status flag:
- `0x00`: Means the row is **ALIVE**.
- `0xFF`: Means the row is **DELETED** (a tombstone).

When a user runs a `DELETE FROM employees WHERE id = 5`, the engine simply navigates to the exact byte position of row 5 and changes its 1-byte flag from `0x00` to `0xFF`. The old data stays in the file, but the flag tells the database to ignore it.

**Example File Structure Before Delete:**
```text
[ALIVE (0x00)] [Value: 42]
[ALIVE (0x00)] [Value: 99]
```

**Example File Structure After Deleting the First Row:**
```text
[DELETED (0xFF)] [Value: 42]   <-- Tombstone placed
[ALIVE (0x00)]   [Value: 99]
```

When the `QueryEngine` reads the column files, it encounters the `0xFF` flag and completely skips the data, making it appear as though it was completely erased.

## 3. Physical vs. Logical Indexes

Because deleted records still take up physical space, we have to distinguish between two types of indexes:
- **Logical Index**: What the user sees (e.g., they only see 5 rows, indexed 0 to 4).
- **Physical Index**: The actual position in the file including deleted rows.

Our `BasePersister` handles the mapping. When a user updates Logical Row `2`, the persister scans the file from the start, counting only ALIVE flags until it reaches the 2nd ALIVE record, ensuring that tombstones don't ruin the row ordering.

To ensure safe deletion of multiple rows, our `QueryEngine` deletes rows from **bottom to top** (highest index to lowest). If we deleted from the top, all the logical indexes below would instantly shift, causing subsequent deletes in the same query to target the wrong rows.

---

## 4. Summary for Viva

If the examiner asks: *"How do you handle deleting rows from the binary files?"*

**Answer:** "We use a Tombstone Deletion mechanism. Physically removing bytes from the middle of a binary file would force us to rewrite the rest of the file, which is too slow. Instead, every record starts with a 1-byte flag. When a row is deleted, we overwrite that flag with a `0xFF` (Deleted) tombstone marker. During query execution, our storage engine simply ignores any record with a tombstone flag, providing instant, O(1) deletions without file shifting."
