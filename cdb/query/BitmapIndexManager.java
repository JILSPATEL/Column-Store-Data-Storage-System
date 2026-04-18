package cdb.query;

import cdb.ddl.ColumnSchema;
import cdb.ddl.SchemaManager;
import cdb.ddl.TableSchema;
import cdb.query.querytypes.WhereClause;
import cdb.query.querytypes.WhereCondition;
import cdb.storage.StorageEngine;

import java.io.IOException;
import java.util.*;

public class BitmapIndexManager {
    private final StorageEngine storageEngine;
    private final SchemaManager schemaManager;

    // Table -> Column -> Value -> BitSet
    private final Map<String, Map<String, Map<String, BitSet>>> indexes = new HashMap<>();

    // Table -> Number of active rows
    private final Map<String, Integer> tableRowCounts = new HashMap<>();

    public BitmapIndexManager(StorageEngine storageEngine, SchemaManager schemaManager) {
        this.storageEngine = storageEngine;
        this.schemaManager = schemaManager;
    }

    public void initializeAll() throws IOException {
        for (String tableName : schemaManager.listTables()) {
            buildIndex(tableName);
        }
    }

    public void buildIndex(String tableName) throws IOException {
        TableSchema schema = schemaManager.getTable(tableName);
        if (schema == null) return;

        Map<String, Map<String, BitSet>> tableIndexes = new HashMap<>();
        int rowCount = 0;

        for (ColumnSchema col : schema.getColumns()) {
            String colName = col.getName();

            List<String> values = storageEngine.readColumn(tableName, colName);
            rowCount = values.size(); // All columns have same active rows

            Set<String> uniqueVals = new HashSet<>(values);
            long distinctCount = uniqueVals.size();

            boolean buildIndex = true;
            if (distinctCount > 1000) {
                buildIndex = false;
            } else if (rowCount >= 1000 && (double) distinctCount / rowCount > 0.05) {
                buildIndex = false;
            }

            if (buildIndex) {
                Map<String, BitSet> colIndex = new HashMap<>();
                for (int i = 0; i < values.size(); i++) {
                    String val = values.get(i);
                    colIndex.computeIfAbsent(val, k -> new BitSet()).set(i);
                }
                tableIndexes.put(colName, colIndex);
            } else {
                System.out.println("[Bitmap Index] Skipped index creation for " + tableName + "." + colName
                        + " (High Cardinality: " + distinctCount + " unique / " + rowCount + " rows)");
            }
        }

        indexes.put(tableName, tableIndexes);
        tableRowCounts.put(tableName, rowCount);
    }

    public void insertRow(String tableName, TableSchema schema, List<String> values) {
        Map<String, Map<String, BitSet>> tableIndexes = indexes.computeIfAbsent(tableName, k -> new HashMap<>());

        int newIdx = tableRowCounts.getOrDefault(tableName, 0);

        for (int i = 0; i < schema.getColumns().size(); i++) {
            String colName = schema.getColumns().get(i).getName();

            if (!tableIndexes.containsKey(colName)) continue;

            String val = values.get(i);
            Map<String, BitSet> colIndex = tableIndexes.get(colName);
            colIndex.computeIfAbsent(val, k -> new BitSet()).set(newIdx);

            int distinctCount = colIndex.size();
            int totalRows = newIdx + 1;
            if (distinctCount > 1000 || (totalRows >= 1000 && (double) distinctCount / totalRows > 0.05)) {
                tableIndexes.remove(colName);
                System.out.println("[Bitmap Index] Dynamically dropped index for " + tableName + "." + colName
                        + " (Exceeded cardinality threshold)");
            }
        }

        tableRowCounts.put(tableName, newIdx + 1);
    }

    public void updateValue(String tableName, String colName, int rowIndex, String oldValue, String newValue) {
        Map<String, Map<String, BitSet>> tableIndexes = indexes.get(tableName);
        if (tableIndexes == null) return;

        Map<String, BitSet> colIndex = tableIndexes.get(colName);
        if (colIndex != null) {
            // Unset old value
            BitSet oldBits = colIndex.get(oldValue);
            if (oldBits != null) {
                oldBits.clear(rowIndex);
            }
            // Set new value
            colIndex.computeIfAbsent(newValue, k -> new BitSet()).set(rowIndex);

            int distinctCount = colIndex.size();
            int totalRows = tableRowCounts.get(tableName);
            if (distinctCount > 1000 || (totalRows >= 1000 && (double) distinctCount / totalRows > 0.05)) {
                tableIndexes.remove(colName);
                System.out.println("[Bitmap Index] Dynamically dropped index for " + tableName + "." + colName
                        + " (Exceeded cardinality threshold)");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Multi-condition WHERE clause filtering (new primary API)
    // -------------------------------------------------------------------------

    /**
     * Returns the set of matching row indexes for the given WhereClause.
     *
     * Returns null if any condition cannot be served by the bitmap index
     * (caller should fall back to sequential scan for the whole clause).
     *
     * If whereClause is null, returns all row indexes (no filter).
     */
    public List<Integer> getFilteredRowIndexes(String tableName, TableSchema schema,
                                               WhereClause whereClause) {
        if (whereClause == null) {
            // No filter — return all rows
            int count = tableRowCounts.getOrDefault(tableName, 0);
            List<Integer> all = new ArrayList<>();
            for (int i = 0; i < count; i++) all.add(i);
            return all;
        }

        List<WhereCondition> conditions = whereClause.getConditions();
        boolean isAnd = whereClause.getLogicalOp() == WhereClause.LogicalOp.AND;

        // Evaluate each condition — if any can't use the index, signal fallback via null
        List<BitSet> conditionBits = new ArrayList<>();
        int totalRows = tableRowCounts.getOrDefault(tableName, 0);

        for (WhereCondition cond : conditions) {
            BitSet bits = evaluateConditionWithIndex(tableName, cond, totalRows);
            if (bits == null) {
                return null; // Index not available for this condition — full fallback
            }
            conditionBits.add(bits);
        }

        // Combine results
        BitSet resultBits = (BitSet) conditionBits.get(0).clone();
        for (int i = 1; i < conditionBits.size(); i++) {
            if (isAnd) {
                resultBits.and(conditionBits.get(i));
            } else {
                resultBits.or(conditionBits.get(i));
            }
        }

        List<Integer> result = new ArrayList<>();
        for (int i = resultBits.nextSetBit(0); i >= 0; i = resultBits.nextSetBit(i + 1)) {
            result.add(i);
        }

        String op = isAnd ? "AND" : "OR";
        System.out.println("[Bitmap Index] Used fast lookup for " + tableName
                + " (" + conditions.size() + " condition(s) joined by " + op
                + "), matched " + result.size() + " logical row(s).");
        return result;
    }

    /**
     * Legacy single-condition API kept for backward compatibility.
     * Delegates to the WhereClause-based method.
     */
    public List<Integer> getFilteredRowIndexes(String tableName, TableSchema schema,
                                               String filterCol, String filterOp, String filterVal) {
        if (filterCol == null) {
            int count = tableRowCounts.getOrDefault(tableName, 0);
            List<Integer> all = new ArrayList<>();
            for (int i = 0; i < count; i++) all.add(i);
            return all;
        }
        int totalRows = tableRowCounts.getOrDefault(tableName, 0);
        BitSet bits = evaluateConditionWithIndex(tableName,
                new WhereCondition(filterCol, filterOp, filterVal), totalRows);
        if (bits == null) return null;

        List<Integer> result = new ArrayList<>();
        for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1)) {
            result.add(i);
        }
        System.out.println("[Bitmap Index] Used fast lookup for " + tableName + "." + filterCol
                + " (" + filterOp + " " + filterVal + "), matched " + result.size() + " logical row(s).");
        return result;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Returns a BitSet for one condition using the in-memory index.
     * Returns null if the column doesn't have a bitmap index (caller must fall back).
     */
    private BitSet evaluateConditionWithIndex(String tableName, WhereCondition cond, int totalRows) {
        Map<String, Map<String, BitSet>> tableIndexes = indexes.get(tableName);
        if (tableIndexes == null || !tableIndexes.containsKey(cond.getColumn())) {
            return null;
        }

        Map<String, BitSet> colIndex = tableIndexes.get(cond.getColumn());
        String filterOp  = cond.getOp();
        String filterVal = cond.getValue();
        BitSet resultBits = new BitSet();

        if (filterOp.equals("=")) {
            BitSet exactMatch = colIndex.get(filterVal);
            if (exactMatch != null) {
                resultBits.or(exactMatch);
            }
        } else if (filterOp.equals("!=")) {
            // All rows minus the matching ones
            BitSet exactMatch = colIndex.get(filterVal);
            for (int i = 0; i < totalRows; i++) resultBits.set(i);
            if (exactMatch != null) {
                resultBits.andNot(exactMatch);
            }
        } else {
            // >, <, >=, <=
            double num2;
            try {
                num2 = Double.parseDouble(normalizeBoolean(filterVal).trim());
            } catch (NumberFormatException e) {
                return new BitSet(); // Invalid numeric comparison → empty result
            }

            for (Map.Entry<String, BitSet> entry : colIndex.entrySet()) {
                double num1;
                try {
                    num1 = Double.parseDouble(normalizeBoolean(entry.getKey()).trim());
                } catch (NumberFormatException e) {
                    continue;
                }

                boolean match = switch (filterOp) {
                    case ">"  -> num1 > num2;
                    case "<"  -> num1 < num2;
                    case ">=" -> num1 >= num2;
                    case "<=" -> num1 <= num2;
                    default   -> false;
                };
                if (match) resultBits.or(entry.getValue());
            }
        }
        return resultBits;
    }

    private String normalizeBoolean(String val) {
        String s = val.trim().toLowerCase();
        if (s.equals("true"))  return "1";
        if (s.equals("false")) return "0";
        return s;
    }

    public String dumpIndex(String tableName) {
        Map<String, Map<String, BitSet>> tableIndexes = indexes.get(tableName);
        if (tableIndexes == null || tableIndexes.isEmpty()) {
            return "No bitmap index found for table '" + tableName + "'.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Bitmap Index for table '").append(tableName).append("':\n");
        int maxRows = tableRowCounts.getOrDefault(tableName, 0);

        for (Map.Entry<String, Map<String, BitSet>> colEntry : tableIndexes.entrySet()) {
            String colName = colEntry.getKey();
            sb.append("  Column: ").append(colName).append("\n");
            for (Map.Entry<String, BitSet> valEntry : colEntry.getValue().entrySet()) {
                String val = valEntry.getKey();
                BitSet bs  = valEntry.getValue();

                StringBuilder bits = new StringBuilder();
                for (int i = 0; i < maxRows; i++) {
                    bits.append(bs.get(i) ? "1" : "0");
                }
                sb.append("    Value '").append(val).append("': ")
                  .append(bits).append(" ").append(bs).append("\n");
            }
        }
        return sb.toString().trim();
    }
}
