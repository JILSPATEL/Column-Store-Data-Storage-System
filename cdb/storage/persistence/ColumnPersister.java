package cdb.storage.persistence;

import java.io.IOException;
import java.util.List;

public interface ColumnPersister {
    void create(int tag) throws IOException;
    void append(String value) throws IOException;
    List<String> readAll() throws IOException;
    void update(int rowIndex, String value) throws IOException;
    void delete(int rowIndex) throws IOException;
    int getTag() throws IOException;
}
