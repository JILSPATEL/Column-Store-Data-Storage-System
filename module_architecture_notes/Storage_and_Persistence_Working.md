# Storage and Persistence Module Architecture

## Overview
The Storage module provides low-level disk I/O operations for reading, appending, updating, and deleting values in a purely columnar format. It completely abstracts away file handling from the QueryEngine.

## 1. Columnar Storage Layout
Instead of storing rows contiguously (like typical relational databases), this system stores each column in its own binary file.
- Directory Structure: `dataDir / <TableName> / <ColumnName>.dat`
- **Advantage**: If a query only needs 2 columns out of 100, the system only reads 2 files. CPU cache usage and Disk I/O are optimized.

## 2. Storage Engine (`BinaryStorageEngine.java`)
**Core Responsibilities:**
- **Create Table**: Creates the directory for the table.
- **Append**: Delegates to the appropriate persister to write a value to the end of the column file.
- **Read**: Reads the entire column file into memory as a `List<String>`.
- **Update**: Re-writes a specific element at a specific index. (Depending on the persister, this may involve rewriting the whole file or doing random access if fixed-size).
- **Delete**: Since it's columnar, deleting a row means removing the value at `rowIndex` from *every* column file belonging to that table.

## 3. Persisters Architecture (`cdb.storage.persistence`)
To handle different data types optimally, the system uses a hierarchy of Persisters extending `BasePersister`.

### 3.1 `NumericalPersister`
**Working:**
- Used for `INT`, `DOUBLE`, `LONG`, etc.
- Stores values in raw binary formats (e.g., 4 bytes for INT) rather than string representation. This dramatically reduces file size and improves read speeds.
- Uses `DataOutputStream` and `DataInputStream` or NIO `ByteBuffer` for fixed-length binary encoding.
- Updating/Deleting is easier for fixed-width records, as the offset can be calculated as `rowIndex * byteWidth`.

### 3.2 `StringPersister`
**Working:**
- Used for `VARCHAR`, `TEXT`.
- Strings are variable-length. The persister usually writes the length of the string first (e.g., as a 4-byte int), followed by the UTF-8 bytes of the string itself.
- **Read Algorithm**: Read integer `L`, read `L` bytes into string, repeat until EOF.

### 3.3 `CategoricalPersister`
**Working:**
- Optimized for columns with low cardinality (few unique values, e.g., 'MALE', 'FEMALE').
- Uses dictionary encoding. It maintains a dictionary mapping the string to a short integer (e.g., 'MALE' -> 0, 'FEMALE' -> 1).
- In the `.dat` file, it only stores the short integers.
- **Read Algorithm**: Reads the dictionary into memory, then reads the array of short integers, translating them back to strings on-the-fly.

## Storage Flow Example (Appending a row)
1. Query Engine calls `storageEngine.appendValue("Users", "Age", "25")`.
2. Storage Engine looks up the column type (INT).
3. It instantiates/retrieves a `NumericalPersister`.
4. Persister parses `"25"` to `int 25`.
5. Persister opens `data/Users/Age.dat` in append mode and writes 4 bytes `0x00 0x00 0x00 0x19`.
