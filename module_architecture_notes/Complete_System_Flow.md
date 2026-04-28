# Complete System Flow: How the Column Store Database Works (End-to-End)

Think of this database as an incredibly organized filing cabinet. Instead of grouping a person's entire profile (Name, Age, Department) into a single folder (like a traditional Row-Oriented SQL database), this system gives each attribute its own separate drawer (a Columnar Database). 

Here is exactly what happens behind the scenes from the moment you turn it on, to the moment you retrieve your data.

---

## 1. Booting Up (The CLI and API)
When you run the project, the `CLIClient` starts up. It creates a `DatabaseAPI` object, which acts as the general manager of the entire database. 
- The **SchemaManager** wakes up and reads all existing `.schema` (JSON) files to remember what tables exist.
- The **StorageEngine** gets ready to handle disk reading/writing.
- The **BitmapIndexManager** loads all the existing `.idx` files into memory so that queries can be lightning fast.

You are presented with a `cdb>` prompt.

---

## 2. Creating a Table 
**You type:** `CREATE TABLE Users (ID INT PRIMARY_KEY, Name VARCHAR, Age INT)`

1. **Parsing:** The `QueryParser` reads your string and figures out you want to create a table.
2. **Schema Manager:** The `SchemaManager` saves this blueprint into a `schema.json` file inside a new folder named `Users`.
3. **Storage Engine:** The `StorageEngine` checks this blueprint and realizes it needs to prepare to store data. However, in a columnar store, files are only created when actual data is inserted. The table's directory `data/Users/` is now ready.

---

## 3. Inserting Data 
**You type:** `INSERT INTO Users VALUES (1, 'Alice', 25)`

1. **Parsing & Validation:** The `QueryEngine` checks if `1` is an integer, `'Alice'` is a string, and `25` is an integer. It also checks the `PRIMARY_KEY` constraint on `ID` to make sure `1` doesn't already exist.
2. **Splitting to Columns:** Instead of saving "1, Alice, 25" as a single line in a text file, the `StorageEngine` splits them up:
   - It opens `ID.dat` and writes `1`.
   - It opens `Name.dat` and writes `Alice`.
   - It opens `Age.dat` and writes `25`.
   *(Because these are the very first records, they are all conceptually at "Row Index 0").*
3. **Updating the Index:** The `BitmapIndexManager` sees that row `0` was added. If there are indexes, it updates them. For example, in the "Age" index, it logs: *"Age 25 now exists at Row 0"*.

---

## 4. Querying Data (The Magic of Columnar + Bitmaps)
**You type:** `SELECT Name FROM Users WHERE Age = 25`

This is where the architecture shines.

1. **Understanding the Request:** The engine sees you only want the `Name` column. It completely ignores `ID.dat`. 
2. **Finding the Rows (The Fast Path):** 
   - The engine asks the `BitmapIndexManager`: *"Do you know which rows have Age = 25?"*
   - Because we keep our indexes in memory, the Index Manager instantly replies: *"Yes, Row 0."*
   - *(If we asked `Age = 25 AND Department = HR`, it would quickly do a bitwise AND on the two index arrays to find the matching rows instantly without reading any disks).*
3. **Fetching the Data:** Now the system knows it only needs Row 0. 
   - It goes directly to `Name.dat` and reads the value at Row 0, which is "Alice".
4. **Displaying:** The `CLIClient` prints "Alice" to your screen.

#### What if there is no index? (The Slow Path)
If you search for something without an index, the engine has to do a **Sequential Scan**. It reads the entire `Age.dat` file from top to bottom, checks every single number to see if it equals 25, records the matching row numbers, and *then* fetches the corresponding names.

---

## 5. Deleting and Updating
- **UPDATE:** If you `UPDATE Users SET Age = 26 WHERE Name = 'Alice'`, the engine uses the exact same logic to find Alice's row index. It then goes directly to `Age.dat` and replaces `25` with `26` at that specific row index. It also updates the index memory to reflect this change.
- **DELETE:** If you delete a row, the engine finds the row index. It then goes into **every single column file** (`ID.dat`, `Name.dat`, `Age.dat`) and removes the data at that specific position.

---

## Why this Architecture? (The Big Picture)
Traditional databases read whole rows into memory even if you only want one column. By splitting data into separate column files (`BinaryStorageEngine`) and using highly compressed bit-arrays for lookups (`BitmapIndexManager`), this project is capable of searching through millions of records and aggregating data (like "Average Age") incredibly fast, while using minimal CPU and RAM resources.
