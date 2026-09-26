package com.duduke.agentdb.policy;

import com.duduke.agentdb.config.AgentDbProperties;
import com.duduke.agentdb.error.AgentToolException;
import com.duduke.agentdb.error.ToolErrorCode;
import com.duduke.agentdb.policy.model.ResolvedColumn;
import com.duduke.agentdb.policy.model.ResolvedTable;
import com.duduke.agentdb.policy.model.TableOverview;
import org.jooq.DSLContext;
import org.jooq.DataType;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static com.duduke.agentdb.policy.PolicySchema.COLUMN_POLICY;
import static com.duduke.agentdb.policy.PolicySchema.CP_ACCESSIBLE;
import static com.duduke.agentdb.policy.PolicySchema.CP_COLUMN;
import static com.duduke.agentdb.policy.PolicySchema.CP_DESCRIPTION;
import static com.duduke.agentdb.policy.PolicySchema.CP_FILTERABLE;
import static com.duduke.agentdb.policy.PolicySchema.CP_GROUPABLE;
import static com.duduke.agentdb.policy.PolicySchema.CP_SELECTABLE;
import static com.duduke.agentdb.policy.PolicySchema.CP_SORTABLE;
import static com.duduke.agentdb.policy.PolicySchema.CP_TABLE;
import static com.duduke.agentdb.policy.PolicySchema.TABLE_POLICY;
import static com.duduke.agentdb.policy.PolicySchema.TP_DESCRIPTION;
import static com.duduke.agentdb.policy.PolicySchema.TP_NAME;
import static com.duduke.agentdb.policy.PolicySchema.TP_QUERYABLE;
import static com.duduke.agentdb.policy.PolicySchema.TP_VISIBLE;

/**
 * 授权策略的唯一来源，落在数据库的两张表里，<b>每次调用都实时读取</b>。
 * <p>
 * 默认拒绝是靠表结构本身实现的，不需要额外的过滤逻辑：不在 agent_table_policy 里的表名
 * agent 看不见，不在 agent_column_policy 里的列名同样看不见。也就不存在
 * 「过滤条件哪天被写错」这种风险。
 * <p>
 * 没有任何缓存、也没有启动期预加载 —— 改完这两张表，下一次工具调用立即生效。
 * 代价是每次查询多两次数据库往返（读授权 + 读涉及表的列结构），对面向 agent 的低频工具完全划算。
 */
@Repository
public class PolicyRepository {

    private static final Logger log = LoggerFactory.getLogger(PolicyRepository.class);

    // 两张授权表的 Table / Field 定义统一放在 PolicySchema —— 建表、登记清单、读授权
    // 三处用的是同一批标识符，各写一份迟早会不一致。
    // 标识符刻意不加引号（不用 DSL.name 包装），让数据库按自己的规则归一化大小写，
    // H2（转大写）与 PostgreSQL（转小写）都能对上；加了引号反而会因为大小写不一致而找不到列。

    private final DataSource dataSource;
    private final DSLContext dsl;
    private final AgentDbProperties properties;

    public PolicyRepository(DataSource dataSource, DSLContext dsl, AgentDbProperties properties) {
        this.dataSource = dataSource;
        this.dsl = dsl;
        this.properties = properties;
    }

    public AgentDbProperties.Limits limits() {
        return properties.limits();
    }

    /**
     * 供 list_tables 使用：只要表名、说明、可访问列数。
     * <p>
     * <b>完全不读数据库元数据</b> —— 列数直接在授权表里数出来，不必为了知道「有几列」
     * 去查 DatabaseMetaData（真正的结构校验在 {@link #require(String)} 那一侧做）。
     * 授权表多的时候，这一步能省掉几十次元数据查询。
     * <p>
     * 代价是它不做「列名在数据库里是否存在」的校验，所以可能出现一张列数被数成 0 的表，
     * 等真要查它时才会报 POLICY_INVALID。
     */
    public List<TableOverview> visibleTableOverviews() {
        // 分两次查而不是 join：两张授权表都有 table_name 列，而 DSL.field(String) 构造出的字段
        // 不带表限定，join 时会生成 `lower(table_name) = lower(table_name)` 这种两边同名、
        // 数据库直接报歧义的 SQL。授权表很小，两次轻查询比为此引入表别名和限定字段更简单。
        // 数的是「可访问的列数」，而不是登记行数：字段清单是全量的，登记数不等于 agent 能看到的数
        Map<String, Integer> columnCounts = dsl
                .select(CP_TABLE, DSL.count())
                .from(COLUMN_POLICY)
                .where(CP_ACCESSIBLE.isTrue())
                .groupBy(CP_TABLE)
                .fetch()
                .stream()
                // 按位置取值而不是按列名：默认列名的大小写在 H2 与 PostgreSQL 上不一致
                .collect(Collectors.toMap(
                        record -> record.get(0, String.class).toLowerCase(Locale.ROOT),
                        record -> record.get(1, Integer.class),
                        (first, second) -> first));

        return dsl
                .select(TP_NAME, TP_DESCRIPTION)
                .from(TABLE_POLICY)
                .where(TP_VISIBLE.isTrue())
                .orderBy(TP_NAME.asc())
                .fetch()
                .stream()
                .map(record -> {
                    String name = record.get(0, String.class);
                    return new TableOverview(
                            name,
                            record.get(1, String.class),
                            columnCounts.getOrDefault(name.toLowerCase(Locale.ROOT), 0));
                })
                .toList();
    }

    /**
     * 供 describe_table / query 使用：实时取一张表的完整策略。
     * <p>
     * 找不到时统一报 TABLE_NOT_VISIBLE，不区分「表不存在」和「未授权」——
     * 一旦区分，agent 就能靠错误码差异把整个库的表名枚举出来，
     * 「从授权出发」这个方向带来的好处也就白费了。
     */
    public ResolvedTable require(String tableName) {
        TablePolicyRow row = findVisibleRow(tableName);
        if (row == null) {
            throw new AgentToolException(ToolErrorCode.TABLE_NOT_VISIBLE,
                    "表 '%s' 不在可访问范围内。当前可访问的表：%s".formatted(tableName, visibleTableNames()));
        }
        return compile(row, loadColumnPolicies(row.tableName()));
    }

    /**
     * 与 {@link #require(String)} 相同，但表不可访问时返回 {@code null} 而不是抛异常。
     * <p>
     * 供批量查询使用：一次问多张表时，个别表名拼错或没授权不该让整批失败 ——
     * 能给的先给出去，模型自己会从返回结果里对出少了哪张。
     */
    public ResolvedTable findOrNull(String tableName) {
        TablePolicyRow row = findVisibleRow(tableName);
        return (row == null) ? null : compile(row, loadColumnPolicies(row.tableName()));
    }

    /** 查授权表，返回可见的表策略行；未登记或 visible=false 时返回 null。 */
    private TablePolicyRow findVisibleRow(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return null;
        }
        TablePolicyRow row = dsl
                .select(TP_NAME, TP_DESCRIPTION, TP_VISIBLE, TP_QUERYABLE)
                .from(TABLE_POLICY)
                .where(TP_NAME.equalIgnoreCase(tableName))
                .fetchOne(this::toTablePolicyRow);
        return (row != null && row.visible()) ? row : null;
    }

    // ------------------------------------------------------------------ 读授权表

    private List<ColumnPolicyRow> loadColumnPolicies(String tableName) {
        return dsl
                .select(CP_COLUMN, CP_DESCRIPTION, CP_ACCESSIBLE, CP_SELECTABLE, CP_FILTERABLE, CP_SORTABLE, CP_GROUPABLE)
                .from(COLUMN_POLICY)
                .where(CP_TABLE.equalIgnoreCase(tableName))
                .orderBy(CP_COLUMN.asc())
                .fetch(this::toColumnPolicyRow);
    }

    private String visibleTableNames() {
        String joined = dsl
                .select(TP_NAME)
                .from(TABLE_POLICY)
                .where(TP_VISIBLE.isTrue())
                .orderBy(TP_NAME.asc())
                .fetch(TP_NAME)
                .stream()
                .collect(Collectors.joining("、"));
        return joined.isEmpty() ? "（无）" : joined;
    }

    private TablePolicyRow toTablePolicyRow(Record record) {
        return new TablePolicyRow(
                record.get(TP_NAME),
                record.get(TP_DESCRIPTION),
                Boolean.TRUE.equals(record.get(TP_VISIBLE)),
                Boolean.TRUE.equals(record.get(TP_QUERYABLE)));
    }

    private ColumnPolicyRow toColumnPolicyRow(Record record) {
        return new ColumnPolicyRow(
                record.get(CP_COLUMN),
                record.get(CP_DESCRIPTION),
                Boolean.TRUE.equals(record.get(CP_ACCESSIBLE)),
                Boolean.TRUE.equals(record.get(CP_SELECTABLE)),
                Boolean.TRUE.equals(record.get(CP_FILTERABLE)),
                Boolean.TRUE.equals(record.get(CP_SORTABLE)),
                Boolean.TRUE.equals(record.get(CP_GROUPABLE)));
    }

    // ------------------------------------------------------------------ 编译

    private ResolvedTable compile(TablePolicyRow tablePolicy, List<ColumnPolicyRow> columnPolicies) {
        TableMetadata metadata = readColumns(tablePolicy.tableName());
        if (metadata.columns().isEmpty()) {
            throw new AgentToolException(ToolErrorCode.POLICY_INVALID,
                    "agent_table_policy 里登记了表 '%s'，但数据库中找不到这张表".formatted(tablePolicy.tableName()));
        }
        Map<String, ResolvedColumn> columns = new LinkedHashMap<>();
        for (ColumnPolicyRow columnPolicy : columnPolicies) {
            // 字段清单是全量登记的，能不能访问由 accessible 决定
            if (!columnPolicy.accessible()) {
                continue;
            }
            ColumnMetadata columnMeta = metadata.columns().get(columnPolicy.columnName().toLowerCase(Locale.ROOT));
            if (columnMeta == null) {
                throw new AgentToolException(ToolErrorCode.POLICY_INVALID,
                        "agent_column_policy 里登记了表 '%s' 的列 '%s'，但数据库中不存在该列"
                                .formatted(tablePolicy.tableName(), columnPolicy.columnName()));
            }
            columns.put(columnPolicy.columnName().toLowerCase(Locale.ROOT), new ResolvedColumn(
                    columnPolicy.columnName(),
                    columnPolicy.description(),
                    columnPolicy.selectable(),
                    columnPolicy.filterable(),
                    columnPolicy.sortable(),
                    columnPolicy.groupable(),
                    columnMeta.field()));
        }

        if (columns.isEmpty()) {
            throw new AgentToolException(ToolErrorCode.POLICY_INVALID,
                    "表 '%s' 在 agent_column_policy 里登记了 %d 个字段，但没有一个是可访问的（accessible 全为 false）"
                            .formatted(tablePolicy.tableName(), columnPolicies.size()));
        }

        return new ResolvedTable(
                tablePolicy.tableName(),
                tablePolicy.description(),
                tablePolicy.queryable(),
                columns,
                DSL.table(DSL.name(metadata.actualTableName())));
    }

    /**
     * 实时读某张表的列结构。
     * <p>
     * 走 JDBC 的 {@link DatabaseMetaData#getColumns}，只查这一张表（走索引），
     * 不是全量扫描 information_schema。拿到的不仅是类型，还有数据库里标识符的<b>真实大小写</b> ——
     * 后面构造 jOOQ 的 Field 对象要用它，这样生成 SQL 时不会在 H2（大写）和 PostgreSQL（小写）上失配。
     */
    private TableMetadata readColumns(String tableName) {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();

            // 先定位表，拿到数据库自己报的 catalog / schema —— 不去猜命名空间
            TableLocation location = locateTable(meta, connection, tableName);
            if (location == null) {
                // 找不到就返回空列集，由调用方统一报「策略登记了但库里没有」
                return new TableMetadata(tableName, Map.of());
            }

            Map<String, ColumnMetadata> columns = new LinkedHashMap<>();
            try (ResultSet rs = meta.getColumns(
                    location.catalog(), location.schema(), location.tableName(), null)) {
                while (rs.next()) {
                    String actualColumn = rs.getString("COLUMN_NAME");
                    int jdbcType = rs.getInt("DATA_TYPE");
                    Field<?> field = DSL.field(
                            DSL.name(location.tableName(), actualColumn),
                            sqlDataTypeOf(jdbcType));
                    columns.put(actualColumn.toLowerCase(Locale.ROOT), new ColumnMetadata(actualColumn, field));
                }
            }
            return new TableMetadata(location.tableName(), columns);
        } catch (SQLException ex) {
            throw new AgentToolException(ToolErrorCode.POLICY_INVALID,
                    "读取表 '%s' 的结构时数据库报错：%s".formatted(tableName, ex.getMessage()));
        }
    }

    /**
     * 用数据库自己的元数据定位一张表，返回它的 catalog / schema / 真实表名。
     * <p>
     * 为什么不直接取 {@code connection.getSchema()} 再兜底 {@code getCatalog()}：
     * JDBC 的 catalog / schema 是「想把各家的命名空间统一但没统一成」的历史产物 ——
     * PostgreSQL 用 schema（catalog 常为 null），MySQL 用 catalog（schema 为 null），
     * SQL Server 两个都用。靠猜只是在两个库上碰巧都对，换一个就错。
     * <p>
     * 反过来，{@code getTables()} 返回的每一行都带着 {@code TABLE_CAT} / {@code TABLE_SCHEM}，
     * 那是数据库自己报的真实位置 —— 直接用它，不用猜。
     * <p>
     * 有同名表落在多个命名空间时，优先取与当前连接一致的那个；都没有则取第一个。
     */
    private TableLocation locateTable(DatabaseMetaData meta, Connection connection, String tableName)
            throws SQLException {
        String pattern = toDatabaseCase(meta, tableName);
        String currentSchema = connection.getSchema();
        String currentCatalog = connection.getCatalog();

        TableLocation firstMatch = null;
        try (ResultSet rs = meta.getTables(null, null, pattern, new String[]{"TABLE"})) {
            while (rs.next()) {
                TableLocation candidate = new TableLocation(
                        rs.getString("TABLE_CAT"),
                        rs.getString("TABLE_SCHEM"),
                        rs.getString("TABLE_NAME"));

                if (sameNamespace(currentSchema, candidate.schema())
                        || sameNamespace(currentCatalog, candidate.catalog())) {
                    return candidate;
                }
                if (firstMatch == null) {
                    firstMatch = candidate;
                }
            }
        }
        return firstMatch;
    }

    /** 命名空间相等判断：大小写不敏感，null 与空串都视为「没这个值」。 */
    private static boolean sameNamespace(String current, String candidate) {
        return current != null && !current.isBlank()
                && candidate != null && current.equalsIgnoreCase(candidate);
    }

    /**
     * 元数据的表名匹配是大小写敏感的，而 H2 存大写、PostgreSQL 存小写。
     * 用驱动自己报告的标识符规则来判断，比硬编码数据库类型可靠。
     */
    private String toDatabaseCase(DatabaseMetaData meta, String name) throws SQLException {
        if (meta.storesLowerCaseIdentifiers()) {
            return name.toLowerCase(Locale.ROOT);
        }
        if (meta.storesUpperCaseIdentifiers()) {
            return name.toUpperCase(Locale.ROOT);
        }
        return name;
    }

    /**
     * JDBC 类型到 jOOQ 数据类型的映射。
     * <p>
     * 这里只需要「列的 Java 类型」，用于把模型给的值转成正确的类型再绑定；
     * 长度、精度这些属性对值转换没有影响，所以不逐列回填。
     */
    private DataType<?> sqlDataTypeOf(int jdbcType) {
        return switch (jdbcType) {
            case Types.BIGINT -> SQLDataType.BIGINT;
            case Types.INTEGER -> SQLDataType.INTEGER;
            case Types.SMALLINT -> SQLDataType.SMALLINT;
            case Types.TINYINT -> SQLDataType.TINYINT;
            case Types.DECIMAL, Types.NUMERIC -> SQLDataType.DECIMAL;
            case Types.DOUBLE, Types.FLOAT -> SQLDataType.DOUBLE;
            case Types.REAL -> SQLDataType.REAL;
            case Types.BOOLEAN, Types.BIT -> SQLDataType.BOOLEAN;
            // 用 LOCAL* 而不是 java.sql.Date/Time/Timestamp：后三者的 Java 类型表示「瞬时时间」，
            // 序列化给模型时会按 UTC 加时区偏移 —— 入库是 09:00，模型看到的是 01:00Z，是错的。
            case Types.DATE -> SQLDataType.LOCALDATE;
            case Types.TIME -> SQLDataType.LOCALTIME;
            case Types.TIMESTAMP -> SQLDataType.LOCALDATETIME;
            default -> SQLDataType.VARCHAR;
        };
    }

    // ------------------------------------------------------------------ 行对象

    private record TablePolicyRow(String tableName, String description, boolean visible, boolean queryable) {
    }

    private record ColumnPolicyRow(String columnName,
                                   String description,
                                   boolean accessible,
                                   boolean selectable,
                                   boolean filterable,
                                   boolean sortable,
                                   boolean groupable) {
    }

    private record TableMetadata(String actualTableName, Map<String, ColumnMetadata> columns) {
    }

    /** 一张表在数据库元数据里的真实位置，由数据库自己报出来，不靠猜。 */
    private record TableLocation(String catalog, String schema, String tableName) {
    }

    private record ColumnMetadata(String actualName, Field<?> field) {
    }
}
