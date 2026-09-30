package com.duduke.agentdb.template;

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
import java.sql.DriverManager;
import java.sql.SQLException;

import static com.duduke.agentdb.template.QueryTemplateSchema.QT_DESCRIPTION;
import static com.duduke.agentdb.template.QueryTemplateSchema.QT_NAME;
import static com.duduke.agentdb.template.QueryTemplateSchema.QT_PARAMETERS_JSON;
import static com.duduke.agentdb.template.QueryTemplateSchema.QT_TEMPLATE_JSON;
import static com.duduke.agentdb.template.QueryTemplateSchema.QT_VISIBLE;
import static com.duduke.agentdb.template.QueryTemplateSchema.QUERY_TEMPLATE;

/**
 * 启动时把查询模板表建好。
 * <p>
 * 用管理员连接建表 —— 应用自己是只读账号，这条约束和授权表那边是同一个。
 * <p>
 * <b>只建表，不塞任何模板。</b>模板内容属于统计口径决策，由 DBA 按业务需要登记，
 * 硬编码一份初始模板进代码，用起来就死板了。默认 {@code visible = FALSE}，
 * 和授权表一样是「默认拒绝」——加完模板还得显式放行。
 */
@Component
public class QueryTemplateInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(QueryTemplateInitializer.class);

    private final AgentDbProperties properties;
    private final DataSourceProperties dataSourceProperties;

    public QueryTemplateInitializer(AgentDbProperties properties, DataSourceProperties dataSourceProperties) {
        this.properties = properties;
        this.dataSourceProperties = dataSourceProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        AgentDbProperties.Bootstrap bootstrap = properties.bootstrap();
        if (!bootstrap.enabled()) {
            log.info("查询模板表自动初始化已关闭（agent-db.bootstrap.enabled=false）");
            return;
        }

        String url = dataSourceProperties.getUrl();
        if (url == null || url.isBlank()) {
            log.error("spring.datasource.url 未配置，无法创建查询模板表");
            return;
        }

        try (Connection connection = DriverManager.getConnection(url, bootstrap.username(), bootstrap.password())) {
            DSLContext admin = DSL.using(connection);
            admin.createTableIfNotExists(QUERY_TEMPLATE)
                    .column(QT_NAME, SQLDataType.VARCHAR(128).nullable(false))
                    .column(QT_DESCRIPTION, SQLDataType.VARCHAR(500).nullable(false).defaultValue(DSL.inline("")))
                    .column(QT_VISIBLE, SQLDataType.BOOLEAN.nullable(false).defaultValue(DSL.inline(false)))
                    .column(QT_TEMPLATE_JSON, SQLDataType.CLOB.nullable(false))
                    .column(QT_PARAMETERS_JSON, SQLDataType.CLOB.nullable(false))
                    .constraints(DSL.primaryKey(QT_NAME))
                    .execute();
            log.info("查询模板表已就绪：agent_query_template（默认 visible = FALSE，登记后需显式放行）");
        } catch (SQLException ex) {
            log.error("查询模板表初始化失败（不影响授权表与应用启动）：{}", ex.getMessage());
        }
    }
}
