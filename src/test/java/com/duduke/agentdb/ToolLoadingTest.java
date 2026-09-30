package com.duduke.agentdb;

import com.duduke.agentdb.tool.QueryTemplateTools;
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
 * 验证按需装载：关掉的那组工具，对应的 bean 根本不会注册。
 * <p>
 * 关键点不只是"工具少了"，而是<b>整组生命周期都停了</b> ——
 * 包括建表、仓储、服务。如果只给工具门面加条件，{@code QueryTemplateInitializer}
 * 仍会去建那张表，等于"关掉了却还留着痕迹"。
 * <p>
 * 这里断言到 bean 层面，而不只是工具名，就是为了把这一点钉住。
 */
@SpringBootTest(classes = ToolLoadingTest.TestApplication.class,
        properties = "agent-db.tools.template=false")
class ToolLoadingTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("关掉 template 后：只剩自由查询的三个工具")
    void onlyQueryToolsAreExposed() {
        List<String> exposedNames = context.getBeansOfType(ToolCallbackProvider.class).values().stream()
                .flatMap(provider -> Arrays.stream(provider.getToolCallbacks()))
                .map(ToolCallback::getToolDefinition)
                .map(definition -> definition.name())
                .sorted()
                .toList();

        assertThat(exposedNames).containsExactlyInAnyOrder("list_tables", "describe_table", "query");
    }

    @Test
    @DisplayName("关掉 template 后：整组 bean 都不注册，连建表也不会跑")
    void templateBeansAreNotRegisteredAtAll() {
        assertThat(context.getBeanNamesForType(QueryTemplateTools.class)).isEmpty();
        assertThat(context.containsBean("agentQueryTemplateToolCallbackProvider")).isFalse();

        // 建表那一步也要一起停掉 —— 否则就成了「关掉了却还留着痕迹」。
        //
        // 注意这里检查的是 bean 名（首字母小写的 queryTemplateInitializer），
        // 不是类名。之前写成类名，大小写不匹配导致断言恒为真、什么都没验证到。
        assertThat(context.containsBean("queryTemplateInitializer")).isFalse();
        assertThat(context.getBeanNamesForType(QueryTemplateTools.class))
                .noneMatch(name -> name.toLowerCase().contains("initializer"));
    }
}
