package cdb.query.querytypes;

import java.util.List;

public class SelectQuery implements Query {
    private String tableName;
    private List<String> columns;
    private String filterColumn;
    private String filterOp;
    private String filterValue;

    public SelectQuery(String tableName, List<String> columns, String filterColumn, String filterOp,
            String filterValue) {
        this.tableName = tableName;
        this.columns = columns;
        this.filterColumn = filterColumn;
        this.filterOp = filterOp;
        this.filterValue = filterValue;
    }

    public String getTableName() {
        return tableName;
    }

    public List<String> getColumns() {
        return columns;
    }

    public String getFilterColumn() {
        return filterColumn;
    }

    public String getFilterOp() {
        return filterOp;
    }

    public String getFilterValue() {
        return filterValue;
    }
}
