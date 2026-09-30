package com.duduke.agentdb;

import com.duduke.agentdb.error.AgentToolException;
import com.duduke.agentdb.error.ToolErrorCode;
import com.duduke.agentdb.query.dsl.OrderItem;
import com.duduke.agentdb.query.dsl.QueryRequest;
import com.duduke.agentdb.template.QueryTemplateService;
import com.duduke.agentdb.template.dto.QueryTemplateInfo;
import com.duduke.agentdb.tool.QueryTemplateTools;
import com.duduke.agentdb.tool.dto.QueryOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 查询模板功能的测试。
 * <p>
 * 重点不在「正常路径能跑通」，而在<b>几条安全边界不被绕过</b>：
 * 占位符不能出现在表名/列名上、参数不能是未声明的、模板里的列被取消授权后同样失败。
 * 这些才是这个功能存在的意义 —— 如果模板能绕过授权，它就不该被加进来。
 */
@SpringBootTest(classes = QueryTemplateTest.TestApplication.class)
class QueryTemplateTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }

    private static final String TEMPLATE_NAME = "test_count_by_category";

    /**
     * 注意 orderBy 用的是 groupBy 里的真实列，不是 select 里的聚合别名。
     * <p>
     * 按别名排序（{@code ORDER BY cnt DESC}）在 SQL 上合法，也很有用，
     * 但现有校验把 {@code orderBy.column} 当真实列名查授权表，别名会被判成「列不存在」。
     * 这是一个已知的能力缺口，不是测试将就。
     */
    private static final String VALID_TEMPLATE = """
            {
              "table": "product",
              "select": [{"column": "category"}, {"column": "id", "aggregate": "COUNT", "alias": "cnt"}],
              "where": [{"column": "price", "operator": "GE", "values": ["${minPrice}"]}],
              "groupBy": ["category"],
              "orderBy": [{"column": "category", "direction": "ASC"}]
            }""";

    private static final String VALID_PARAMETERS = """
            [{"name": "minPrice", "type": "NUMBER", "description": "最低单价", "required": true}]""";

    @Value("${spring.datasource.url}")
    private String jdbcUrl;

    /** 模板表要写数据，用管理员连接 —— 应用账号是只读的，写不进去。 */
    @Value("${agent-db.test.admin-username}")
    private String adminUser;

    @Value("${agent-db.test.admin-password}")
    private String adminPassword;

    @org.springframework.beans.factory.annotation.Autowired
    private QueryTemplateService templateService;

    @org.springframework.beans.factory.annotation.Autowired
    private QueryTemplateTools templateTools;

    // ------------------------------------------------------------------ 正常路径

    @Test
    @DisplayName("模板可执行：参数填入后得到正确结果，且查询结构完全由模板决定")
    void templateExecutesAndReturnsRows() throws SQLException {
        givenTemplate(VALID_TEMPLATE, VALID_PARAMETERS, true);
        try {
            List<QueryOutcome> outcomes = templateTools.runQueryTemplate(
                    TEMPLATE_NAME, Map.of("minPrice", 100));

            assertThat(outcomes).hasSize(1);
            // 先断言 errorMessage 为 null：失败时这条会把真实原因打出来，比只看 succeeded() 有用得多
            assertThat(outcomes.getFirst().errorMessage()).isNull();
            assertThat(outcomes.getFirst().succeeded()).isTrue();
            // 模板固定了 groupBy + orderBy，模型只提供了 minPrice
            assertThat(outcomes.getFirst().result().columns()).containsExactly("category", "cnt");
        } finally {
            removeTemplate();
        }
    }

    @Test
    @DisplayName("list_query_templates 返回参数说明，模型据此才知道要传什么")
    void listExposesParameterBriefs() throws SQLException {
        givenTemplate(VALID_TEMPLATE, VALID_PARAMETERS, true);
        try {
            List<QueryTemplateInfo> templates = templateTools.listQueryTemplates();

            assertThat(templates).extracting(QueryTemplateInfo::name).contains(TEMPLATE_NAME);
            QueryTemplateInfo info = templates.stream()
                    .filter(t -> t.name().equals(TEMPLATE_NAME)).findFirst().orElseThrow();
            assertThat(info.parameters()).singleElement().satisfies(p -> {
                assertThat(p.name()).isEqualTo("minPrice");
                assertThat(p.type()).isEqualTo("NUMBER");
                assertThat(p.required()).isTrue();
            });
        } finally {
            removeTemplate();
        }
    }

    // ------------------------------------------------------------------ 安全边界

    @Test
    @DisplayName("占位符出现在表名上：模板被拒绝 —— 参数绝不能左右查哪张表")
    void placeholderOnTableNameIsRejected() throws SQLException {
        givenTemplate("""
                {"table": "${whichTable}", "select": [{"column": "id"}]}""", """
                [{"name": "whichTable", "type": "STRING", "required": true}]""", true);
        try {
            assertThatThrownBy(() -> templateService.resolve(TEMPLATE_NAME, Map.of("whichTable", "product")))
                    .isInstanceOf(AgentToolException.class)
                    .hasMessageContaining("占位符位置不合法")
                    .extracting(ex -> ((AgentToolException) ex).code())
                    .isEqualTo(ToolErrorCode.TEMPLATE_INVALID);
        } finally {
            removeTemplate();
        }
    }

    @Test
    @DisplayName("占位符出现在列名上：同样被拒绝")
    void placeholderOnColumnNameIsRejected() throws SQLException {
        givenTemplate("""
                {"table": "product",
                 "select": [{"column": "id"}],
                 "where": [{"column": "${col}", "operator": "EQ", "values": ["1"]}]}""", """
                [{"name": "col", "type": "STRING", "required": true}]""", true);
        try {
            assertThatThrownBy(() -> templateService.resolve(TEMPLATE_NAME, Map.of("col", "id")))
                    .isInstanceOf(AgentToolException.class)
                    .hasMessageContaining("占位符位置不合法");
        } finally {
            removeTemplate();
        }
    }

    @Test
    @DisplayName("传了未声明的参数：拒绝而不是静默忽略 —— 否则模型会以为参数生效了")
    void undeclaredParameterIsRejected() throws SQLException {
        givenTemplate(VALID_TEMPLATE, VALID_PARAMETERS, true);
        try {
            assertThatThrownBy(() -> templateTools.runQueryTemplate(TEMPLATE_NAME,
                    Map.of("minPrice", 100, "table", "agent_table_policy")))
                    .isInstanceOf(AgentToolException.class)
                    .hasMessageContaining("未声明的参数")
                    .extracting(ex -> ((AgentToolException) ex).code())
                    .isEqualTo(ToolErrorCode.TEMPLATE_PARAMETER_INVALID);
        } finally {
            removeTemplate();
        }
    }

    @Test
    @DisplayName("缺必填参数：拒绝并说明缺了哪个")
    void missingRequiredParameterIsRejected() throws SQLException {
        givenTemplate(VALID_TEMPLATE, VALID_PARAMETERS, true);
        try {
            assertThatThrownBy(() -> templateTools.runQueryTemplate(TEMPLATE_NAME, Map.of()))
                    .isInstanceOf(AgentToolException.class)
                    .hasMessageContaining("缺少必填参数")
                    .hasMessageContaining("minPrice");
        } finally {
            removeTemplate();
        }
    }

    @Test
    @DisplayName("参数类型不符：拒绝")
    void wrongParameterTypeIsRejected() throws SQLException {
        givenTemplate(VALID_TEMPLATE, VALID_PARAMETERS, true);
        try {
            assertThatThrownBy(() -> templateTools.runQueryTemplate(TEMPLATE_NAME,
                    Map.of("minPrice", "不是数字")))
                    .isInstanceOf(AgentToolException.class)
                    .hasMessageContaining("期望 NUMBER");
        } finally {
            removeTemplate();
        }
    }

    @Test
    @DisplayName("visible = FALSE 的模板报 TEMPLATE_NOT_VISIBLE，且不泄露模板是否存在")
    void invisibleTemplateIsNotVisible() throws SQLException {
        givenTemplate(VALID_TEMPLATE, VALID_PARAMETERS, false);
        try {
            assertThatThrownBy(() -> templateTools.runQueryTemplate(TEMPLATE_NAME, Map.of("minPrice", 1)))
                    .isInstanceOf(AgentToolException.class)
                    .extracting(ex -> ((AgentToolException) ex).code())
                    .isEqualTo(ToolErrorCode.TEMPLATE_NOT_VISIBLE);
        } finally {
            removeTemplate();
        }
    }

    @Test
    @DisplayName("模板引用的列被取消授权时，执行同样失败 —— 模板不产生新权限")
    void templateStillObeysColumnPolicy() throws SQLException {
        givenTemplate(VALID_TEMPLATE, VALID_PARAMETERS, true);
        try {
            // price 取消可过滤能力
            execute("UPDATE agent_column_policy SET filterable = FALSE "
                    + "WHERE table_name = 'product' AND column_name = 'price'");

            List<QueryOutcome> outcomes = templateTools.runQueryTemplate(TEMPLATE_NAME, Map.of("minPrice", 1));

            assertThat(outcomes.getFirst().succeeded()).isFalse();
            // errorCode 是 String（对外是错误码名字），不是枚举
            assertThat(outcomes.getFirst().errorCode()).isEqualTo("COLUMN_NOT_FILTERABLE");
        } finally {
            execute("UPDATE agent_column_policy SET filterable = TRUE "
                    + "WHERE table_name = 'product' AND column_name = 'price'");
            removeTemplate();
        }
    }

    // ------------------------------------------------------------------ 与既有工具的关系

    @Test
    @DisplayName("模板产出的就是普通 QueryRequest：走 query 工具能拿到一模一样的 SQL 行为")
    void templateProducesPlainQueryRequest() throws SQLException {
        givenTemplate(VALID_TEMPLATE, VALID_PARAMETERS, true);
        try {
            QueryRequest viaTemplate = templateService.resolve(TEMPLATE_NAME, Map.of("minPrice", 100));
            QueryRequest handWritten = new QueryRequest("product",
                    List.of(new com.duduke.agentdb.query.dsl.SelectItem("category", null, null),
                            new com.duduke.agentdb.query.dsl.SelectItem("id",
                                    com.duduke.agentdb.query.dsl.Aggregate.COUNT, "cnt")),
                    List.of(new com.duduke.agentdb.query.dsl.Condition("price",
                            com.duduke.agentdb.query.dsl.Operator.GE, List.of("100"))),
                    List.of("category"),
                    List.of(new OrderItem("category", com.duduke.agentdb.query.dsl.SortDirection.ASC)),
                    null);

            assertThat(viaTemplate).isEqualTo(handWritten);
        } finally {
            removeTemplate();
        }
    }

    // ------------------------------------------------------------------ 辅助

    private void givenTemplate(String templateJson, String parametersJson, boolean visible) throws SQLException {
        removeTemplate();
        execute("INSERT INTO agent_query_template "
                + "(template_name, description, visible, template_json, parameters_json) VALUES ('"
                + TEMPLATE_NAME + "', '测试用模板', " + visible + ", "
                + literal(templateJson) + ", " + literal(parametersJson) + ")");
    }

    private void removeTemplate() throws SQLException {
        execute("DELETE FROM agent_query_template WHERE template_name = '" + TEMPLATE_NAME + "'");
    }

    /** 把 Java 字符串安全地写成 SQL 字面量（单引号翻倍），够这个测试用。 */
    private String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, adminUser, adminPassword);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }
}
