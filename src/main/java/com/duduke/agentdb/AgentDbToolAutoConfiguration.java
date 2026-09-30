package com.duduke.agentdb;

import com.duduke.agentdb.config.AgentDbProperties;
import com.duduke.agentdb.policy.PolicyRepository;
import com.duduke.agentdb.policy.PolicyTableInitializer;
import com.duduke.agentdb.query.AgentQueryService;
import com.duduke.agentdb.query.QueryCompiler;
import com.duduke.agentdb.query.validation.AuthorizationValidator;
import com.duduke.agentdb.query.validation.ParameterValidator;
import com.duduke.agentdb.query.validation.SemanticValidator;
import com.duduke.agentdb.template.QueryTemplateInitializer;
import com.duduke.agentdb.template.QueryTemplateRepository;
import com.duduke.agentdb.template.QueryTemplateService;
import com.duduke.agentdb.tool.DatabaseAgentTools;
import com.duduke.agentdb.tool.QueryTemplateTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 自动配置入口。
 * <p>
 * <b>为什么需要它</b>：这个 jar 作为 starter 被别的项目引入时，那些 {@code @Component}
 * 不在使用方的组件扫描路径下（他们的 {@code @SpringBootApplication} 包名和 {@code com.duduke.agentdb}
 * 没有任何关系），靠 {@code @ComponentScan} 一个都扫不到 —— 必须在这里显式注册。
 * <p>
 * <b>按需装载</b>：两组工具各放在一个嵌套的 {@code @Configuration} 里，条件写在类上。
 * 这样做而不是给每个类加 {@code @ConditionalOnProperty}，是因为要控制的是一整组的生命周期
 * （包含建表、仓储、服务、工具门面），条件重复四遍迟早会漏掉一个。
 */
@AutoConfiguration
@EnableConfigurationProperties(AgentDbProperties.class)
public class AgentDbToolAutoConfiguration {

    /**
     * 自由查询：list_tables / describe_table / query。
     * <p>
     * 默认开启（{@code matchIfMissing = true}）—— 关掉它这个 starter 就只剩模板工具，
     * 而模板默认也是关的，那就等于引入了一个什么都不做的库。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "agent-db.tools.query", havingValue = "true", matchIfMissing = true)
    @Import({
            PolicyRepository.class,
            PolicyTableInitializer.class,
            AuthorizationValidator.class,
            ParameterValidator.class,
            SemanticValidator.class,
            QueryCompiler.class,
            AgentQueryService.class,
            DatabaseAgentTools.class
    })
    static class QueryToolConfiguration {

        /**
         * 条件用 {@code name} 而不是类型：两个 provider 都是 {@code ToolCallbackProvider}，
         * 若按类型判断，第一个注册之后第二个的条件就永远不满足，会被静默跳过。
         */
        @Bean
        @ConditionalOnMissingBean(name = "agentDatabaseToolCallbackProvider")
        public ToolCallbackProvider agentDatabaseToolCallbackProvider(DatabaseAgentTools tools) {
            return MethodToolCallbackProvider.builder().toolObjects(tools).build();
        }
    }

    /**
     * 查询模板：list_query_templates / run_query_template。
     * <p>
     * 默认关闭 —— 它是可选能力，且会多建一张 {@code agent_query_template} 表。
     * 需要时在配置里打开：{@code agent-db.tools.template: true}。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "agent-db.tools.template", havingValue = "true")
    @Import({
            QueryTemplateInitializer.class,
            QueryTemplateRepository.class,
            QueryTemplateService.class,
            QueryTemplateTools.class
    })
    static class TemplateToolConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "agentQueryTemplateToolCallbackProvider")
        public ToolCallbackProvider agentQueryTemplateToolCallbackProvider(QueryTemplateTools tools) {
            return MethodToolCallbackProvider.builder().toolObjects(tools).build();
        }
    }
}
