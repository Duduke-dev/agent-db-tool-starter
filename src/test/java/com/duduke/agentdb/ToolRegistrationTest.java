package com.duduke.agentdb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证「工具是否真的能被模型看到」，以及「按需装载是否生效」。
 * <p>
 * 这个测试是因为一次真实疏漏才加的：{@code QueryTemplateTools} 写好了、单元测试也过了，
 * 但它<b>没有被注册进任何 ToolCallbackProvider</b> —— 使用方按文档挂上 provider，
 * 模型根本看不到那两个模板工具。
 * <p>
 * 之前那种「直接注入工具类调用」的测试<b>验证不到这件事</b>：它绕过了 provider 这一层。
 * 所以这里专门从容器里取 provider，检查工具名。
 */
@SpringBootTest(classes = ToolRegistrationTest.TestApplication.class,
        properties = "agent-db.tools.template=true")
class ToolRegistrationTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("两组工具各自有 provider，五个工具名一个不少 —— 模型看得到的才算数")
    void allToolsAreExposedThroughProviders() {
        List<String> exposedNames = context.getBeansOfType(ToolCallbackProvider.class).values().stream()
                .flatMap(provider -> Arrays.stream(provider.getToolCallbacks()))
                .map(ToolCallback::getToolDefinition)
                .map(definition -> definition.name())
                .sorted()
                .toList();

        assertThat(exposedNames).containsExactlyInAnyOrder(
                // 自由查询
                "list_tables",
                "describe_table",
                "query",
                // 查询模板
                "list_query_templates",
                "run_query_template");
    }

    @Test
    @DisplayName("两个 provider 都注册了，且按名字能分别取到 —— 使用方可以只挂其中一组")
    void providersAreSeparatedAndIndividuallyAddressable() {
        assertThat(context.containsBean("agentDatabaseToolCallbackProvider")).isTrue();
        assertThat(context.containsBean("agentQueryTemplateToolCallbackProvider")).isTrue();

        ToolCallbackProvider database = context.getBean("agentDatabaseToolCallbackProvider", ToolCallbackProvider.class);
        ToolCallbackProvider template = context.getBean("agentQueryTemplateToolCallbackProvider", ToolCallbackProvider.class);

        assertThat(Arrays.stream(database.getToolCallbacks())
                .map(callback -> callback.getToolDefinition().name()))
                .containsExactlyInAnyOrder("list_tables", "describe_table", "query");
        assertThat(Arrays.stream(template.getToolCallbacks())
                .map(callback -> callback.getToolDefinition().name()))
                .containsExactlyInAnyOrder("list_query_templates", "run_query_template");
    }
}
