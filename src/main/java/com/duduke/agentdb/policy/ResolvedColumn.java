package com.duduke.agentdb.policy;

import org.jooq.Field;

/**
 * YAML 列定义与数据库真实列对齐后的产物。
 * <p>
 * 关键点：这里持有的是 jOOQ 的 {@link Field} 对象，它来自数据库元数据本身。
 * 生成 SQL 时全程使用它，因此「标识符」从来没有以字符串形式参与过拼接 ——
 * 既天然免疫注入，也天然对得上数据库的大小写规则（H2 大写、PostgreSQL 小写）。
 */
public record ResolvedColumn(
        String name,
        String description,
        boolean selectable,
        boolean filterable,
        boolean sortable,
        boolean groupable,
        Field<?> field
) {

    /** 数据库侧的类型名，用于回给模型以及做值的类型转换。 */
    public String typeName() {
        return field.getDataType().getTypeName();
    }
}
