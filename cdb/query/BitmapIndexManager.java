package cdb.query;

import cdb.ddl.ColumnSchema;
import cdb.ddl.SchemaManager;
import cdb.ddl.TableSchema;
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
                System.out.println("[Bitmap Index] Skipped index creation for " + tableName + "." + colName + " (High Cardinality: " + distinctCount + " unique / " + rowCount + " rows)");
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
                System.out.println("[Bitmap Index] Dynamically dropped index for " + tableName + "." + colName + " (Exceeded cardinality threshold)");
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
                System.out.println("[Bitmap Index] Dynamically dropped index for " + tableName + "." + colName + " (Exceeded cardinality threshold)");
            }
        }
    }

    public List<Integer> getFilteredRowIndexes(String tableName, TableSchema schema, String filterCol, String filterOp, String filterVal) {
        // Fallback: If no filter, return all indexes
        if (filterCol == null) {
            int count = tableRowCounts.getOrDefault(tableName, 0);
            List<Integer> result = new ArrayList<>();
            for (int i = 0; i < count; i++) result.add(i);
            return result;
        }

        Map<String, Map<String, BitSet>> tableIndexes = indexes.get(tableName);
        if (tableIndexes == null || !tableIndexes.containsKey(filterCol)) {
            return null;
        }

        Map<String, BitSet> colIndex = tableIndexes.get(filterCol);
        BitSet resultBits = new BitSet();

        if (filterOp.equals("=")) {
            BitSet exactMatch = colIndex.get(filterVal);
            if (exactMatch != null) {
                resultBits.or(exactMatch);
            }
        } else {
            // For > and <, iterate through keys
            double num2;
            try {
                num2 = Double.parseDouble(normalizeBoolean(filterVal).trim());
            } catch (NumberFormatException e) {
                return new ArrayList<>(); // Invalid numeric comparison
            }
            
            for (Map.Entry<String, BitSet> entry : colIndex.entrySet()) {
                double num1;
                try {
                    num1 = Double.parseDouble(normalizeBoolean(entry.getKey()).trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                
                if (filterOp.equals(">") && num1 > num2) {
                    resultBits.or(entry.getValue());
                } else if (filterOp.equals("<") && num1 < num2) {
                    resultBits.or(entry.getValue());
                }
            }
        }

        // Convert BitSet back to list of dynamic active indices
        List<Integer> result = new ArrayList<>();
        for (int i = resultBits.nextSetBit(0); i >= 0; i = resultBits.nextSetBit(i + 1)) {
            result.add(i);
        }
        
        System.out.println("[Bitmap Index] Used fast lookup for " + tableName + "." + filterCol + " (" + filterOp + " " + filterVal + "), matched " + result.size() + " logical row(s).");
        return result;
    }

    private String normalizeBoolean(String val) {
        String s = val.trim().toLowerCase();
        if (s.equals("true")) return "1";
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
                BitSet bs = valEntry.getValue();
                
                StringBuilder bits = new StringBuilder();
                for (int i = 0; i < maxRows; i++) {
                    bits.append(bs.get(i) ? "1" : "0");
                }
                sb.append("    Value '").append(val).append("': ").append(bits.toString()).append(" ").append(bs.toString()).append("\n");
            }
        }
        return sb.toString().trim();
    }
}
