package cdb.query;

import cdb.ddl.ColumnSchema;
import cdb.ddl.TableSchema;
import cdb.query.querytypes.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class QueryParser {
    public Query parse(String query) {
        query = query.trim();
        if (query.toUpperCase().startsWith("CREATE TABLE")) {
            return parseCreateTable(query);
        } else if (query.toUpperCase().startsWith("INSERT INTO")) {
            return parseInsert(query);
        } else if (query.toUpperCase().startsWith("SELECT")) {
            return parseSelect(query);
        } else if (query.toUpperCase().startsWith("UPDATE")) {
            return parseUpdate(query);
        } else if (query.toUpperCase().startsWith("DELETE FROM")) {
            return parseDelete(query);
        }
        throw new IllegalArgumentException("Unsupported query: " + query);
    }

    private CreateTableQuery parseCreateTable(String query) {
        Pattern pattern = Pattern.compile("CREATE\\s+TABLE\\s+(\\w+)\\s*\\((.*)\\)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(query);
        if (matcher.find()) {
            String tableName = matcher.group(1);
            String columnsPart = matcher.group(2);
            TableSchema schema = new TableSchema(tableName);

            String[] colDefs = columnsPart.split(",");
            for (String colDef : colDefs) {
                String[] parts = colDef.trim().split("\\s+");
                if (parts.length >= 2) {
                    ColumnSchema col = new ColumnSchema(parts[0], parts[1]);
                    for (int i = 2; i < parts.length; i++) {
                        col.addConstraint(parts[i]);
                    }
                    schema.addColumn(col);
                }
            }
            return new CreateTableQuery(schema);
        }
        throw new IllegalArgumentException("Invalid CREATE TABLE syntax");
    }

    private InsertQuery parseInsert(String query) {
        Pattern pattern = Pattern.compile("INSERT\\s+INTO\\s+(\\w+)\\s+VALUES\\s*\\((.*)\\)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(query);
        if (matcher.find()) {
            String tableName = matcher.group(1);
            String valuesPart = matcher.group(2);
            String[] valStrings = valuesPart.split(",");
            List<String> values = new ArrayList<>();
            for (String v : valStrings) {
                values.add(v.trim().replace("\"", "").replace("'", "").replace("“", "").replace("”", ""));
            }
            return new InsertQuery(tableName, values);
        }
        throw new IllegalArgumentException("Invalid INSERT syntax");
    }

    private String[] extractWhere(String query) {
        String mainPart = query;
        String filterCol = null, filterOp = null, filterVal = null;
        String upper = query.toUpperCase();
        int whereIdx = upper.lastIndexOf(" WHERE ");
        if (whereIdx != -1) {
            String whereClause = query.substring(whereIdx + 7).trim();
            mainPart = query.substring(0, whereIdx).trim();
            Pattern wherePat = Pattern.compile("(\\w+)\\s*([=><])\\s*(.*)");
            Matcher w = wherePat.matcher(whereClause);
            if (w.find()) {
                filterCol = w.group(1);
                filterOp = w.group(2);
                filterVal = w.group(3).trim().replace("\"", "").replace("'", "").replace("“", "").replace("”", "");
            }
        }
        return new String[] { mainPart, filterCol, filterOp, filterVal };
    }

    private SelectQuery parseSelect(String query) {
        String[] parts = extractWhere(query);
        String mainPart = parts[0];
        Pattern pattern = Pattern.compile("SELECT\\s+(.*?)\\s+FROM\\s+(\\w+)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(mainPart);
        if (matcher.find()) {
            String colsPart = matcher.group(1);
            String tableName = matcher.group(2);
            List<String> cols = new ArrayList<>();
            for (String c : colsPart.split(",")) {
                cols.add(c.trim());
            }
            return new SelectQuery(tableName, cols, parts[1], parts[2], parts[3]);
        }
        throw new IllegalArgumentException("Invalid SELECT syntax");
    }

    private UpdateQuery parseUpdate(String query) {
        String[] parts = extractWhere(query);
        String mainPart = parts[0];
        Pattern pattern = Pattern.compile("UPDATE\\s+(\\w+)\\s+SET\\s+(\\w+)\\s*=\\s*(.*)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(mainPart);
        if (matcher.find()) {
            String tableName = matcher.group(1);
            String setCol = matcher.group(2);
            String setVal = matcher.group(3).trim().replace("\"", "").replace("'", "").replace("“", "").replace("”",
                    "");
            return new UpdateQuery(tableName, setCol, setVal, parts[1], parts[2], parts[3]);
        }
        throw new IllegalArgumentException("Invalid UPDATE syntax");
    }

    private DeleteQuery parseDelete(String query) {
        String[] parts = extractWhere(query);
        String mainPart = parts[0];
        Pattern pattern = Pattern.compile("DELETE\\s+FROM\\s+(\\w+)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(mainPart);
        if (matcher.find()) {
            String tableName = matcher.group(1);
            return new DeleteQuery(tableName, parts[1], parts[2], parts[3]);
        }
        throw new IllegalArgumentException("Invalid DELETE syntax");
    }
}
