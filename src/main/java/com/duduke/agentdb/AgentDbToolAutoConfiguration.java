package com.duduke.agentdb;

import com.duduke.agentdb.config.AgentDbProperties;
import com.duduke.agentdb.policy.PolicyRepository;
import com.duduke.agentdb.policy.PolicyTableInitializer;
import com.duduke.agentdb.query.AgentQueryService;
import com.duduke.agentdb.query.QueryCompiler;
import com.duduke.agentdb.query.validation.AuthorizationValidator;
import com.duduke.agentdb.query.validation.ParameterValidator;
import com.duduke.agentdb.query.validation.SemanticValidator;
import com.duduke.agentdb.tool.DatabaseAgentTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * 自动配置入口。
 * <p>
 * <b>为什么需要它</b>：这个 jar 作为 starter 被别的项目引入时，上面这些 {@code @Component}
 * 不在使用方的组件扫描路径下（他们的 {@code @SpringBootApplication} 包名和 {@code com.duduke.agentdb}
 * 没有任何关系），靠 {@code @ComponentScan} 一个都扫不到 —— 必须在这里显式注册。
 * <p>
 * 本仓库自带的 demo（{@link AgentDbToolApplication}）走的是组件扫描，两种方式注册的是同一批 bean。
 */
@AutoConfiguration
@EnableConfigurationProperties(AgentDbProperties.class)
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
public class AgentDbToolAutoConfiguration {

    /**
     * 把 {@code @Tool} 方法转成 Spring AI 的 ToolCallback 集合。
     * <p>
     * 标注 {@code @ConditionalOnMissingBean}：使用方如果已经自己组装了工具集，就让他们说了算。
     */
    @Bean
    @ConditionalOnMissingBean(ToolCallbackProvider.class)
    public ToolCallbackProvider agentDatabaseToolCallbackProvider(DatabaseAgentTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
