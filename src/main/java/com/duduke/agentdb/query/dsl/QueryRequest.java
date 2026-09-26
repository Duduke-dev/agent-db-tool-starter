package com.duduke.agentdb.query.dsl;

import java.util.List;

/**
 * 模型能构造出来的全部查询表达能力 —— 到此为止。
 * <p>
 * 这里没有 join、没有子查询、没有 SQL 字符串、没有 union。跨表分析不靠放开语法实现，
 * 而是靠提前建好只读视图、再把视图当普通表登记进策略。
 *
 * @param table   目标表名
 * @param select  输出列；为空时取策略里所有 selectable 的列
 * @param where   过滤条件；多条之间是 AND 关系
 * @param groupBy 分组列
 * @param orderBy 排序项
 * @param limit   返回行数，超过策略上限会被拒绝而不是静默截断
 */
public record QueryRequest(
        String table,
        List<SelectItem> select,
        List<Condition> where,
        List<String> groupBy,
        List<OrderItem> orderBy,
        Integer limit
) {

    public QueryRequest {
        select = (select == null) ? List.of() : List.copyOf(select);
        where = (where == null) ? List.of() : List.copyOf(where);
        groupBy = (groupBy == null) ? List.of() : List.copyOf(groupBy);
        orderBy = (orderBy == null) ? List.of() : List.copyOf(orderBy);
    }

    public static QueryRequest of(String table) {
        return new QueryRequest(table, List.of(), List.of(), List.of(), List.of(), null);
    }
}
