package cdb.api;

import cdb.ddl.SchemaManager;
import cdb.query.QueryEngine;
import cdb.query.QueryParser;
import cdb.query.querytypes.Query;
import cdb.storage.StorageEngine;
import cdb.storage.BinaryStorageEngine;
import cdb.query.BitmapIndexManager;

public class DatabaseAPI {
    private SchemaManager schemaManager;
    private StorageEngine storageEngine;
    private QueryParser queryParser;
    private QueryEngine queryEngine;
    private BitmapIndexManager indexManager;

    public DatabaseAPI(String dataDir) {
        this.schemaManager = new SchemaManager(dataDir);
        this.storageEngine = new BinaryStorageEngine(dataDir);
        this.queryParser = new QueryParser();
        
        this.indexManager = new BitmapIndexManager(this.storageEngine, this.schemaManager);
        try {
            this.indexManager.initializeAll();
        } catch(java.io.IOException e) {
            System.err.println("Failed to initialize indexes: " + e.getMessage());
        }
        
        this.queryEngine = new QueryEngine(this.schemaManager, this.storageEngine, this.indexManager);
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
