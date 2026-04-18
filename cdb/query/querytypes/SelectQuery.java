package cdb.query.querytypes;

import java.util.List;

public class SelectQuery implements Query {
    private final String tableName;
    private final List<String> columns;
    private final WhereClause whereClause; // null means no filter

    public SelectQuery(String tableName, List<String> columns, WhereClause whereClause) {
        this.tableName   = tableName;
        this.columns     = columns;
        this.whereClause = whereClause;
    }

    /** Legacy single-condition constructor for backward compatibility. */
    public SelectQuery(String tableName, List<String> columns,
                       String filterColumn, String filterOp, String filterValue) {
        this.tableName = tableName;
        this.columns   = columns;
        if (filterColumn != null) {
            this.whereClause = new WhereClause(
                    new WhereCondition(filterColumn, filterOp, filterValue));
        } else {
            this.whereClause = null;
        }
    }

    public String getTableName()       { return tableName; }
    public List<String> getColumns()   { return columns; }
    public WhereClause getWhereClause(){ return whereClause; }

    // ---- Legacy getters (single-condition convenience) ----
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
