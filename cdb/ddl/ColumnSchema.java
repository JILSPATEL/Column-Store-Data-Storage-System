package cdb.ddl;

import java.util.ArrayList;
import java.util.List;

public class ColumnSchema {
    private String name;
    private String type; // e.g., INT, STRING
    private List<String> constraints; // PRIMARY_KEY, NOT_NULL, UNIQUE

    public ColumnSchema(String name, String type) {
        this.name = name;
        this.type = type;
        this.constraints = new ArrayList<>();
    }

    public void addConstraint(String constraint) {
        this.constraints.add(constraint);
    }

    public String getName() { return name; }
    public String getType() { return type; }
    public boolean hasConstraint(String constraint) {
        return constraints.contains(constraint);
    }
    public List<String> getConstraints() { return constraints; }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("COLUMN ").append(name).append(" ").append(type);
        for (String c : constraints) {
            sb.append(" ").append(c);
        }
        return sb.toString();
    }
}
