package cdb.query.querytypes;

public class UpdateQuery implements Query {
    private final String tableName;
    private final String setColumn;
    private final String setValue;
    private final WhereClause whereClause; // null means no filter (update all)

    public UpdateQuery(String tableName, String setColumn, String setValue, WhereClause whereClause) {
        this.tableName   = tableName;
        this.setColumn   = setColumn;
        this.setValue    = setValue;
        this.whereClause = whereClause;
    }

    /** Legacy single-condition constructor for backward compatibility. */
    public UpdateQuery(String tableName, String setColumn, String setValue,
                       String filterColumn, String filterOp, String filterValue) {
        this.tableName = tableName;
        this.setColumn = setColumn;
        this.setValue  = setValue;
        if (filterColumn != null) {
            this.whereClause = new WhereClause(
                    new WhereCondition(filterColumn, filterOp, filterValue));
        } else {
            this.whereClause = null;
        }
    }

    public String getTableName()        { return tableName; }
    public String getSetColumn()        { return setColumn; }
    public String getSetValue()         { return setValue; }
    public WhereClause getWhereClause() { return whereClause; }

    // ---- Legacy getters ----
    public String getFilterColumn() {
        return (whereClause != null && !whereClause.getConditions().isEmpty())
                ? whereClause.getConditions().get(0).getColumn() : null;
    }
    public String getFilterOp() {
        return (whereClause != null && !whereClause.getConditions().isEmpty())
                ? whereClause.getConditions().get(0).getOp() : null;
    }
    public String getFilterValue() {
        return (whereClause != null && !whereClause.getConditions().isEmpty())
                ? whereClause.getConditions().get(0).getValue() : null;
    }
}
