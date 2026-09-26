package com.duduke.agentdb.query;

import org.jooq.Record;
import org.jooq.SelectQuery;

import java.util.List;

/**
 * 编译产物：一条值全部参数化的 jOOQ 查询，外加输出列名。
 *
 * @param outputLabels   输出列名，顺序与编译时的 select 严格一致
 * @param effectiveLimit 实际下推到 SQL 的 LIMIT，比业务 limit 多 1，用于判断是否被截断
 */
public record CompiledQuery(
        String table,
        SelectQuery<Record> query,
        List<String> outputLabels,
        int effectiveLimit
) {

    public CompiledQuery {
        outputLabels = List.copyOf(outputLabels);
    }
}
