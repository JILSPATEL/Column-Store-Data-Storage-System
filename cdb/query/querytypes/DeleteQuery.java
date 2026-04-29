package cdb.query.querytypes;

public class DeleteQuery implements Query {
    private final String tableName;
    private final WhereClause whereClause; // null means no filter (delete all)

    public DeleteQuery(String tableName, WhereClause whereClause) {
        this.tableName   = tableName;
        this.whereClause = whereClause;
    }

    /** Legacy single-condition constructor for backward compatibility. */
    public DeleteQuery(String tableName, String filterColumn, String filterOp, String filterValue) {
        this.tableName = tableName;
        if (filterColumn != null) {
            this.whereClause = new WhereClause(
                    new WhereCondition(filterColumn, filterOp, filterValue));
        } else {
            this.whereClause = null;
        }
    }

    public String getTableName()        { return tableName; }
    public WhereClause getWhereClause() { return whereClause; }

    // ---- Legacy getters ----
    public String getFilterColumn() {
        return (whereClause != null && !whereClause.getAllConditions().isEmpty())
                ? whereClause.getAllConditions().get(0).getColumn() : null;
    }
    public String getFilterOp() {
        return (whereClause != null && !whereClause.getAllConditions().isEmpty())
                ? whereClause.getAllConditions().get(0).getOp() : null;
    }
    public String getFilterValue() {
        return (whereClause != null && !whereClause.getAllConditions().isEmpty())
                ? whereClause.getAllConditions().get(0).getValue() : null;
    }
}
