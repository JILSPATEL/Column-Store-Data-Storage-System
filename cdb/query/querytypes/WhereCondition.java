package cdb.query.querytypes;

public class WhereCondition {
    private final String column;
    private final String op;
    private final String value;

    public WhereCondition(String column, String op, String value) {
        this.column = column;
        this.op = op;
        this.value = value;
    }

    public String getColumn() { return column; }
    public String getOp()     { return op; }
    public String getValue()  { return value; }

    @Override
    public String toString() {
        return column + " " + op + " " + value;
    }
}
