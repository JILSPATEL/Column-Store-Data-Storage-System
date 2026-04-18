package cdb.query.querytypes;

import java.util.Collections;
import java.util.List;

/**
 * Represents a WHERE clause composed of one or more conditions joined by a
 * single logical operator (AND / OR).
 *
 * Examples:
 *   WHERE id > 50                           → 1 condition, operator irrelevant
 *   WHERE id > 50 AND salary > 30000        → 2 conditions, operator = AND
 *   WHERE dept = 'HR' OR dept = 'Finance'   → 2 conditions, operator = OR
 */
public class WhereClause {

    public enum LogicalOp { AND, OR }

    private final List<WhereCondition> conditions;
    private final LogicalOp logicalOp;   // how conditions are combined (ignored when size == 1)

    public WhereClause(List<WhereCondition> conditions, LogicalOp logicalOp) {
        if (conditions == null || conditions.isEmpty()) {
            throw new IllegalArgumentException("WhereClause must have at least one condition.");
        }
        this.conditions = Collections.unmodifiableList(conditions);
        this.logicalOp  = logicalOp;
    }

    /** Convenience constructor for a single-condition WHERE clause. */
    public WhereClause(WhereCondition single) {
        this(List.of(single), LogicalOp.AND);
    }

    public List<WhereCondition> getConditions() { return conditions; }
    public LogicalOp getLogicalOp()             { return logicalOp; }

    /** True when there is no filter at all (caller should return all rows). */
    public boolean isEmpty() { return conditions.isEmpty(); }
}
