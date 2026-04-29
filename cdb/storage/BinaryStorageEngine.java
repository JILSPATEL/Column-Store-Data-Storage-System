package cdb.storage;

import cdb.ddl.ColumnSchema;
import cdb.ddl.TableSchema;
import cdb.storage.persistence.CategoricalPersister;
import cdb.storage.persistence.ColumnPersister;
import cdb.storage.persistence.NumericalPersister;
import cdb.util.FileUtils;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class BinaryStorageEngine implements StorageEngine {

    private final String tablesDir;

    public BinaryStorageEngine(String dataDir) {
        this.tablesDir = dataDir + "/tables";
        FileUtils.ensureDirectory(this.tablesDir);
    }

    private String getColumnPath(String table, String column) {
        return tablesDir + "/" + table + "/" + column + ".bin";
    }

    private int typeTag(String colSqlType) {
        switch (colSqlType.toUpperCase()) {
            case "BYTE":       return NumericalPersister.TYPE_BYTE;
            case "SHORT":      return NumericalPersister.TYPE_SHORT;
            case "INT":
            case "INTEGER":    return NumericalPersister.TYPE_INT;
            case "LONG":
            case "BIGINT":     return NumericalPersister.TYPE_LONG;
            case "FLOAT":
            case "REAL":       return NumericalPersister.TYPE_FLOAT;
            case "DOUBLE":
            case "DECIMAL":    return NumericalPersister.TYPE_DOUBLE;
            case "BOOLEAN":
            case "BOOL":       return NumericalPersister.TYPE_BOOLEAN;
            case "BIGDECIMAL":
            case "NUMERIC":    return NumericalPersister.TYPE_BIGDECIMAL;
            case "STRING":
            case "VARCHAR":
            case "TEXT":
            case "CHAR":       return CategoricalPersister.TYPE_STRING;
            default:
                throw new IllegalArgumentException("Unsupported type: " + colSqlType);
        }
    }

    private ColumnPersister getPersister(String path) throws IOException {
        int tag;
        try (DataInputStream dis = new DataInputStream(new FileInputStream(path))) {
            tag = dis.readInt();
        }
        if (tag == CategoricalPersister.TYPE_STRING) {
            return new CategoricalPersister(path);
        } else {
            return new NumericalPersister(path);
        }
    }

    private ColumnPersister getPersister(String table, String column) throws IOException {
        return getPersister(getColumnPath(table, column));
    }

    @Override
    public void createTable(TableSchema schema) throws IOException {
        String tablePath = tablesDir + "/" + schema.getTableName();
        FileUtils.ensureDirectory(tablePath);

        for (ColumnSchema col : schema.getColumns()) {
            int tag = typeTag(col.getType());
            String path = getColumnPath(schema.getTableName(), col.getName());
            ColumnPersister persister;
            if (tag == CategoricalPersister.TYPE_STRING) {
                persister = new CategoricalPersister(path);
            } else {
                persister = new NumericalPersister(path);
            }
            persister.create(tag);
        }
    }

    @Override
    public void appendValue(String table, String column, String value) throws IOException {
        getPersister(table, column).append(value);
    }

    @Override
    public List<String> readColumn(String table, String column) throws IOException {
        String path = getColumnPath(table, column);
        if (!new File(path).exists()) return new ArrayList<>();
        return getPersister(path).readAll();
    }

    @Override
    public void updateValue(String table, String column, int rowIndex, String value) throws IOException {
        getPersister(table, column).update(rowIndex, value);
    }

    @Override
    public void deleteRow(String table, int rowIndex) throws IOException {
        File tableDir = new File(tablesDir + "/" + table);
        File[] colFiles = tableDir.listFiles(f -> f.isFile() && f.getName().endsWith(".bin"));
        if (colFiles == null) return;

        for (File colFile : colFiles) {
            getPersister(colFile.getAbsolutePath()).delete(rowIndex);
        }
    }

    @Override
    public void dropTable(String table) throws IOException {
        File tableDir = new File(tablesDir + "/" + table);
        FileUtils.deleteDirectory(tableDir);
    }
}
