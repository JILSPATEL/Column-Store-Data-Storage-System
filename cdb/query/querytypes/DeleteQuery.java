package cdb.query.querytypes;

public class DeleteQuery implements Query {
    private String tableName;
    private String filterColumn;
    private String filterOp;
    private String filterValue;

    public DeleteQuery(String tableName, String filterColumn, String filterOp, String filterValue) {
        this.tableName = tableName;
        this.filterColumn = filterColumn;
        this.filterOp = filterOp;
        this.filterValue = filterValue;
    }

    public String getTableName() {
        return tableName;
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
