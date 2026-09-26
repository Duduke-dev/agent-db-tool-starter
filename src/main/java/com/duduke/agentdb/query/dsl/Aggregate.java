package com.duduke.agentdb.query.dsl;

/**
 * 聚合函数白名单。聚合列必须同时具备 groupable 能力位，见 QueryValidator。
 */
public enum Aggregate {
    /** 不聚合，直接取列值。 */
    NONE,
    COUNT,
    SUM,
    AVG,
    MIN,
    MAX;

    public boolean isAggregated() {
        return this != NONE;
    }
}
