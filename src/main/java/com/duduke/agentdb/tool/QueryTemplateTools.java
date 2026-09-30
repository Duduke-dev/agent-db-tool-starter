package com.duduke.agentdb.tool;

import com.duduke.agentdb.template.QueryTemplateService;
import com.duduke.agentdb.template.dto.QueryTemplateInfo;
import com.duduke.agentdb.tool.dto.QueryOutcome;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 查询模板工具的对外门面。
 * <p>
 * <b>和 {@link DatabaseAgentTools} 刻意分开。</b>那边是「让模型自由表达查询」，
 * 这边是「让模型挑选已固化的查询」—— 两者的心智模型不同，混在一个类里
 * 会让模型看到的工具列表变成一锅粥。
 * <p>
 * 两个工具是配套的：先 {@code list_query_templates} 看有哪些模板、要传什么参数，
 * 再 {@code run_query_template} 填参数执行。
 * <p>
 * <b>模板不产生任何新权限。</b>{@link QueryTemplateService} 产出的就是标准 {@code QueryRequest}，
 * 交给 {@link DatabaseAgentTools#query} 走同一条链路 —— 三层校验、只读、上限、超时全部照常生效。
 * 模板里的列如果被取消授权，执行时一样会被拒绝。
 */
@Component
public class QueryTemplateTools {

    private final QueryTemplateService templateService;
    private final DatabaseAgentTools databaseAgentTools;

    public QueryTemplateTools(QueryTemplateService templateService, DatabaseAgentTools databaseAgentTools) {
        this.templateService = templateService;
        this.databaseAgentTools = databaseAgentTools;
    }

    @Tool(name = "list_query_templates", description = """
            列出可用的查询模板及其参数说明。
            常用的、口径敏感的统计（如「按月统计各分类销售额」）通常已经固化成模板，
            优先用模板而不是自行构造 query —— 模板的口径是确认过的，结果更可靠，也更省往返。
            拿到模板名和参数说明后，用 run_query_template 执行。
            """)
    public List<QueryTemplateInfo> listQueryTemplates() {
        return templateService.visibleTemplates();
    }

    @Tool(name = "run_query_template", description = """
            按名称执行一个查询模板，只需提供模板声明的参数。
            参数名和类型必须与 list_query_templates 返回的说明一致；传未声明的参数会被拒绝。
            查询结构由模板固定，无法通过参数改变表名、列名或过滤方式。
            """)
    public List<QueryOutcome> runQueryTemplate(
            @ToolParam(description = "模板名，来自 list_query_templates") String templateName,
            @ToolParam(description = "模板参数，键为参数名、值为参数值", required = false)
            Map<String, Object> parameters) {

        // 委托给既有查询入口：批处理、错误包装、三层校验都由那边负责，这里不重复实现
        return databaseAgentTools.query(List.of(templateService.resolve(templateName, parameters)));
    }
}
