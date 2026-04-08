# Binary Data Storage Format Report

This document provides an in-depth analysis of how data is stored within the `.bin` files of the `BinaryStorageEngine` in the Column-Store Data Storage System.

## Architecture Overview

The `BinaryStorageEngine` employs a fixed-width, column-oriented binary persistence model. Each column of a table is physically stored in its own separate file (e.g., `column_name.bin`). This approach ensures rapid sequential scans and precise random access by row index without needing to parse the entire file.

The file layout is split into two primary sections:
1. **Header**: Global metadata about the column (8 bytes).
2. **Records List**: A sequence of fixed-width records containing the tombstone status (deletion flag) and the encoded mathematical value.

---

## 1. File Header (8 Bytes)

Every `.bin` file begins with an 8-byte header located exactly at byte offset `0`. It is written with big-endian byte order (standard for Java's `DataOutputStream`).

| Byte Offset | Size (Bytes) | Data Type | Description |
| :--- | :--- | :--- | :--- |
| `0 - 3` | 4 | `INT32` | **Type Tag:** Specifies the data type of the column. |
| `4 - 7` | 4 | `INT32` | **Record Count:** The total number of records ever appended (including logically deleted ones). |

### Type Tags

The integer code present in bytes `0-3` determines the width of each subsequent record:

| Type Tag | SQL Type | Byte Width (Data) | Total Record Width (1B Tombstone + Data)|
| :---: | :--- | :---: | :---: |
| `1` | `BYTE` | 1 | 2 |
| `2` | `SHORT`| 2 | 3 |
| `3` | `INT` / `INTEGER`| 4 | 5 |
| `4` | `LONG` / `BIGINT`| 8 | 9 |
| `5` | `FLOAT` / `REAL`| 4 | 5 |
| `6` | `DOUBLE` / `DECIMAL`| 8 | 9 |
| `7` | `BOOLEAN` / `BOOL`| 1 | 2 |
| `8` | `BIGDECIMAL` / `NUMERIC`| 20 | 21 |

---

## 2. Record Structure

Starting from byte offset `8`, the records are stored contiguously. Each record has a total width equal to `1 + Data_Bytes`.

| Offset within Record | Size (Bytes) | Description |
| :--- | :--- | :--- |
| `0` | 1 | **Tombstone Flag:** Dictates record liveliness. |
| `1 ... W`| `W` | **Value:** The raw big-endian bytes of the encoded value. |

### Tombstone Flags
- `0x00` : **ALIVE.** The record holds a valid value.
- `0xFF` : **DELETED.** The record has been logically deleted. The value bytes still occupy space but are skipped during reads.

### Data Width Encodings
- **`BYTE`** (1 byte): Signed 8-bit integer.
- **`SHORT`** (2 byte): Signed 16-bit integer.
- **`INT`** (4 bytes): Signed 32-bit integer.
- **`LONG`** (8 bytes): Signed 64-bit integer.
- **`FLOAT`** (4 bytes): IEEE-754 single-precision float.
- **`DOUBLE`** (8 bytes): IEEE-754 double-precision float.
- **`BOOLEAN`** (1 byte): `0x00` = false, `0x01` = true.
- **`BIGDECIMAL`** (20 bytes): 
  - `4 bytes`: Int32 Scale.
  - `8 bytes`: Int64 Unscaled value.
  - `8 bytes`: Padding reserved for future expansion.

---

## Byte-Level Visual Representation

Below is a visual map of how a `.bin` file storing a sequence of `INT` values looks in memory or on disk. 

For an `INT` column, the **Type Tag** is `3`, **Data Width** is `4`, and **Total Record Width** is `5` bytes. Let's assume the file has `3` records total, where:
- Row 1: `42`
- Row 2: `100` *(Deleted)*
- Row 3: `-5`

```text
[ FILE HEADER - 8 bytes ]
Offsets (decimal):
0          1          2          3          4          5          6          7
+----------+----------+----------+----------+----------+----------+----------+----------+
|          Type Tag (e.g., 3 for INT)       |      Total Record Count (e.g., 3)         |
| 00000000 | 00000000 | 00000000 | 00000011 | 00000000 | 00000000 | 00000000 | 00000011 |
+----------+----------+----------+----------+----------+----------+----------+----------+

[ RECORD 0 - 5 bytes ] (Value = 42)
8          9          10         11         12
+----------+----------+----------+----------+----------+
|Tombstone |      Value (42)                           |
| 00000000 | 00000000 | 00000000 | 00000000 | 00101010 |
+----------+----------+----------+----------+----------+

[ RECORD 1 - 5 bytes ] - (DELETED, Value = 100)
13         14         15         16         17
+----------+----------+----------+----------+----------+
|Tombstone |      Value (100)                          |
| 11111111 | 00000000 | 00000000 | 00000000 | 01100100 |
+----------+----------+----------+----------+----------+

[ RECORD 2 - 5 bytes ] (Value = -5)
18         19         20         21         22
+----------+----------+----------+----------+----------+
|Tombstone |      Value (-5)                           |
| 00000000 | 11111111 | 11111111 | 11111111 | 11111011 |
+----------+----------+----------+----------+----------+
```

### Addressing Math

Because records are fixed-width, the file supports **O(1)** random access positioning.

To locate physical record index `N` (the 0-indexed count of all records):
1. **Record Width**: `width = 1 + dataWidth`
2. **Byte Offset**: `offset = 8 + (N * width)`

### Deletion and Updates in Action
- **Delete**: The `BinaryStorageEngine` scans to find the $i$-th ALIVE row. When it discovers the byte offset, it directly writes a single `0xFF` byte to the Tombstone offset without shifting the rest of the array.
- **Update**: Similarly, the engine jumps directly to `offset + 1` (skipping the tombstone byte) and overwrites the subsequent $W$ bytes of the mathematical value in place.
