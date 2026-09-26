package com.duduke.agentdb.tool.dto;

/**
 * 回给模型的列描述。
 * <p>
 * 把四个能力位如实带出去，是为了让模型「知道自己不能做什么」：
 * 它看到 filterable=false 就不会去构造过滤条件，看到 selectable=false 就不会要求输出该列。
 * 与其让它试错到被拒绝，不如一开始就把边界说清楚 —— 这能省下大量往返。
 */
public record ColumnDescriptor(
        String name,
        String type,
        String description,
        boolean selectable,
        boolean filterable,
        boolean sortable,
        boolean groupable
) {
}
