package cdb.query.querytypes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class WhereClause {

    private final List<List<WhereCondition>> orGroups;

    public WhereClause(List<List<WhereCondition>> orGroups) {
        if (orGroups == null || orGroups.isEmpty()) {
            throw new IllegalArgumentException("WhereClause must have at least one condition.");
        }
        
        // Make immutable
        List<List<WhereCondition>> immutableGroups = new ArrayList<>();
        for (List<WhereCondition> group : orGroups) {
            if (group == null || group.isEmpty()) {
                throw new IllegalArgumentException("WhereClause OR group cannot be empty.");
            }
            immutableGroups.add(Collections.unmodifiableList(new ArrayList<>(group)));
        }
        this.orGroups = Collections.unmodifiableList(immutableGroups);
    }

    public WhereClause(WhereCondition single) {
        this(List.of(List.of(single)));
    }

    public List<List<WhereCondition>> getOrGroups() { return orGroups; }

    public List<WhereCondition> getAllConditions() {
        List<WhereCondition> all = new ArrayList<>();
        for (List<WhereCondition> group : orGroups) {
            all.addAll(group);
        }
        return all;
    }

    public boolean isEmpty() { return orGroups.isEmpty(); }
}
