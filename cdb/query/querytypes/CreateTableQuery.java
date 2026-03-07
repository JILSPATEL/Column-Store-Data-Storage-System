package cdb.query.querytypes;

import cdb.ddl.TableSchema;

public class CreateTableQuery implements Query {
    private TableSchema schema;

    public CreateTableQuery(TableSchema schema) {
        this.schema = schema;
    }

    public TableSchema getSchema() {
        return schema;
    }
}
