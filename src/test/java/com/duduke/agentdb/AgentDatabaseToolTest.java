package com.duduke.agentdb;

import com.duduke.agentdb.error.AgentToolException;
import com.duduke.agentdb.error.ToolErrorCode;
import com.duduke.agentdb.query.AgentQueryService;
import com.duduke.agentdb.query.dsl.Aggregate;
import com.duduke.agentdb.query.dsl.Condition;
import com.duduke.agentdb.query.dsl.Operator;
import com.duduke.agentdb.query.dsl.OrderItem;
import com.duduke.agentdb.query.dsl.QueryRequest;
import com.duduke.agentdb.query.QueryResult;
import com.duduke.agentdb.query.dsl.SelectItem;
import com.duduke.agentdb.query.dsl.SortDirection;
import com.duduke.agentdb.tool.dto.ColumnDescriptor;
import com.duduke.agentdb.tool.DatabaseAgentTools;
import com.duduke.agentdb.tool.dto.QueryOutcome;
import com.duduke.agentdb.tool.dto.TableDescriptor;
import com.duduke.agentdb.tool.dto.TableSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * starter 模块里没有 {@code @SpringBootApplication}（那属于 demo 模块），
 * 所以这里自带一个最小的引导配置。
 * <p>
 * 关键是它只开 {@code @EnableAutoConfiguration}、<b>不显式列出任何配置类</b> ——
 * 这样 {@link AgentDbToolAutoConfiguration} 必须靠
 * {@code META-INF/spring/...AutoConfiguration.imports} 那条真实路径被加载。
 * 如果哪天有人漏了那个文件、或把关键 bean 挪到了 demo 一侧，这个测试会立刻失败。
 */
@SpringBootTest(classes = AgentDatabaseToolTest.TestApplication.class)
class AgentDatabaseToolTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }

    @Autowired
    private DatabaseAgentTools tools;

    @Autowired
    private AgentQueryService queryService;

    @Value("${spring.datasource.url}")
    private String jdbcUrl;

    /** 改授权表要用管理员连接 —— 应用自己的账号是账号级只读，这正是被验证的行为。 */
    @Value("${agent-db.test.admin-username}")
    private String adminUser;

    @Value("${agent-db.test.admin-password}")
    private String adminPassword;

    /** 应用实际使用的只读账号，用于验证「它确实写不进去」。 */
    @Value("${spring.datasource.username}")
    private String appUser;

    @Value("${spring.datasource.password}")
    private String appPassword;

    private QueryRequest selectOne(String column) {
        return new QueryRequest("product", List.of(SelectItem.of(column)), List.of(), List.of(), List.of(), null);
    }

    // ------------------------------------------------------------------ 表可见性

    @Test
    @DisplayName("list_tables 只返回权限编码里授权的表：未登记的 supplier 完全不可见")
    void listTablesOnlyReturnsAuthorizedTables() {
        assertThat(tools.listTables())
                .extracting(TableSummary::name)
                .containsExactly("product");
    }

    @Test
    @DisplayName("describe_table 如实下发四个能力位，模型据此知道哪些列不能筛/排/分组")
    void describeTableExposesCapabilities() {
        TableDescriptor descriptor = tools.describeTable(List.of("product")).getFirst();

        assertThat(descriptor.columns())
                .extracting(ColumnDescriptor::name)
                .contains("id", "sku", "name", "category", "price", "stock_quantity", "created_at")
                // cost_price 在库里真实存在，但权限编码里没登记，所以不下发
                .doesNotContain("cost_price");

        assertThat(descriptor.columns())
                .filteredOn(column -> column.name().equals("id"))
                .singleElement()
                .satisfies(column -> assertThat(column.filterable()).isFalse());

        assertThat(descriptor.columns())
                .filteredOn(column -> column.name().equals("name"))
                .singleElement()
                .satisfies(column -> assertThat(column.sortable()).isFalse());
    }

    @Test
    @DisplayName("可以一次查看多张表：不可访问的表被跳过，重复的表名被去掉，其余照常返回")
    void describeMultipleTablesSkipsInaccessible() {
        List<TableDescriptor> result = tools.describeTable(
                List.of("product", "supplier", "no_such_table", "product"));

        assertThat(result).extracting(TableDescriptor::name).containsExactly("product");
    }

    @Test
    @DisplayName("一张表都拿不到时给出明确错误，而不是返回空列表让模型自己猜")
    void describeAllInaccessibleReportsError() {
        assertThatThrownBy(() -> tools.describeTable(List.of("supplier", "no_such_table")))
                .isInstanceOfSatisfying(AgentToolException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ToolErrorCode.TABLE_NOT_VISIBLE);
                    // 错误里带着可用表的清单，模型拿到能直接改
                    assertThat(ex.getMessage()).contains("product");
                });
    }

    @Test
    @DisplayName("一次请求的表数量超过上限被拒绝，避免批量把上下文撑爆")
    void describeTooManyTablesIsRejected() {
        List<String> tooMany = IntStream.rangeClosed(1, 21).mapToObj(i -> "table_" + i).toList();

        assertThatThrownBy(() -> tools.describeTable(tooMany))
                .isInstanceOfSatisfying(AgentToolException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ToolErrorCode.TOO_MANY_TABLES));
    }

    // ------------------------------------------------------------------ 第一层：授权

    @Test
    @DisplayName("未登记的表被拒绝，且错误信息不透露该表是否存在")
    void unregisteredTableIsRejected() {
        assertThatThrownBy(() -> queryService.query(QueryRequest.of("supplier")))
                .isInstanceOfSatisfying(AgentToolException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ToolErrorCode.TABLE_NOT_VISIBLE);
                    assertThat(ex.getMessage()).contains("product");
                });
    }

    @Test
    @DisplayName("登记表里未登记的列被拒绝：cost_price 在数据库中真实存在，但不在授权集合里")
    void unregisteredColumnIsRejected() {
        assertThatThrownBy(() -> queryService.query(selectOne("cost_price")))
                .isInstanceOfSatisfying(AgentToolException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ToolErrorCode.COLUMN_NOT_VISIBLE));
    }

    @Test
    @DisplayName("能力位生效：不可筛选的列不能做过滤条件")
    void nonFilterableColumnIsRejected() {
        QueryRequest request = new QueryRequest("product",
                List.of(SelectItem.of("sku")),
                List.of(Condition.of("id", Operator.EQ, "1")),
                List.of(), List.of(), null);

        assertThatThrownBy(() -> queryService.query(request))
                .isInstanceOfSatisfying(AgentToolException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ToolErrorCode.COLUMN_NOT_FILTERABLE));
    }

    @Test
    @DisplayName("能力位生效：不可排序的列不能排序")
    void nonSortableColumnIsRejected() {
        QueryRequest request = new QueryRequest("product",
                List.of(SelectItem.of("sku")), List.of(), List.of(),
                List.of(new OrderItem("name", SortDirection.ASC)), null);

        assertThatThrownBy(() -> queryService.query(request))
                .isInstanceOfSatisfying(AgentToolException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ToolErrorCode.COLUMN_NOT_SORTABLE));
    }

    @Test
    @DisplayName("能力位生效：不可分组的列不能 groupBy")
    void nonGroupableColumnIsRejected() {
        QueryRequest request = new QueryRequest("product",
                List.of(new SelectItem("created_at", Aggregate.COUNT, "cnt")),
                List.of(), List.of("created_at"), List.of(), null);

        assertThatThrownBy(() -> queryService.query(request))
                .isInstanceOfSatisfying(AgentToolException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ToolErrorCode.COLUMN_NOT_GROUPABLE));
    }

    // ------------------------------------------------------------------ 正常查询

    @Test
    @DisplayName("常规查询返回全部行")
    void queryReturnsAllRows() {
        QueryResult result = queryService.query(QueryRequest.of("product"));
        assertThat(result.rowCount()).isEqualTo(8);
        assertThat(result.truncated()).isFalse();
    }

    @Test
    @DisplayName("select 留空时返回策略里全部 selectable 的列，而不是 SELECT *")
    void emptySelectReturnsAllSelectableColumns() {
        QueryResult result = queryService.query(QueryRequest.of("product"));

        assertThat(result.columns()).containsExactlyInAnyOrder(
                "id", "sku", "name", "category", "price", "stock_quantity", "created_at");
        assertThat(result.columns()).doesNotContain("cost_price");
    }

    @Test
    @DisplayName("排序与筛选组合可用")
    void filterAndSortWork() {
        QueryRequest request = new QueryRequest("product",
                List.of(SelectItem.of("sku"), SelectItem.of("price")),
                List.of(Condition.of("category", Operator.EQ, "家具")),
                List.of(), List.of(new OrderItem("price", SortDirection.DESC)), null);

        QueryResult result = queryService.query(request);

        assertThat(result.rowCount()).isEqualTo(2);
        assertThat(result.rows().get(0).get("price").toString()).startsWith("2599");
    }

    // ------------------------------------------------------------------ 第二层：参数

    @Test
    @DisplayName("limit 超过策略上限时拒绝而不是静默截断")
    void limitBeyondPolicyIsRejected() {
        QueryRequest request = new QueryRequest("product",
                List.of(SelectItem.of("id")), List.of(), List.of(), List.of(), 9999);

        assertThatThrownBy(() -> queryService.query(request))
                .isInstanceOfSatisfying(AgentToolException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ToolErrorCode.LIMIT_EXCEEDED));
    }

    @Test
    @DisplayName("在文本列上使用 SUM 被拒绝")
    void sumOnNonNumericColumnIsRejected() {
        QueryRequest request = new QueryRequest("product",
                List.of(new SelectItem("name", Aggregate.SUM, "total")),
                List.of(), List.of(), List.of(), null);

        assertThatThrownBy(() -> queryService.query(request))
                .isInstanceOfSatisfying(AgentToolException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ToolErrorCode.INVALID_AGGREGATE));
    }

    @Test
    @DisplayName("IN 的元素个数超过上限被拒绝")
    void oversizedInClauseIsRejected() {
        List<String> tooMany = IntStream.rangeClosed(1, 200).mapToObj(i -> "SKU-" + i).toList();
        QueryRequest request = new QueryRequest("product",
                List.of(SelectItem.of("sku")),
                List.of(new Condition("sku", Operator.IN, tooMany)),
                List.of(), List.of(), null);

        assertThatThrownBy(() -> queryService.query(request))
                .isInstanceOfSatisfying(AgentToolException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ToolErrorCode.IN_CLAUSE_TOO_LARGE));
    }

    @Test
    @DisplayName("值按列类型转换：往数值列传非数字时明确报类型不匹配")
    void valueIsConvertedToColumnType() {
        QueryRequest request = new QueryRequest("product",
                List.of(SelectItem.of("sku")),
                List.of(Condition.of("price", Operator.GT, "不是数字")),
                List.of(), List.of(), null);

        assertThatThrownBy(() -> queryService.query(request))
                .isInstanceOfSatisfying(AgentToolException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ToolErrorCode.INVALID_VALUE_TYPE);
                    assertThat(ex.getMessage()).contains("price");
                });
    }

    // ------------------------------------------------------------------ 第三层：语义

    @Test
    @DisplayName("聚合与裸列混用却不写 groupBy 时被拦住，并提示该把列加进 groupBy")
    void aggregateWithoutGroupByIsRejected() {
        QueryRequest request = new QueryRequest("product",
                List.of(SelectItem.of("category"), new SelectItem("id", Aggregate.COUNT, "cnt")),
                List.of(), List.of(), List.of(), null);

        assertThatThrownBy(() -> queryService.query(request))
                .isInstanceOfSatisfying(AgentToolException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ToolErrorCode.NON_GROUPED_COLUMN_IN_SELECT);
                    assertThat(ex.getMessage()).contains("groupBy");
                });
    }

    @Test
    @DisplayName("补上 groupBy 后同一查询可以正常执行")
    void aggregateWithGroupBySucceeds() {
        QueryRequest request = new QueryRequest("product",
                List.of(SelectItem.of("category"), new SelectItem("id", Aggregate.COUNT, "cnt")),
                List.of(), List.of("category"), List.of(), null);

        QueryResult result = queryService.query(request);

        assertThat(result.columns()).containsExactly("category", "cnt");
        assertThat(result.rowCount()).isEqualTo(5);
    }

    // ------------------------------------------------------------------ 其他保护

    @Test
    @DisplayName("LIKE 通配符被转义：查询 '%' 不会退化成匹配全部行")
    void likeWildcardsAreEscaped() {
        QueryRequest request = new QueryRequest("product",
                List.of(SelectItem.of("sku")),
                List.of(Condition.of("name", Operator.CONTAINS, "%")),
                List.of(), List.of(), null);

        // 库里没有任何名称含字面量 '%'，若通配符没被转义这里会返回全部 8 行
        assertThat(queryService.query(request).rowCount()).isZero();
    }

    @Test
    @DisplayName("结果超出 limit 时标记 truncated，并只返回 limit 行")
    void resultIsTruncatedWhenExceedingLimit() {
        QueryRequest request = new QueryRequest("product",
                List.of(SelectItem.of("sku")), List.of(), List.of(), List.of(), 3);

        QueryResult result = queryService.query(request);

        assertThat(result.rowCount()).isEqualTo(3);
        assertThat(result.truncated()).isTrue();
        assertThat(result.hint()).isNotBlank();
    }

    // ------------------------------------------------------------------ 数据库层防线（只在真实 PG 上成立）

    @Test
    @DisplayName("只读账号写不进去：数据库直接拒绝，这是应用层有 bug 也绕不过去的防线")
    void applicationAccountCannotWrite() {
        assertThatThrownBy(() -> {
            try (Connection connection = DriverManager.getConnection(jdbcUrl, appUser, appPassword);
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM agent_table_policy WHERE table_name = 'not_exists'");
            }
        }).isInstanceOf(SQLException.class).hasMessageContaining("read-only");
    }

    @Test
    @DisplayName("statement_timeout 生效：超长查询会被数据库掐断，不会拖死连接池")
    void statementTimeoutIsEnforced() {
        long started = System.nanoTime();

        assertThatThrownBy(() -> {
            try (Connection connection = DriverManager.getConnection(jdbcUrl, appUser, appPassword);
                 Statement statement = connection.createStatement()) {
                statement.execute("SELECT pg_sleep(10)");
            }
        }).isInstanceOf(SQLException.class);

        // 上限配的是 5s，所以 10 秒的睡眠必须在明显短于 10 秒的位置被打断
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        assertThat(elapsedMillis).isLessThan(9_000);
    }

    // ------------------------------------------------------------------ 扩展性：加表不用改代码

    @Test
    @DisplayName("放行一张新表只需要改授权表的标记，Java 代码一行都不用改")
    void addingNewTableNeedsNoCodeChange() throws SQLException {
        try {
            // supplier 在库里，启动时已被自动登记成「关闭」状态 —— 现在把表和要用的字段打开
            execute("UPDATE agent_table_policy "
                    + "SET description = '供应商表，记录供应商编码、名称与所在区域', visible = TRUE, queryable = TRUE "
                    + "WHERE table_name = 'supplier'");
            for (String column : List.of("id", "supplier_code", "name", "region")) {
                execute("UPDATE agent_column_policy SET accessible = TRUE "
                        + "WHERE table_name = 'supplier' AND column_name = '" + column + "'");
            }

            assertThat(tools.listTables()).extracting(TableSummary::name)
                    .containsExactly("product", "supplier");

            assertThat(tools.describeTable(List.of("supplier")).getFirst().columns())
                    .extracting(ColumnDescriptor::name)
                    .containsExactlyInAnyOrder("id", "supplier_code", "name", "region");

            List<QueryOutcome> outcomes = tools.query(List.of(QueryRequest.of("supplier")));
            assertThat(outcomes.getFirst().succeeded()).isTrue();
            assertThat(outcomes.getFirst().result().rowCount()).isEqualTo(3);
        } finally {
            // 还原成「已登记但关闭」，与启动时扫描出来的状态一致
            execute("UPDATE agent_table_policy SET description = '', visible = FALSE WHERE table_name = 'supplier'");
            execute("UPDATE agent_column_policy SET accessible = FALSE WHERE table_name = 'supplier'");
        }
    }

    // ------------------------------------------------------------------ 批量查询

    @Test
    @DisplayName("批量查询：一次提交多条，各自独立返回结果或错误，互不影响")
    void batchQueryReturnsPerRequestOutcome() {
        List<QueryOutcome> outcomes = tools.query(List.of(
                new QueryRequest("product", List.of(SelectItem.of("sku")),
                        List.of(), List.of(), List.of(), 2),
                QueryRequest.of("supplier"),
                new QueryRequest("product", List.of(SelectItem.of("cost_price")),
                        List.of(), List.of(), List.of(), null)));

        assertThat(outcomes).hasSize(3);

        assertThat(outcomes.get(0).succeeded()).isTrue();
        assertThat(outcomes.get(0).result().rowCount()).isEqualTo(2);

        // 未授权的表：只影响这一条，前一条的结果照常返回
        assertThat(outcomes.get(1).succeeded()).isFalse();
        assertThat(outcomes.get(1).errorCode()).isEqualTo(ToolErrorCode.TABLE_NOT_VISIBLE.name());

        // 未授权的列：同样只影响这一条，而且错误码和上面的不同，模型能分清是哪种问题
        assertThat(outcomes.get(2).succeeded()).isFalse();
        assertThat(outcomes.get(2).errorCode()).isEqualTo(ToolErrorCode.COLUMN_NOT_VISIBLE.name());
    }

    @Test
    @DisplayName("批量查询条数超过上限被拒绝")
    void tooManyQueriesIsRejected() {
        List<QueryRequest> tooMany = IntStream.rangeClosed(1, 11)
                .mapToObj(i -> QueryRequest.of("product"))
                .toList();

        assertThatThrownBy(() -> tools.query(tooMany))
                .isInstanceOfSatisfying(AgentToolException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ToolErrorCode.TOO_MANY_QUERIES));
    }

    // ------------------------------------------------------------------ 输出稳定性

    @Test
    @DisplayName("输出列顺序可预测：按列名排序，不因 Map 的哈希顺序而随机")
    void columnOrderIsPredictable() {
        assertThat(queryService.query(QueryRequest.of("product")).columns())
                .containsExactly("category", "created_at", "id", "name", "price", "sku", "stock_quantity");
    }

    @Test
    @DisplayName("时间列输出不带时区偏移：入库是 09:00，模型看到的也得是 09:00")
    void timestampIsNotShiftedToUtc() {
        QueryRequest request = new QueryRequest("product",
                List.of(SelectItem.of("created_at")),
                List.of(Condition.of("sku", Operator.EQ, "SKU-1001")),
                List.of(), List.of(), null);

        assertThat(queryService.query(request).rows().get(0).get("created_at").toString())
                .startsWith("2026-01-05T09:00");
    }

    // ------------------------------------------------------------------ 实时性

    @Test
    @DisplayName("改授权表立即生效，不需要重启：全程没有任何缓存")
    void policyChangeTakesEffectImmediately() throws SQLException {
        try {
            // 把 product 的 visible 关掉，效果等于它从没被登记过
            execute("UPDATE agent_table_policy SET visible = FALSE WHERE table_name = 'product'");

            assertThat(tools.listTables()).isEmpty();
            assertThatThrownBy(() -> queryService.query(QueryRequest.of("product")))
                    .isInstanceOfSatisfying(AgentToolException.class,
                            ex -> assertThat(ex.code()).isEqualTo(ToolErrorCode.TABLE_NOT_VISIBLE));
        } finally {
            execute("UPDATE agent_table_policy SET visible = TRUE WHERE table_name = 'product'");
        }

        // 恢复后立刻又能查到，中间没有重启、没有清缓存
        assertThat(tools.listTables())
                .extracting(TableSummary::name)
                .containsExactly("product");
    }

    @Test
    @DisplayName("字段授权表里登记了数据库不存在的列时，报的是配置问题而不是「没数据」")
    void invalidColumnInPolicyIsReported() throws SQLException {
        try {
            execute("INSERT INTO agent_column_policy (table_name, column_name, description, accessible) "
                    + "VALUES ('product', 'not_a_real_column', '这列不存在', TRUE)");

            assertThatThrownBy(() -> tools.describeTable(List.of("product")))
                    .isInstanceOfSatisfying(AgentToolException.class, ex -> {
                        assertThat(ex.code()).isEqualTo(ToolErrorCode.POLICY_INVALID);
                        assertThat(ex.getMessage()).contains("not_a_real_column");
                    });
        } finally {
            execute("DELETE FROM agent_column_policy "
                    + "WHERE table_name = 'product' AND column_name = 'not_a_real_column'");
        }
    }

    // ------------------------------------------------------------------ 改授权表的辅助

    /**
     * 另开一条管理员连接来模拟「有人在改授权表」。
     * <p>
     * 不能用应用配置里的账号：那个账号在 PostgreSQL 上是账号级只读
     * （{@code ALTER ROLE ... default_transaction_read_only = on}），写操作会被直接拒绝 ——
     * 这本身就是要验证的防线之一。
     */
    private void execute(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, adminUser, adminPassword);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }
}
