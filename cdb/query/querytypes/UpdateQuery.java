package cdb.query.querytypes;

public class UpdateQuery implements Query {
    private String tableName;
    private String setColumn;
    private String setValue;
    private String filterColumn;
    private String filterOp;
    private String filterValue;

    public UpdateQuery(String tableName, String setColumn, String setValue, String filterColumn, String filterOp,
            String filterValue) {
        this.tableName = tableName;
        this.setColumn = setColumn;
        this.setValue = setValue;
        this.filterColumn = filterColumn;
        this.filterOp = filterOp;
        this.filterValue = filterValue;
    }

    public String getTableName() {
        return tableName;
    }

    public String getSetColumn() {
        return setColumn;
    }

    public String getSetValue() {
        return setValue;
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
