# Text-to-Binary Conversion in Column-Store Database

This document explains exactly how our database converts plain text data into raw binary formats on the disk. It is designed to be easy to understand and covers all the details step-by-step.

## 1. The Big Picture

When a user types an `INSERT INTO ...` command or we load data, the data starts as normal text (Strings). If we save this as text, it takes up a lot of space and is very slow to read. 

To make the database fast and small, we convert this text into **Binary** (0s and 1s) and store it directly in `.bin` files using Java's built-in binary writers. 

Because we are a **Column-Store**, each column has its own file. For example, if we have a table `employees` with columns `id` and `name`, we create two files: `id.bin` and `name.bin`.

The core code that manages this overall process is the `BinaryStorageEngine` class (`cdb/storage/BinaryStorageEngine.java`).

---

## 2. Step-by-Step Data Flow

Here is exactly what happens when new data enters the system:

**Step 1: Data Arrives as Text**
The query parser reads the user's input. Even numbers are initially parsed as text (e.g., `"1234"`, `"John"`).

**Step 2: Routing to the Correct Engine**
The `StorageEngine` interface receives the request to save the data. Because we are using the binary implementation, the `BinaryStorageEngine` takes over.

**Step 3: Determining the Data Type**
The system checks the database schema to see what type of data the column expects. Is it a number (like `INT`, `DOUBLE`) or is it a text/string (like `VARCHAR`)?
Based on the data type, `BinaryStorageEngine` picks a specialized "Persister" to handle the exact conversion:
- For numbers: It uses `NumericalPersister` (`cdb/storage/persistence/NumericalPersister.java`)
- For strings: It uses `CategoricalPersister` (`cdb/storage/persistence/CategoricalPersister.java`)

---

## 4. Numeric Data Encoding

How do we convert a number like `"12345"` into binary?

**The "Silly" Concept:** 
In a text file, `"12345"` takes up 5 bytes (one byte per character). If the number is `"123456789"`, it takes 9 bytes. This means the size keeps changing!
In binary, an integer (`INT`) always takes exactly 4 bytes, whether it's the number `1` or `1,000,000`. This fixed size makes reading the data incredibly fast because the computer knows exactly where every row starts.

### The Conversion Process (`NumericalPersister`)
1. **Parsing:** The string `"12345"` is converted into a native Java `int` primitive using `Integer.parseInt()`.
2. **Writing Bytes:** The Java `DataOutputStream` writes the raw 4 bytes directly to the disk (`writeInt()`).

### The Structure of the `.bin` File for Numbers
Every numeric `.bin` file looks like this internally:

* **Header (Top of the file):**
  * `Tag` (4 bytes): An integer code representing the data type (e.g., `3` means `INT`, `6` means `DOUBLE`).
  * `Total Rows` (4 bytes): How many rows are in the file.
* **Row Data (Repeated for every row):**
  * `Alive/Deleted Flag` (1 byte): `0` means the row is deleted, `1` means it is active.
  * `Actual Value` (Fixed width): The raw binary data (e.g., 4 bytes for INT, 8 bytes for DOUBLE).

**Responsible Class:** `cdb.storage.persistence.NumericalPersister`

---

## 5. String Data Encoding (Dictionary Encoding)

How do we convert text like `"Engineering"` into binary? Text size varies, and saving the same long word over and over again wastes massive amounts of space.

**The "Silly" Concept:** 
Imagine writing a book where the name "Rumpelstiltskin" appears 10,000 times. That's a lot of writing! Instead, you could create a dictionary at the back of the book that says:
`0 = Rumpelstiltskin`
Then, in the book, you just write `0` every time. `0` takes up much less space than the full name. 
This is exactly what **Dictionary Encoding** does!

### The Conversion Process (`CategoricalPersister`)
When storing string columns, the system creates **two** files instead of one:
1. **`.dict` (Dictionary File):** Stores the unique strings.
2. **`.bin` (Data File):** Stores integer IDs pointing to the dictionary.

**Step-by-Step:**
1. **Lookup:** The string `"Engineering"` arrives. The `CategoricalPersister` checks the `.dict` file to see if `"Engineering"` already exists.
2. **Add if New:** If it's the first time we see `"Engineering"`, we add it to the dictionary and give it a new ID (e.g., ID `0`).
3. **Write ID:** Instead of writing the word "Engineering" into the column data, we write the integer `0` into the `.bin` file as a 4-byte `INT`.

### The Structure of the `.bin` File for Strings
* **Header:**
  * `Tag` (4 bytes): Code representing it's a string type.
  * `Total Rows` (4 bytes): Total rows.
* **Row Data:**
  * `Alive/Deleted Flag` (1 byte): `1` for active.
  * `Dictionary ID` (4 bytes): The integer ID pointing to the word in the `.dict` file.

This means reading string columns is as fast as reading integer columns, because every row takes exactly 5 bytes (1-byte flag + 4-byte ID), no matter how long the text is!

**Responsible Class:** `cdb.storage.persistence.CategoricalPersister`

---

## 6. Summary for Viva

If the examiner asks: *"How do you store your data?"*
**Answer:** "We use a Binary Column-Store architecture. We separate columns into individual files. Numeric data is written as fixed-width raw bytes using `DataOutputStream` so numbers always take the exact same amount of space. String data is converted using Dictionary Encoding, where unique strings are kept in a `.dict` file, and the main `.bin` file only stores a 4-byte integer ID pointing to that dictionary. This ensures all our column data is fixed-width, making lookups extremely fast."
