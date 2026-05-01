# Column-Store vs. Row-Store Architecture

This document explains the fundamental difference between traditional row-store databases and our custom column-store architecture, and why this design choice was made for the project.

## 1. Traditional Row-Store Architecture

In a standard relational database like MySQL or PostgreSQL, data is stored **row by row**. This means all the attributes of a single record are kept together on the disk.

**Example data structure for `employees`:**
```text
Row 1:  [1, "Alice", "Engineering", 80000]
Row 2:  [2, "Bob",   "HR",          50000]
Row 3:  [3, "Charlie","Engineering", 90000]
```

### Pros and Cons of Row-Store:
- **Pros:** Extremely fast for adding new records (`INSERT`) or fetching an entire record (`SELECT * FROM employees WHERE id = 1`).
- **Cons:** Slow for analytical queries. If you want to run `SELECT AVG(salary) FROM employees`, the database has to read the entire rows from the disk just to extract the salary values. This causes a lot of unnecessary Disk I/O.

---

## 2. Our Column-Store Architecture

In our system, we use a **Column-Store Architecture**. Instead of storing an entire row together, we store **each column in its own separate file**. 

**How the same data is stored:**
```text
id.bin:      [1, 2, 3]
name.bin:    ["Alice", "Bob", "Charlie"]
dept.bin:    ["Engineering", "HR", "Engineering"]
salary.bin:  [80000, 50000, 90000]
```

When a user runs an `INSERT` command, the `BinaryStorageEngine` splits the row apart and appends each individual value to its respective `.bin` file.

### Why Did We Choose Column-Store?

1. **Fast Analytical Queries:** If you query `SELECT salary FROM employees`, the database engine ONLY opens and reads `salary.bin`. The `id`, `name`, and `dept` files are completely ignored. This drastically reduces the amount of data read from the disk, making queries much faster.
2. **Data Compression:** Because each file contains only one type of data (e.g., `id.bin` only contains integers), the data is highly uniform. This allows for excellent compression rates (though our system currently focuses on raw binary storage for speed).
3. **Natural Fit for Bitmap Indexing:** Since columns are isolated, it is very easy to generate per-column indexes like our Bitmap Indexing system.

---

## 3. Summary for Viva

If the examiner asks: *"Why did you build a Column-Store instead of a normal database?"*

**Answer:** "A traditional row-store saves entire records together, which is great for transactional workloads but terrible for analytics because it reads unnecessary data. Our database is a column-store, meaning every column is an isolated file. This allows our `QueryEngine` to perform targeted reads—only fetching the exact files needed for a query—which dramatically reduces Disk I/O and speeds up execution for analytical queries."
