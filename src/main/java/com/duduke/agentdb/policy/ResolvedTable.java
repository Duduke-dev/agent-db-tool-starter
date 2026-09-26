package com.duduke.agentdb.policy;

import org.jooq.Table;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 编译并校验通过的表策略。
 * <p>
 * 表名与列名统一以小写作为检索键，以屏蔽 H2 与 PostgreSQL 在标识符大小写上的差异 ——
 * 数据库里真正的大小写只存在于 {@link #table} 和列上的 Field 对象里。
 */
public record ResolvedTable(
        String name,
        String description,
        boolean queryable,
        Map<String, ResolvedColumn> columns,
        Table<?> table
) {

    public ResolvedTable {
        // 用 LinkedHashMap 包一层再冻结，而不是 Map.copyOf ——
        // 后者不保证迭代顺序，列会以看似随机的次序返回：配置里 id 排第一，输出里可能是 created_at。
        // 这个顺序即使在同一进程内稳定，也是不可预测的，增删列时还会整体打乱。
        columns = Collections.unmodifiableMap(new LinkedHashMap<>(columns));
    }

    /** 按小写列名查找已授权列；找不到返回 null，由调用方决定报哪个错误码。 */
    public ResolvedColumn findColumn(String columnName) {
        if (columnName == null) {
            return null;
        }
        return columns.get(columnName.toLowerCase(Locale.ROOT));
    }

    /** 默认输出列：策略里所有 selectable 的列，保持 YAML 中的书写顺序。 */
    public List<ResolvedColumn> defaultSelectableColumns() {
        return columns.values().stream().filter(ResolvedColumn::selectable).toList();
    }

    public Map<String, ResolvedColumn> orderedColumns() {
        return new LinkedHashMap<>(columns);
    }
}
