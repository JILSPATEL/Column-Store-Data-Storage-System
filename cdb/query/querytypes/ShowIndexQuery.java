package cdb.query.querytypes;

public class ShowIndexQuery implements Query {
    private String tableName;
    private String columnName;

    public ShowIndexQuery(String tableName, String columnName) {
        this.tableName = tableName;
        this.columnName = columnName;
    }

    public String getTableName() {
        return tableName;
    }

    public String getColumnName() {
        return columnName;
    }
}
