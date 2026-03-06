package cdb.ddl;

import java.util.ArrayList;
import java.util.List;

public class TableSchema {
    private String tableName;
    private List<ColumnSchema> columns;

    public TableSchema(String tableName) {
        this.tableName = tableName;
        this.columns = new ArrayList<>();
    }

    public void addColumn(ColumnSchema column) {
        this.columns.add(column);
    }

    public String getTableName() { return tableName; }
    public List<ColumnSchema> getColumns() { return columns; }
    
    public ColumnSchema getColumn(String name) {
        for (ColumnSchema col : columns) {
            if (col.getName().equals(name)) {
                return col;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("TABLE ").append(tableName).append(" ");
        for (ColumnSchema col : columns) {
            sb.append(col.toString()).append(" ");
        }
        return sb.toString().trim();
    }
}
