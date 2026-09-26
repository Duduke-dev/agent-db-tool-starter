package com.duduke.agentdb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 应用级配置，来自 application.yml 的 {@code agent-db.*}。
 * <p>
 * 授权策略不在这里 —— 它在数据库的 agent_table_policy / agent_column_policy 两张表里，
 * 每次调用实时读取。这里装的是不随授权变化、也不该由业务侧调整的参数。
 */
@ConfigurationProperties(prefix = "agent-db")
public record AgentDbProperties(Limits limits, Bootstrap bootstrap) {

    public AgentDbProperties {
        limits = (limits == null) ? new Limits(0, 0, 0, null, 0, 0, 0) : limits;
        bootstrap = (bootstrap == null) ? Bootstrap.disabled() : bootstrap;
    }

    /** 全局安全阀，对所有查询统一生效。 */
    public record Limits(
            int defaultLimit,
            int maxLimit,
            int maxResultBytes,
            Duration statementTimeout,
            int maxInValues,
            int maxTablesPerDescribe,
            int maxQueriesPerCall
    ) {
        public Limits {
            defaultLimit = (defaultLimit <= 0) ? 50 : defaultLimit;
            maxLimit = (maxLimit <= 0) ? 200 : maxLimit;
            maxResultBytes = (maxResultBytes <= 0) ? 256 * 1024 : maxResultBytes;
            statementTimeout = (statementTimeout == null) ? Duration.ofSeconds(5) : statementTimeout;
            maxInValues = (maxInValues <= 0) ? 100 : maxInValues;
            maxTablesPerDescribe = (maxTablesPerDescribe <= 0) ? 20 : maxTablesPerDescribe;
            // 比 describe_table 的上限小：query 是真查数据，一条就很重
            maxQueriesPerCall = (maxQueriesPerCall <= 0) ? 10 : maxQueriesPerCall;
        }
    }

    /**
     * 启动时建授权表用的一对管理员凭据。
     * <p>
     * 为什么不复用应用的数据源：应用连的是<b>只读账号</b>，建不了表 ——
     * 这是刻意的安全设计，不该为了图省事把 DDL 权限放回去。
     * 所以单独配一个账号，只在启动期用一次。
     * <p>
     * <b>这里没有 url</b>：它和应用连的是同一个库，地址从 {@code spring.datasource.url} 推导，
     * 避免同一个地址在两处配置里各写一遍、改漏一处就连错库。
     * <p>
     * 生产环境建议把 {@code enabled} 关掉、让建表归 migration 管，
     * 并用环境变量传密码（{@code ${AGENT_DB_BOOTSTRAP_PASSWORD:...}}）而不是写死在文件里。
     */
    public record Bootstrap(boolean enabled, String username, String password) {

        public Bootstrap {
            username = (username == null) ? "" : username;
            password = (password == null) ? "" : password;
        }

        public static Bootstrap disabled() {
            return new Bootstrap(false, null, null);
        }
    }
}
