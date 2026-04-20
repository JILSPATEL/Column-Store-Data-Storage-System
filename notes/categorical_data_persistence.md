# Categorical Data Persistence in Column-Store

Categorical data refers to "text-based" or "other" types of data that aren't simple numbers (e.g., `STRING`, `VARCHAR`, `TEXT`, `CHAR`). In our column-oriented storage system, we use a technique called **Dictionary Encoding** to store this data efficiently.

## The Problem
Normally, text can be any length (e.g., "NY" vs "San Francisco"). If we store these directly in a binary file, every row would have a different size. This makes it impossible to jump directly to a specific row (like row #500) without reading everything before it. It also makes updates very slow.

## The Solution: Dictionary Encoding
To solve this, we split the storage into two parts: a **Main Data File** and a **Dictionary**.

### 1. The Main Data File (`.bin`)
This file stores the actual "rows" for a column, but instead of storing the text, it stores a fixed-length **ID number** (4 bytes).
- **Format**: `[Tombstone Flag (1 byte)] [Dictionary ID (4 bytes)]`
- **Result**: Every single row is exactly 5 bytes.
- **Why?**: Because every row is the same size, we can calculate exactly where row `N` starts in the file (`offset = 8 + N * 5`). This makes searching, updating, and deleting extremely fast.

### 2. The Dictionary File (`.dict`)
This is a small "helper" file that maps ID numbers back to the original text.
- **Content**: It contains a list of unique strings found in that column.
- **ID 0**: "Engineering"
- **ID 1**: "HR"
- **ID 2**: "Finance"
- **Efficiency**: If "Engineering" appears 1 million times in your database, the word "Engineering" is only stored **once** in the dictionary. The main file just stores the number `0` one million times.

## How Operations Work

### Inserting Data
When you insert "Alice" into a `name` column:
1.  We check if "Alice" is already in the dictionary.
2.  If not, we add it and give it a new ID (e.g., ID 5).
3.  We write the number `5` into the `.bin` file.

### Reading Data
When you run a `SELECT`:
1.  We load the dictionary into memory.
2.  We read the ID numbers from the `.bin` file.
3.  We swap the IDs for the actual words (ID 5 → "Alice") before showing them to you.

### Updating Data
If you change "Alice" to "Bob":
1.  We find the ID for "Bob" (or create one).
2.  We jump directly to the exact spot in the `.bin` file where the ID was stored and overwrite it with the new ID.

## Summary of Benefits
*   **Speed**: Faster random access and updates due to fixed-width rows.
*   **Space**: Saves massive amounts of disk space for data with repetitive categories (low cardinality).
*   **Consistency**: Works seamlessly with numerical data columns in the same table.
