package com.duduke.agentdb.query.dsl;

import java.util.List;

/**
 * 一个 WHERE 条件。值统一用字符串承载，由编译阶段按列的真实 JDBC 类型转换 ——
 * 这样模型只能提供值，而值的类型解释权在服务端。
 *
 * @param column   列名
 * @param operator 比较操作符，必须属于 {@link Operator} 白名单
 * @param values   参数值；IS_NULL / IS_NOT_NULL 忽略它，其余操作符需要恰好一个值
 */
public record Condition(String column, Operator operator, List<String> values) {

    public Condition {
        values = (values == null) ? List.of() : List.copyOf(values);
    }

    public static Condition of(String column, Operator operator, String value) {
        return new Condition(column, operator, List.of(value));
    }
}
