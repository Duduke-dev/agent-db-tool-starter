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
public record AgentDbProperties(Limits limits, Bootstrap bootstrap, Tools tools) {

    public AgentDbProperties {
        limits = (limits == null) ? new Limits(0, 0, 0, null, 0, 0, 0) : limits;
        bootstrap = (bootstrap == null) ? Bootstrap.disabled() : bootstrap;
        tools = (tools == null) ? Tools.defaults() : tools;
    }

    /**
     * 按需装载哪几组工具。
     * <p>
     * 关掉一组，对应的 bean 根本不会注册 —— 模型看不到它们的工具说明，
     * 上下文更干净，选择也更准。这是「工具膨胀」的正面解法：
     * 与其让模型在一堆无关工具里挑，不如按场景只给它需要的。
     */
    public record Tools(boolean query, boolean template) {

        /**
         * 自由查询默认开：关掉它这个 starter 就没有任何工具了，那不如不引入。
         * 查询模板默认关：它是后加的能力，且要建额外的表，需要的人显式打开。
         */
        public static Tools defaults() {
            return new Tools(true, false);
        }
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
