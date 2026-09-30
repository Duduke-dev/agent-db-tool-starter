package com.duduke.agentdb.template;

import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;

/**
 * 查询模板表的 jOOQ 引用。
 * <p>
 * 独立于 {@code policy.PolicySchema}：模板是另一套东西 —— 授权表回答「能碰哪些表和列」，
 * 模板表回答「有哪些预定义查询」。两者生命周期不同（授权随业务表变，模板随统计口径变），
 * 混在一处会让「改授权」和「加模板」看起来像同一件事。
 * <p>
 * 标识符同样不加引号，交给数据库按自己的规则归一化大小写。
 */
final class QueryTemplateSchema {

    static final Table<?> QUERY_TEMPLATE = DSL.table("agent_query_template");
    static final Field<String> QT_NAME = DSL.field("template_name", String.class);
    static final Field<String> QT_DESCRIPTION = DSL.field("description", String.class);
    static final Field<Boolean> QT_VISIBLE = DSL.field("visible", Boolean.class);
    static final Field<String> QT_TEMPLATE_JSON = DSL.field("template_json", String.class);
    static final Field<String> QT_PARAMETERS_JSON = DSL.field("parameters_json", String.class);

    private QueryTemplateSchema() {
    }
}
