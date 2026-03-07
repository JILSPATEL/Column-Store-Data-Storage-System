package cdb.query.querytypes;

import java.util.List;

public class InsertQuery implements Query {
    private String tableName;
    private List<String> values;

    public InsertQuery(String tableName, List<String> values) {
        this.tableName = tableName;
        this.values = values;
    }

    public String getTableName() {
        return tableName;
    }

    public List<String> getValues() {
        return values;
    }
}
