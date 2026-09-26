package com.duduke.agentdb.query.dsl;

/**
 * 排序项。列必须具备 sortable 能力位。
 */
public record OrderItem(String column, SortDirection direction) {

    public OrderItem {
        direction = (direction == null) ? SortDirection.ASC : direction;
    }
}
