package com.duduke.agentdb.query.dsl;

/**
 * 允许的比较操作符白名单。
 * <p>
 * 刻意不提供「原始 SQL 片段」这种口子。模糊匹配拆成 STARTS_WITH / ENDS_WITH / CONTAINS，
 * 是为了让通配符由服务端转义后拼接 —— 模型传进来的 % 和 _ 一律当字面量处理。
 */
public enum Operator {
    EQ,
    NE,
    GT,
    GE,
    LT,
    LE,
    /** 精确匹配一组值，元素个数受 limits.max-in-values 限制。 */
    IN,
    /** 包含子串（两侧加通配符，但转义传入的通配符字符）。 */
    CONTAINS,
    /** 以某串开头。 */
    STARTS_WITH,
    /** 以某串结尾。 */
    ENDS_WITH,
    IS_NULL,
    IS_NOT_NULL
}
