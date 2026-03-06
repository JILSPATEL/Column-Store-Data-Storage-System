package cdb.api;

import cdb.ddl.SchemaManager;
import cdb.query.QueryEngine;
import cdb.query.QueryParser;
import cdb.query.querytypes.Query;
import cdb.storage.StorageEngine;
import cdb.storage.TextStorageEngine;

public class DatabaseAPI {
    private SchemaManager schemaManager;
    private StorageEngine storageEngine;
    private QueryParser queryParser;
    private QueryEngine queryEngine;

    public DatabaseAPI(String dataDir) {
        this.schemaManager = new SchemaManager(dataDir);
        this.storageEngine = new TextStorageEngine(dataDir);
        this.queryParser = new QueryParser();
        this.queryEngine = new QueryEngine(this.schemaManager, this.storageEngine);
    }

    public String execute(String queryString) {
        try {
            Query query = queryParser.parse(queryString);
            return queryEngine.execute(query);
        } catch (Exception e) {
            return "Execution Error: " + e.getMessage();
        }
    }
}
