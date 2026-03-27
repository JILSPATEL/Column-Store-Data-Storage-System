package cdb.query;

import cdb.ddl.ColumnSchema;
import cdb.ddl.SchemaManager;
import cdb.ddl.TableSchema;
import cdb.query.querytypes.*;
import cdb.storage.StorageEngine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class QueryEngine {
    private SchemaManager schemaManager;
    private StorageEngine storageEngine;

    public QueryEngine(SchemaManager schemaManager, StorageEngine storageEngine) {
        this.schemaManager = schemaManager;
        this.storageEngine = storageEngine;
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

    private String executeCreate(CreateTableQuery q) throws IOException {
        schemaManager.createTable(q.getSchema());
        storageEngine.createTable(q.getSchema());
        return "Table " + q.getSchema().getTableName() + " created successfully.";
    }

    private String executeInsert(InsertQuery q) throws IOException {
        TableSchema schema = schemaManager.getTable(q.getTableName());
        if (schema == null)
            throw new IllegalArgumentException("Table not found: " + q.getTableName());
        if (schema.getColumns().size() != q.getValues().size()) {
            throw new IllegalArgumentException("Column count doesn't match value count.");
        }

        // Check constraints: PRIMARY_KEY, UNIQUE, NOT_NULL
        for (int i = 0; i < schema.getColumns().size(); i++) {
            ColumnSchema col = schema.getColumns().get(i);
            String val = q.getValues().get(i);
            if (col.hasConstraint("NOT_NULL") && (val == null || val.isEmpty() || val.equalsIgnoreCase("null"))) {
                throw new IllegalArgumentException("Column " + col.getName() + " cannot be null.");
            }
            if (col.hasConstraint("PRIMARY_KEY") || col.hasConstraint("UNIQUE")) {
                List<String> existing = storageEngine.readColumn(q.getTableName(), col.getName());
                if (existing.contains(val)) {
                    throw new IllegalArgumentException(
                            "Constraint violation on " + col.getName() + " for value " + val);
                }
            }
        }

        // Append to all columns
        for (int i = 0; i < schema.getColumns().size(); i++) {
            ColumnSchema col = schema.getColumns().get(i);
            storageEngine.appendValue(q.getTableName(), col.getName(), q.getValues().get(i));
        }

        return "1 row inserted.";
    }

    private String executeSelect(SelectQuery q) throws IOException {
        TableSchema schema = schemaManager.getTable(q.getTableName());
        if (schema == null)
            throw new IllegalArgumentException("Table not found: " + q.getTableName());

        List<Integer> validRowIndexes = getFilteredRowIndexes(q.getTableName(), schema, q.getFilterColumn(),
                q.getFilterOp(), q.getFilterValue());

        // Read requested columns
        List<List<String>> columnsData = new ArrayList<>();
        for (String colName : q.getColumns()) {
            columnsData.add(storageEngine.readColumn(q.getTableName(), colName));
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.join("\t", q.getColumns())).append("\n");
        sb.append("-".repeat(q.getColumns().size() * 10)).append("\n");

        for (int idx : validRowIndexes) {
            List<String> rowValues = new ArrayList<>();
            for (List<String> colData : columnsData) {
                rowValues.add(idx < colData.size() ? colData.get(idx) : "null");
            }
            sb.append(String.join("\t", rowValues)).append("\n");
        }

        return sb.toString().trim() + "\n(" + validRowIndexes.size() + " rows)";
    }

    private String executeUpdate(UpdateQuery q) throws IOException {
        TableSchema schema = schemaManager.getTable(q.getTableName());
        if (schema == null)
            throw new IllegalArgumentException("Table not found: " + q.getTableName());

        List<Integer> validRowIndexes = getFilteredRowIndexes(q.getTableName(), schema, q.getFilterColumn(),
                q.getFilterOp(), q.getFilterValue());

        for (int idx : validRowIndexes) {
            storageEngine.updateValue(q.getTableName(), q.getSetColumn(), idx, q.getSetValue());
        }

        return validRowIndexes.size() + " rows updated.";
    }

    private String executeDelete(DeleteQuery q) throws IOException {
        TableSchema schema = schemaManager.getTable(q.getTableName());
        if (schema == null)
            throw new IllegalArgumentException("Table not found: " + q.getTableName());

        List<Integer> validRowIndexes = getFilteredRowIndexes(q.getTableName(), schema, q.getFilterColumn(),
                q.getFilterOp(), q.getFilterValue());

        // Delete from bottom to top to preserve indexes
        for (int i = validRowIndexes.size() - 1; i >= 0; i--) {
            int idx = validRowIndexes.get(i);
            storageEngine.deleteRow(q.getTableName(), idx);
        }

        return validRowIndexes.size() + " rows deleted.";
    }

    private List<Integer> getFilteredRowIndexes(String tableName, TableSchema schema, String filterCol, String filterOp,
            String filterVal) throws IOException {
        List<Integer> indexes = new ArrayList<>();
        if (filterCol == null) {
            // Assume first column defines number of rows (or checking size of any column)
            if (!schema.getColumns().isEmpty()) {
                List<String> colData = storageEngine.readColumn(tableName, schema.getColumns().get(0).getName());
                for (int i = 0; i < colData.size(); i++) {
                    indexes.add(i);
                }
            }
            return indexes;
        }

        List<String> colData = storageEngine.readColumn(tableName, filterCol);
        for (int i = 0; i < colData.size(); i++) {
            String val = colData.get(i);
            if (evaluateCondition(val, filterOp, filterVal)) {
                indexes.add(i);
            }
        }
        return indexes;
    }

    private boolean evaluateCondition(String val1, String op, String val2) {
        try {
            double num1 = Double.parseDouble(val1.trim());
            double num2 = Double.parseDouble(val2.trim());
            switch (op) {
                case "=":
                    return num1 == num2;
                case ">":
                    return num1 > num2;
                case "<":
                    return num1 < num2;
            }
        } catch (NumberFormatException e) {
            switch (op) {
                case "=":
                    return val1.trim().equals(val2.trim());
            }
        }
        return false;
    }
}
