package cdb.query.querytypes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class WhereClause {

    // Outer list: OR groups. Inner list: conditions joined by AND.
    // E.g. (A AND B) OR (C) OR (D AND E)
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

    /** Convenience constructor for a single-condition WHERE clause. */
    public WhereClause(WhereCondition single) {
        this(List.of(List.of(single)));
    }

    public List<List<WhereCondition>> getOrGroups() { return orGroups; }

    /** Returns all unique conditions, useful for pre-loading columns in sequential scan. */
    public List<WhereCondition> getAllConditions() {
        List<WhereCondition> all = new ArrayList<>();
        for (List<WhereCondition> group : orGroups) {
            all.addAll(group);
        }
        return all;
    }

    /** True when there is no filter at all (caller should return all rows). */
    public boolean isEmpty() { return orGroups.isEmpty(); }
}
