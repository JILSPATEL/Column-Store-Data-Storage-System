# The Journey of a Query

Ever wonder what happens exactly when you run a command in the system? This document traces the step-by-step journey of a simple `SELECT` query from the moment it leaves the user's hands until it reads deep from the `.bin` storage files.

Let's follow this example query:
**`SELECT id, salary FROM employees WHERE dept_id = 10`**

---

## Step 1: The User Interface (`DatabaseAPI`)

The journey begins at the entry point of the database: `DatabaseAPI`.
The query is currently just a block of plain text. The API's job is to pass this unstructured text safely to the internal components.

```text
[ USER ]  ---- "SELECT id, salary..." ---->  [ DatabaseAPI ]
```
**Form:** Raw `String`

---

## Step 2: The Translator (`QueryParser`)

The `DatabaseAPI` sends the raw text to the `QueryParser`. The parser breaks down the sentence word-by-word (tokenization) and figures out what type of command it is. 

It translates the raw text into a neat, organized Java object called a `SelectQuery`.

```text
[ QueryParser ]
      │
      ▼
+-----------------------+
| SelectQuery Object    |
|-----------------------|
| Table: "employees"    |
| Cols:  ["id", "salary"]
| Filter: "dept_id"     |
| Op:     "="           |
| Value:  "10"          |
+-----------------------+
```
**Form:** A structured Java `Query` Object.

---

## Step 3: Validation (`QueryEngine` & `SchemaManager`)

The `QueryEngine` takes the `SelectQuery` object and asks the `SchemaManager` for the structural blueprints (the `TableSchema`) of the "employees" table. 

It checks:
1. *Does the "employees" table exist?*
2. *Does it have "id", "salary", and "dept_id" columns?*

If any validation fails, the journey ends here and throws an error!

```text
[ QueryEngine ]  <---- "Give me blueprints!" ---->  [ SchemaManager ]
```
**Form:** Still a `Query` object, now validated against a `TableSchema`.

---

## Step 4: The Fast-Lane Lookup (`BitmapIndexManager`)

This is where the magic happens! Before the `BitmapIndexManager` existed, the system would have to read every single employee's `dept_id` from the hard drive to find who works in department 10. 

Now, the `QueryEngine` asks the `BitmapIndexManager`: *"Which rows have `dept_id = 10`?"*
The index manager looks up its high-speed RAM dictionary and instantly returns a list of logical memory indexes. It uses zeroes and ones to map the exact locations!

```text
[ QueryEngine ]
      │ (Ask for dept 10)
      ▼
[ BitmapIndexManager (RAM) ]
      │
      ▼
+-----------------------------------+
| dept_id  [ Value = "10" ]         |
|                                   |
| Logical Row Index:                |
|  0    1    2    3    4    5       |
| [1]  [0]  [1]  [0]  [0]  [1]      |
|                                   |
| Matches found at Rows: 0, 2, 5    |
+-----------------------------------+
```
**Form:** A List of Integer IDs (`[0, 2, 5]`) representing matching rows.

---

## Step 5: The Hard Drive Retrieval (`BinaryStorageEngine`)

Now that the `QueryEngine` has the exact row numbers (`0, 2, 5`), it only needs to grab the requested data (`id` and `salary`).

It calls upon the `BinaryStorageEngine` to read the `.bin` files:
1. Opens `id.bin`
2. Opens `salary.bin`

Because we only need a few rows, we don't need to look at `dept_id.bin` at all anymore! The engine grabs the active records from the binary arrays for `id` and `salary`.

```text
[ BinaryStorageEngine (Disk I/O) ]
      │
      ▼
[ id.bin ] -----> Reads all active IDs -----> [1, 2, 3, 4, 5, 6]
[ salary.bin ] -> Reads all active Salaries -> [85k, 95k, 60k, 120k, 75k, 80k]
```
> *Note: By extracting indices `0, 2, 5`, the engine plucks out exactly `(1, 85k)`, `(3, 60k)`, and `(6, 80k)`.*

**Form:** Raw byte chunks decoded into Lists of Strings (`List<String>`).

---

## Step 6: Formatting and Return

The `QueryEngine` groups the extracted `id` and `salary` string arrays together and neatly aligns them into a printable ASCII table format. 

This beautifully formatted text block is handed back to the `DatabaseAPI`, which delivers it to the user's screen.

```text
  id    salary
----------------
  1     85000
  3     60000
  6     80000
(3 rows)
```

**The Journey is Complete!**
