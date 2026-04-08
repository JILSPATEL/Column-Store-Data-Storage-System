# Bitmap Index Strategy Update Report

## The Problem With Bitmap Indexes
A **Bitmap Index** is like a massive checklist that the database keeps in its working memory (RAM). When you look for data, instead of reading through real records line-by-line, the database quickly grabs the checklist, reads the '1's and '0's, and instantly knows where the matches are. It works incredibly fast. 

However, bitmap indices have an Achilles heel: **High Cardinality** (when a column has a lot of *unique* values). 

For example, tracking a YES/NO `is_active` column is fantastic. There are only 2 distinct values ("true" or "false"), so the computer only needs to maintain 2 checklists. But if you try to index an `id` column, and you have 1 million rows, every single row is a unique value. The database would have to create 1 million separate checklists, which completely crashes your system's memory and actually makes searching *slower*.

## Our New Protective Strategy
To solve this, we updated our `BitmapIndexManager` to be much smarter. It now constantly monitors the flow of data using a **Cardinality Ratio constraint**. 

Here is how the new strategy is structured:
1. **Dynamic Memory Protection:** If a table column accumulates highly unique elements, the manager actively refuses to build an index for it. For smaller tables (under 1,000 rows), we have an absolute hard limit of 1,000 index values.
2. **Ratio Scanning:** For larger tables (1,000 rows or more), if the number of unique elements stretches past **5%** of the total count (e.g., 50 unique values over 1,000 total rows), the database determines that an index isn't worth keeping. 
3. **Adaptive Auto-Drop:** Data changes over time. If a column naturally drifts and exceeds our 1,000 boundary or our 5% limit while data is being continuously inserted or modified, our Index Manager dynamically destroys the existing bitmap index mid-stream to reclaim performance.

## The Sequential Scan Fallback
If an index isn't created, how does the database look up your query?

We upgraded the `QueryEngine` to include a built-in safety net called a **Sequential Scan Fallback**. 

If the user runs a `SELECT` query on a table and requests data filtering where an index *should* have been used, but the `BitmapIndexManager` correctly bypassed index creation, the engine is smart enough to realize the index doesn't exist. Instead of returning an error or returning 0 results, the `QueryEngine` gracefully engages its fallback logic. It physically reads the column values sequentially piece by piece from your hard drive (`StorageEngine`), comparing every row automatically without crashing. 

This ensures complete feature safety while preserving high memory efficiency under massive payloads!
