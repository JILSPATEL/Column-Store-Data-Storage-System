package cdb.ddl;

import cdb.util.FileUtils;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public class SchemaManager {
    private String metadataDir;
    private Map<String, TableSchema> schemas;

    public SchemaManager(String dataDir) {
        this.metadataDir = dataDir + "/metadata";
        this.schemas = new HashMap<>();
        FileUtils.ensureDirectory(this.metadataDir);
        loadSchemas();
    }

    private void loadSchemas() {
        File dir = new File(metadataDir);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().endsWith(".schema")) {
                    try {
                        String content = new String(Files.readAllBytes(file.toPath())).trim();
                        TableSchema schema = parseSchemaString(content);
                        if (schema != null) {
                            schemas.put(schema.getTableName(), schema);
                        }
                    } catch (IOException e) {
                        System.err.println("Failed to load schema: " + file.getName());
                    }
                }
            }
        }
    }

    public TableSchema parseSchemaString(String content) {
        String[] tokens = content.split("\\s+");
        if (tokens.length < 2 || !tokens[0].equals("TABLE"))
            return null;

        TableSchema table = new TableSchema(tokens[1]);
        ColumnSchema currentColumn = null;

        for (int i = 2; i < tokens.length; i++) {
            if (tokens[i].equals("COLUMN")) {
                if (i + 2 < tokens.length) {
                    currentColumn = new ColumnSchema(tokens[i + 1], tokens[i + 2]);
                    table.addColumn(currentColumn);
                    i += 2;
                }
            } else if (currentColumn != null) {
                // Must be a constraint
                currentColumn.addConstraint(tokens[i]);
            }
        }
        return table;
    }

    public void createTable(TableSchema schema) throws IOException {
        String filePath = metadataDir + "/" + schema.getTableName() + ".schema";
        FileUtils.ensureFile(filePath);
        String schemaStr = schema.toString();
        Files.write(Paths.get(filePath), schemaStr.getBytes());
        schemas.put(schema.getTableName(), schema);
    }

    public void dropTable(String tableName) {
        String filePath = metadataDir + "/" + tableName + ".schema";
        new File(filePath).delete();
        schemas.remove(tableName);
    }

    public TableSchema getTable(String tableName) {
        return schemas.get(tableName);
    }
}
