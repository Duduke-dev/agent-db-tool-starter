package com.duduke.agentdb.tool.dto;

import java.util.List;

/**
 * describe_table 的结果：某张表的完整可访问列结构。
 */
public record TableDescriptor(String name, String description, List<ColumnDescriptor> columns) {

    public TableDescriptor {
        columns = List.copyOf(columns);
    }
}
