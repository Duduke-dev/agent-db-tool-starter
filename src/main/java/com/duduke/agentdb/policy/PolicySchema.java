package com.duduke.agentdb.policy;

import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;

import java.util.List;

/**
 * 两张授权表的 jOOQ 引用，由 {@link PolicyRepository} 与 {@link PolicyTableInitializer} 共用。
 * <p>
 * 抽出来是为了让表的定义只有一处：读授权、建表、登记清单三个场景用的是同一批标识符，
 * 各自抄一遍迟早会不一致。
 * <p>
 * 标识符刻意不加引号（不用 {@code DSL.name} 包装），让数据库按自己的规则归一化大小写，
 * H2（转大写）与 PostgreSQL（转小写）都能对上；加了引号反而会因大小写不一致而找不到列。
 */
final class PolicySchema {

    static final Table<?> TABLE_POLICY = DSL.table("agent_table_policy");
    static final Field<String> TP_NAME = DSL.field("table_name", String.class);
    static final Field<String> TP_DESCRIPTION = DSL.field("description", String.class);
    static final Field<Boolean> TP_VISIBLE = DSL.field("visible", Boolean.class);
    static final Field<Boolean> TP_QUERYABLE = DSL.field("queryable", Boolean.class);

    static final Table<?> COLUMN_POLICY = DSL.table("agent_column_policy");
    static final Field<String> CP_TABLE = DSL.field("table_name", String.class);
    static final Field<String> CP_COLUMN = DSL.field("column_name", String.class);
    static final Field<String> CP_DESCRIPTION = DSL.field("description", String.class);
    static final Field<Boolean> CP_ACCESSIBLE = DSL.field("accessible", Boolean.class);
    static final Field<Boolean> CP_SELECTABLE = DSL.field("selectable", Boolean.class);
    static final Field<Boolean> CP_FILTERABLE = DSL.field("filterable", Boolean.class);
    static final Field<Boolean> CP_SORTABLE = DSL.field("sortable", Boolean.class);
    static final Field<Boolean> CP_GROUPABLE = DSL.field("groupable", Boolean.class);

    /** 授权表自身不能出现在清单里，否则 agent 能读到整份授权配置。 */
    static final List<String> RESERVED = List.of("agent_table_policy", "agent_column_policy");

    private PolicySchema() {
    }
}
