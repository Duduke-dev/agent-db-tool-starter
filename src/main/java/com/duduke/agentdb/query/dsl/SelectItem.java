package com.duduke.agentdb.query.dsl;

/**
 * 一个输出列。这些字段全部是「数据」而不是「SQL 片段」，模型无法在其中夹带语法结构。
 *
 * @param column    列名（此处不做校验，授权校验统一在编译阶段进行）
 * @param aggregate 聚合方式，缺省 NONE 表示直接取列值
 * @param alias     结果集里的列名，缺省与 column 相同
 */
public record SelectItem(String column, Aggregate aggregate, String alias) {

    public SelectItem {
        aggregate = (aggregate == null) ? Aggregate.NONE : aggregate;
        alias = (alias == null || alias.isBlank()) ? column : alias;
    }

    public static SelectItem of(String column) {
        return new SelectItem(column, Aggregate.NONE, column);
    }
}
