package cdb.query;

import cdb.ddl.ColumnSchema;
import cdb.ddl.SchemaManager;
import cdb.ddl.TableSchema;
import cdb.query.querytypes.*;
import cdb.storage.StorageEngine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class QueryEngine {
    private SchemaManager schemaManager;
    private StorageEngine storageEngine;
    private BitmapIndexManager indexManager;

    public QueryEngine(SchemaManager schemaManager, StorageEngine storageEngine, BitmapIndexManager indexManager) {
        this.schemaManager = schemaManager;
        this.storageEngine = storageEngine;
        this.indexManager  = indexManager;
    }

    public String execute(Query query) {
        try {
            if (query instanceof CreateTableQuery) {
                return executeCreate((CreateTableQuery) query);
            } else if (query instanceof InsertQuery) {
                return executeInsert((InsertQuery) query);
            } else if (query instanceof SelectQuery) {
                return executeSelect((SelectQuery) query);
            } else if (query instanceof UpdateQuery) {
                return executeUpdate((UpdateQuery) query);
            } else if (query instanceof DeleteQuery) {
                return executeDelete((DeleteQuery) query);
            }
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
        return "Unknown query type";
    }

    // -------------------------------------------------------------------------
    // CREATE
    // -------------------------------------------------------------------------
    private String executeCreate(CreateTableQuery q) throws IOException {
        schemaManager.createTable(q.getSchema());
        storageEngine.createTable(q.getSchema());
        return "Table " + q.getSchema().getTableName() + " created successfully.";
    }

    // -------------------------------------------------------------------------
    // INSERT
    // -------------------------------------------------------------------------
    private String executeInsert(InsertQuery q) throws IOException {
        TableSchema schema = schemaManager.getTable(q.getTableName());
        if (schema == null)
            throw new IllegalArgumentException("Table not found: " + q.getTableName());
        if (schema.getColumns().size() != q.getValues().size()) {
            throw new IllegalArgumentException("Column count doesn't match value count.");
        }

        // Validate types and constraints before any writes
        for (int i = 0; i < schema.getColumns().size(); i++) {
            ColumnSchema col = schema.getColumns().get(i);
            String val = q.getValues().get(i);
            
            // Check NOT_NULL
            if (col.hasConstraint("NOT_NULL") && (val == null || val.isEmpty() || val.equalsIgnoreCase("null"))) {
                throw new IllegalArgumentException("Column " + col.getName() + " cannot be null.");
            }
            
            // Type validation (Dry run parsing)
            validateType(val, col.getType());

            // Check UNIQUE/PRIMARY_KEY
            if (col.hasConstraint("PRIMARY_KEY") || col.hasConstraint("UNIQUE")) {
                List<String> existing = storageEngine.readColumn(q.getTableName(), col.getName());
                if (existing.contains(val)) {
                    throw new IllegalArgumentException(
                            "Constraint violation on " + col.getName() + " for value " + val);
                }
            }
        }

        // All validations passed, now append to all columns
        for (int i = 0; i < schema.getColumns().size(); i++) {
            ColumnSchema col = schema.getColumns().get(i);
            storageEngine.appendValue(q.getTableName(), col.getName(), q.getValues().get(i));
        }

        indexManager.insertRow(q.getTableName(), schema, q.getValues());
        return "1 row inserted.";
    }

    // -------------------------------------------------------------------------
    // SELECT
    // -------------------------------------------------------------------------
    private String executeSelect(SelectQuery q) throws IOException {
        TableSchema schema = schemaManager.getTable(q.getTableName());
        if (schema == null)
            throw new IllegalArgumentException("Table not found: " + q.getTableName());

        // Resolve * -> all column names
        List<String> requestedCols = resolveColumns(q.getColumns(), schema);

        // Validate each requested column
        for (String colName : requestedCols) {
            if (schema.getColumn(colName) == null) {
                throw new IllegalArgumentException("Column not found: " + colName);
            }
        }

        // Validate WHERE columns
        validateWhereClause(q.getWhereClause(), schema);

        List<Integer> validRowIndexes = getFilteredRowIndexes(q.getTableName(), schema, q.getWhereClause());

        // Read requested columns
        List<List<String>> columnsData = new ArrayList<>();
        for (String colName : requestedCols) {
            columnsData.add(storageEngine.readColumn(q.getTableName(), colName));
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.join("\t", requestedCols)).append("\n");
        sb.append("-".repeat(requestedCols.size() * 15)).append("\n");

        for (int idx : validRowIndexes) {
            List<String> rowValues = new ArrayList<>();
            for (List<String> colData : columnsData) {
                rowValues.add(idx < colData.size() ? colData.get(idx) : "null");
            }
            sb.append(String.join("\t", rowValues)).append("\n");
        }

        return sb.toString().trim() + "\n(" + validRowIndexes.size() + " rows)";
    }

    // -------------------------------------------------------------------------
    // UPDATE
    // -------------------------------------------------------------------------
    private String executeUpdate(UpdateQuery q) throws IOException {
        TableSchema schema = schemaManager.getTable(q.getTableName());
        if (schema == null)
            throw new IllegalArgumentException("Table not found: " + q.getTableName());

        if (schema.getColumn(q.getSetColumn()) == null) {
            throw new IllegalArgumentException("Column not found: " + q.getSetColumn());
        }

        validateWhereClause(q.getWhereClause(), schema);

        List<Integer> validRowIndexes = getFilteredRowIndexes(q.getTableName(), schema, q.getWhereClause());

        List<String> currentValues = storageEngine.readColumn(q.getTableName(), q.getSetColumn());

        for (int idx : validRowIndexes) {
            String oldValue = currentValues.get(idx);
            storageEngine.updateValue(q.getTableName(), q.getSetColumn(), idx, q.getSetValue());
            indexManager.updateValue(q.getTableName(), q.getSetColumn(), idx, oldValue, q.getSetValue());
        }

        return validRowIndexes.size() + " rows updated.";
    }

    // -------------------------------------------------------------------------
    // DELETE
    // -------------------------------------------------------------------------
    private String executeDelete(DeleteQuery q) throws IOException {
        TableSchema schema = schemaManager.getTable(q.getTableName());
        if (schema == null)
            throw new IllegalArgumentException("Table not found: " + q.getTableName());

        validateWhereClause(q.getWhereClause(), schema);

        List<Integer> validRowIndexes = getFilteredRowIndexes(q.getTableName(), schema, q.getWhereClause());

        // Delete from bottom to top to preserve indexes during removal
        for (int i = validRowIndexes.size() - 1; i >= 0; i--) {
            int idx = validRowIndexes.get(i);
            storageEngine.deleteRow(q.getTableName(), idx);
        }

        // Rebuild bitmap index (row positions have shifted)
        indexManager.buildIndex(q.getTableName());

        return validRowIndexes.size() + " rows deleted.";
    }

    // -------------------------------------------------------------------------
    // Core filtering — WhereClause aware
    // -------------------------------------------------------------------------

    private List<Integer> getFilteredRowIndexes(String tableName, TableSchema schema,
                                                WhereClause whereClause) throws IOException {

        // No WHERE clause → return every row
        if (whereClause == null) {
            List<Integer> all = indexManager.getFilteredRowIndexes(tableName, schema,
                    (WhereClause) null);
            return all;
        }

        // Attempt bitmap index path
        List<Integer> indexed = indexManager.getFilteredRowIndexes(tableName, schema, whereClause);
        if (indexed != null) {
            return indexed;
        }

        // ---- Sequential scan fallback ----
        List<WhereCondition> allConditions = whereClause.getAllConditions();

        // Determine scan length from the first condition's column
        // (all columns have the same number of rows in a columnar store)
        String firstCol = allConditions.get(0).getColumn();
        List<String> firstColData = storageEngine.readColumn(tableName, firstCol);
        int rowCount = firstColData.size();

        // Pre-load column data for every unique condition's column
        Map<String, List<String>> colDataMap = new HashMap<>();
        for (WhereCondition cond : allConditions) {
            if (!colDataMap.containsKey(cond.getColumn())) {
                colDataMap.put(cond.getColumn(), storageEngine.readColumn(tableName, cond.getColumn()));
            }
        }

        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < rowCount; i++) {
            boolean rowMatchesAnyOrGroup = false;

            // DNF logic: True if ANY OR-group is true
            for (List<WhereCondition> andGroup : whereClause.getOrGroups()) {
                boolean andGroupMatches = true; // True if ALL conditions in this AND-group are true
                
                for (WhereCondition cond : andGroup) {
                    List<String> colData = colDataMap.get(cond.getColumn());
                    String cellVal = i < colData.size() ? colData.get(i) : "null";
                    boolean condMatch = evaluateCondition(cellVal, cond.getOp(), cond.getValue());
                    
                    if (!condMatch) {
                        andGroupMatches = false;
                        break; // short-circuit this AND-group
                    }
                }
                
                if (andGroupMatches) {
                    rowMatchesAnyOrGroup = true;
                    break; // short-circuit the outer OR logic
                }
            }
            
            if (rowMatchesAnyOrGroup) {
                result.add(i);
            }
        }

        System.out.println("[Sequential Scan] Used full scan for " + tableName
                + " (mixed AND/OR logic), matched " + result.size() + " logical row(s).");
        return result;
    }

    // -------------------------------------------------------------------------
    // Condition evaluator
    // -------------------------------------------------------------------------

    private boolean evaluateCondition(String val1, String op, String val2) {
        try {
            double num1 = Double.parseDouble(normalizeBoolean(val1).trim());
            double num2 = Double.parseDouble(normalizeBoolean(val2).trim());
            return switch (op) {
                case "="  -> num1 == num2;
                case "!=" -> num1 != num2;
                case ">"  -> num1 > num2;
                case "<"  -> num1 < num2;
                case ">=" -> num1 >= num2;
                case "<=" -> num1 <= num2;
                default   -> false;
            };
        } catch (NumberFormatException e) {
            // String comparison
            return switch (op) {
                case "="  -> val1.trim().equalsIgnoreCase(val2.trim());
                case "!=" -> !val1.trim().equalsIgnoreCase(val2.trim());
                default   -> false;
            };
        }
    }

    private void validateType(String value, String type) {
        if (value == null || value.equalsIgnoreCase("null")) return;
        try {
            switch (type.toUpperCase()) {
                case "BYTE":       Byte.parseByte(value.trim()); break;
                case "SHORT":      Short.parseShort(value.trim()); break;
                case "INT":
                case "INTEGER":    Integer.parseInt(value.trim()); break;
                case "LONG":
                case "BIGINT":     Long.parseLong(value.trim()); break;
                case "FLOAT":
                case "REAL":       Float.parseFloat(value.trim()); break;
                case "DOUBLE":
                case "DECIMAL":    Double.parseDouble(value.trim()); break;
                case "BOOLEAN":
                case "BOOL":       break; // Boolean.parseBoolean always works
                case "BIGDECIMAL":
                case "NUMERIC":    new java.math.BigDecimal(value.trim()); break;
                default:           break; // STRING/other are always valid
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid value for type " + type + ": \"" + value + "\". " + e.getMessage());
        }
    }

    private String normalizeBoolean(String val) {
        String s = val.trim().toLowerCase();
        if (s.equals("true"))  return "1";
        if (s.equals("false")) return "0";
        return s;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Expands ["*"] to the full ordered list of column names in the schema. */
    private List<String> resolveColumns(List<String> requested, TableSchema schema) {
        if (requested.size() == 1 && requested.get(0).equals("*")) {
            List<String> all = new ArrayList<>();
            for (ColumnSchema col : schema.getColumns()) {
                all.add(col.getName());
            }
            return all;
        }
        return requested;
    }

    /** Validates that every column referenced in a WHERE clause exists in the schema. */
    private void validateWhereClause(WhereClause whereClause, TableSchema schema) {
        if (whereClause == null) return;
        for (WhereCondition cond : whereClause.getAllConditions()) {
            if (schema.getColumn(cond.getColumn()) == null) {
                throw new IllegalArgumentException("Filter column not found: " + cond.getColumn());
            }
        }
    }
}
