package com.duduke.agentdb.tool.dto;

/**
 * list_tables 的条目：只给出表名与语义描述，不下发列结构。
 * <p>
 * 表多的时候，把所有列一次性铺开会把上下文撑满，也更没必要 ——
 * 模型先看有哪些表，再对感兴趣的那张调 describe_table 就够了。
 */
public record TableSummary(String name, String description, int columnCount) {
}
