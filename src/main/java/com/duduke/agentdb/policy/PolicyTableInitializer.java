package com.duduke.agentdb.policy;

import com.duduke.agentdb.config.AgentDbProperties;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static com.duduke.agentdb.policy.PolicySchema.COLUMN_POLICY;
import static com.duduke.agentdb.policy.PolicySchema.CP_ACCESSIBLE;
import static com.duduke.agentdb.policy.PolicySchema.CP_COLUMN;
import static com.duduke.agentdb.policy.PolicySchema.CP_DESCRIPTION;
import static com.duduke.agentdb.policy.PolicySchema.CP_FILTERABLE;
import static com.duduke.agentdb.policy.PolicySchema.CP_GROUPABLE;
import static com.duduke.agentdb.policy.PolicySchema.CP_SELECTABLE;
import static com.duduke.agentdb.policy.PolicySchema.CP_SORTABLE;
import static com.duduke.agentdb.policy.PolicySchema.CP_TABLE;
import static com.duduke.agentdb.policy.PolicySchema.RESERVED;
import static com.duduke.agentdb.policy.PolicySchema.TABLE_POLICY;
import static com.duduke.agentdb.policy.PolicySchema.TP_DESCRIPTION;
import static com.duduke.agentdb.policy.PolicySchema.TP_NAME;
import static com.duduke.agentdb.policy.PolicySchema.TP_QUERYABLE;
import static com.duduke.agentdb.policy.PolicySchema.TP_VISIBLE;

/**
 * 启动时把两张授权表建好，并把库里现有的表和字段<b>全部登记为清单</b>。
 * <p>
 * 用的是一条<b>独立的管理员连接</b>，而不是应用的数据源 —— 应用连的是只读账号，
 * 本来就没有 DDL 权限，这是刻意的安全设计。建表是运维（DBA）的动作，
 * 只是把它自动化在启动那一刻做掉。
 * <p>
 * <b>登记 ≠ 授权</b>：扫描进来的一律是「关闭」状态
 * （表 {@code visible = FALSE}、字段 {@code accessible = FALSE}），
 * 要放开哪个再手工改标记。否则一启动就等于把整库开放给 agent 了。
 * <p>
 * 写入一律走 {@code ON CONFLICT DO NOTHING}，所以重复启动不会覆盖已经调好的授权配置，
 * 也只会补录新增的表和字段。
 */
@Component
public class PolicyTableInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PolicyTableInitializer.class);

    private final AgentDbProperties properties;
    private final DataSourceProperties dataSourceProperties;

    public PolicyTableInitializer(AgentDbProperties properties, DataSourceProperties dataSourceProperties) {
        this.properties = properties;
        this.dataSourceProperties = dataSourceProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        AgentDbProperties.Bootstrap bootstrap = properties.bootstrap();
        if (!bootstrap.enabled()) {
            log.info("授权表自动初始化已关闭（agent-db.bootstrap.enabled=false）");
            return;
        }

        // 地址从主数据源推导，而不是单独配一份：两者连的是同一个库，各配一遍容易改漏
        String url = dataSourceProperties.getUrl();
        if (url == null || url.isBlank()) {
            log.error("spring.datasource.url 未配置，无法执行授权表初始化");
            return;
        }

        try (Connection connection = DriverManager.getConnection(url, bootstrap.username(), bootstrap.password())) {
            // 管理员连接自己建一个 DSLContext：应用那个绑的是只读数据源，用它建不了表。
            // 不指定方言，让 jOOQ 从连接自己探测。
            DSLContext admin = DSL.using(connection);

            createTables(admin);
            ScanResult result = registerCatalog(admin, connection);
            if (result.isEmpty()) {
                log.info("授权表已就绪；清单无变化（已登记的表和字段均未改动，授权配置未被覆盖）");
            } else {
                log.info("授权表已就绪；本次新登记 {} 张表、{} 个字段 —— 注意全部是关闭状态，"
                        + "要放开哪个再改 visible / accessible", result.tables(), result.columns());
            }
        } catch (SQLException ex) {
            // 初始化失败不阻断启动：应用自己的只读连接可能仍然可用（表已由 DBA 建好），
            // 把问题说清楚交给运维判断，比直接让应用起不来更合适。
            log.error("授权表初始化失败（应用可能仍可用，前提是表已由 DBA 建好）：{}", ex.getMessage());
        }
    }

    /**
     * 建两张授权表。
     * <p>
     * 走 jOOQ 而不是拼 SQL 字符串：和读授权那边保持同一套表定义（都在 {@link PolicySchema}），
     * 否则容易变成"SQL 里写的列名"和"代码里引用的列名"各说各话。
     */
    private void createTables(DSLContext admin) {
        admin.createTableIfNotExists(TABLE_POLICY)
                .column(TP_NAME, SQLDataType.VARCHAR(128).nullable(false))
                .column(TP_DESCRIPTION, SQLDataType.VARCHAR(500).nullable(false).defaultValue(DSL.inline("")))
                .column(TP_VISIBLE, SQLDataType.BOOLEAN.nullable(false).defaultValue(DSL.inline(true)))
                .column(TP_QUERYABLE, SQLDataType.BOOLEAN.nullable(false).defaultValue(DSL.inline(true)))
                .constraints(DSL.primaryKey(TP_NAME))
                .execute();

        admin.createTableIfNotExists(COLUMN_POLICY)
                .column(CP_TABLE, SQLDataType.VARCHAR(128).nullable(false))
                .column(CP_COLUMN, SQLDataType.VARCHAR(128).nullable(false))
                .column(CP_DESCRIPTION, SQLDataType.VARCHAR(500).nullable(false).defaultValue(DSL.inline("")))
                .column(CP_ACCESSIBLE, SQLDataType.BOOLEAN.nullable(false).defaultValue(DSL.inline(false)))
                .column(CP_SELECTABLE, SQLDataType.BOOLEAN.nullable(false).defaultValue(DSL.inline(true)))
                .column(CP_FILTERABLE, SQLDataType.BOOLEAN.nullable(false).defaultValue(DSL.inline(true)))
                .column(CP_SORTABLE, SQLDataType.BOOLEAN.nullable(false).defaultValue(DSL.inline(true)))
                .column(CP_GROUPABLE, SQLDataType.BOOLEAN.nullable(false).defaultValue(DSL.inline(true)))
                .constraints(DSL.primaryKey(CP_TABLE, CP_COLUMN))
                .execute();
    }

    /**
     * 一次遍历同时登记表和列。
     * <p>
     * 表和列分两趟扫会让 {@code meta.getTables()} 跑两遍，纯属重复劳动；
     * 拿到表名后直接顺着读它的列，一趟就够。
     * <p>
     * 元数据仍走 JDBC 的 {@link DatabaseMetaData}：它支持按单表查列，
     * 而 jOOQ 的 {@code dsl.meta()} 是全量扫描，库大的时候没必要。
     */
    private ScanResult registerCatalog(DSLContext admin, Connection connection) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        String schema = currentSchema(connection);

        List<String> tables = new ArrayList<>();
        try (ResultSet rs = meta.getTables(null, schema, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (!isReserved(name)) {
                    tables.add(name);
                }
            }
        }

        int insertedTables = 0;
        int insertedColumns = 0;
        for (String table : tables) {
            // onDuplicateKeyIgnore 是 jOOQ 的跨方言写法：PG 上生成 ON CONFLICT DO NOTHING，
            // MySQL 上生成 INSERT IGNORE，不支持的方言再用 WHERE NOT EXISTS 模拟。
            // 直接写 onConflict().doNothing() 会绑死 PG / SQLite。
            insertedTables += admin
                    .insertInto(TABLE_POLICY, TP_NAME, TP_DESCRIPTION, TP_VISIBLE, TP_QUERYABLE)
                    .values(table, "", false, true)
                    .onDuplicateKeyIgnore()
                    .execute();

            try (ResultSet columns = meta.getColumns(null, schema, table, null)) {
                while (columns.next()) {
                    insertedColumns += admin
                            .insertInto(COLUMN_POLICY, CP_TABLE, CP_COLUMN, CP_DESCRIPTION)
                            .values(table, columns.getString("COLUMN_NAME"), "")
                            .onDuplicateKeyIgnore()
                            .execute();
                }
            }
        }
        return new ScanResult(insertedTables, insertedColumns);
    }

    private boolean isReserved(String tableName) {
        return RESERVED.stream().anyMatch(reserved -> reserved.equalsIgnoreCase(tableName));
    }

    /**
     * 当前 schema。
     * <p>
     * 不能直接用 {@code connection.getSchema()}：部分驱动（如 MySQL 在未指定库时）返回 null，
     * 而 null 在 {@code getTables} 里表示「不限 schema」，会把系统库一并扫进来。
     * 这种情况下的兜底是退回用连接自身的 catalog —— 对 MySQL 那正是当前库名。
     */
    private String currentSchema(Connection connection) throws SQLException {
        String schema = connection.getSchema();
        if (schema != null && !schema.isBlank()) {
            return schema;
        }
        return connection.getCatalog();
    }

    private record ScanResult(int tables, int columns) {

        boolean isEmpty() {
            return tables == 0 && columns == 0;
        }
    }
}
