package com.duduke.agentdb.error;

/**
 * 工具层错误码。
 * <p>
 * 对外返回错误码而不是自由文本，原因有两点：一是模型能据此判断「这条路走不通」并换策略，
 * 二是排查问题时可以按码聚合，看出 agent 是否在反复试探不该碰的东西。
 */
public enum ToolErrorCode {

    /** 表未登记或 visible=false。注意：对外不区分「不存在」和「不允许」，避免泄露表的存在性。 */
    TABLE_NOT_VISIBLE,
    /** 表可见但 queryable=false。 */
    TABLE_NOT_QUERYABLE,
    /** 列未登记或不在授权集合内。同样不区分「不存在」和「不允许」。 */
    COLUMN_NOT_VISIBLE,
    COLUMN_NOT_SELECTABLE,
    COLUMN_NOT_FILTERABLE,
    COLUMN_NOT_SORTABLE,
    COLUMN_NOT_GROUPABLE,
    /** 传入的值与列的 JDBC 类型不兼容。 */
    INVALID_VALUE_TYPE,
    /** 聚合函数用在了不支持的列上，例如对文本列求 SUM。 */
    INVALID_AGGREGATE,
    /** 查询里出现了聚合函数，但某个输出列既没被聚合也不在 groupBy 里，SQL 语义不成立。 */
    NON_GROUPED_COLUMN_IN_SELECT,
    IN_CLAUSE_TOO_LARGE,
    /** 一次请求涉及的表数量超过上限。 */
    TOO_MANY_TABLES,
    /** 一次请求包含的查询条数超过上限。 */
    TOO_MANY_QUERIES,
    LIMIT_EXCEEDED,
    INVALID_IDENTIFIER,
    /**
     * 权限编码本身有问题：读不到、不是合法 JSON，或者编码里登记的表 / 列在数据库中并不存在。
     * 这是配置问题不是调用问题，但仍然通过工具层返回，因为 agent 需要知道「查不到不是没有数据」。
     */
    POLICY_INVALID
}
