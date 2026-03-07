package cdb.storage;

import cdb.ddl.TableSchema;
import java.util.List;
import java.io.IOException;

public interface StorageEngine {
    List<String> readColumn(String table, String column) throws IOException;
    void appendValue(String table, String column, String value) throws IOException;
    void updateValue(String table, String column, int rowIndex, String value) throws IOException;
    void deleteRow(String table, int rowIndex) throws IOException;
    void createTable(TableSchema schema) throws IOException;
    void dropTable(String table) throws IOException;
}
