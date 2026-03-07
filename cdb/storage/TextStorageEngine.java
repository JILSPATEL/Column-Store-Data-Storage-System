package cdb.storage;

import cdb.ddl.ColumnSchema;
import cdb.ddl.TableSchema;
import cdb.util.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

public class TextStorageEngine implements StorageEngine {
    private String tablesDir;

    public TextStorageEngine(String dataDir) {
        this.tablesDir = dataDir + "/tables";
        FileUtils.ensureDirectory(this.tablesDir);
    }

    private String getColumnPath(String table, String column) {
        return tablesDir + "/" + table + "/" + column + ".col";
    }

    @Override
    public List<String> readColumn(String table, String column) throws IOException {
        String path = getColumnPath(table, column);
        File file = new File(path);
        if (!file.exists())
            return new ArrayList<>();
        return Files.readAllLines(Paths.get(path));
    }

    @Override
    public void appendValue(String table, String column, String value) throws IOException {
        String path = getColumnPath(table, column);
        FileUtils.ensureFile(path);
        String line = value + "\n";
        Files.write(Paths.get(path), line.getBytes(), StandardOpenOption.APPEND);
    }

    @Override
    public void updateValue(String table, String column, int rowIndex, String value) throws IOException {
        String path = getColumnPath(table, column);
        List<String> lines = readColumn(table, column);
        if (rowIndex >= 0 && rowIndex < lines.size()) {
            lines.set(rowIndex, value);
            if (lines.isEmpty()) {
                Files.write(Paths.get(path), "".getBytes());
            } else {
                Files.write(Paths.get(path), String.join("\n", lines).getBytes());
                Files.write(Paths.get(path), "\n".getBytes(), StandardOpenOption.APPEND);
            }
        }
    }

    @Override
    public void deleteRow(String table, int rowIndex) throws IOException {
        File tableDir = new File(tablesDir + "/" + table);
        File[] columns = tableDir.listFiles();
        if (columns != null) {
            for (File colFile : columns) {
                if (colFile.getName().endsWith(".col")) {
                    List<String> lines = Files.readAllLines(colFile.toPath());
                    if (rowIndex >= 0 && rowIndex < lines.size()) {
                        lines.remove(rowIndex);
                        if (lines.isEmpty()) {
                            Files.write(colFile.toPath(), "".getBytes());
                        } else {
                            Files.write(colFile.toPath(), String.join("\n", lines).getBytes());
                            Files.write(colFile.toPath(), "\n".getBytes(), StandardOpenOption.APPEND);
                        }
                    }
                }
            }
        }
    }

    @Override
    public void createTable(TableSchema schema) throws IOException {
        String tablePath = tablesDir + "/" + schema.getTableName();
        FileUtils.ensureDirectory(tablePath);
        for (ColumnSchema col : schema.getColumns()) {
            FileUtils.ensureFile(getColumnPath(schema.getTableName(), col.getName()));
        }
    }

    @Override
    public void dropTable(String table) throws IOException {
        File tableDir = new File(tablesDir + "/" + table);
        FileUtils.deleteDirectory(tableDir);
    }
}
