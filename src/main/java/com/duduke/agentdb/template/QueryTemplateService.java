package com.duduke.agentdb.template;

import com.duduke.agentdb.error.AgentToolException;
import com.duduke.agentdb.error.ToolErrorCode;
import com.duduke.agentdb.query.dsl.QueryRequest;
import com.duduke.agentdb.template.dto.QueryTemplateInfo;
import com.duduke.agentdb.template.model.ResolvedTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * 查询模板的对外门面：把「模板名 + 参数」解析成一个可直接执行的 {@link QueryRequest}。
 * <p>
 * 为什么要这一层：{@code TemplatePlaceholder}（占位符校验与填充）和
 * {@code TemplateParameterValidator}（参数校验）刻意保持包内可见 —— 它们是模板的内部实现，
 * 不该被别的包直接调用。外部只需要这一个入口，也就只有这一个入口可能被绕过。
 * <p>
 * <b>返回的是标准 {@code QueryRequest}，不返回 SQL、不返回编译结果。</b>
 * 模板调用方拿到的和模型自己构造查询时完全一样的东西，后续走同一条链路。
 */
@Component
public class QueryTemplateService {

    /**
     * 自己 new 一个，不注入 Spring 的 {@code ObjectMapper}。
     * <p>
     * 原因是依赖问题：这个 starter 刻意不带 {@code spring-boot-starter-web} /
     * {@code spring-boot-starter-json}，容器里根本没有 {@code ObjectMapper} bean。
     * 与其为此引入一个使用方未必需要的依赖，不如在这里自建一个 ——
     * 用途单一（把填充后的 JSON 转成 QueryRequest），也不需要任何全局配置。
     */
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private final QueryTemplateRepository repository;

    public QueryTemplateService(QueryTemplateRepository repository) {
        this.repository = repository;
    }

    /** 列出可用模板及其参数说明。 */
    public List<QueryTemplateInfo> visibleTemplates() {
        return repository.visibleTemplates();
    }

    /**
     * 解析模板：加载 → 校验占位符位置 → 校验参数 → 填充 → 转成 {@link QueryRequest}。
     * <p>
     * 任何一步失败都抛 {@link AgentToolException}，带着明确的错误码 —— 配置问题归
     * {@code TEMPLATE_INVALID}，调用参数问题归 {@code TEMPLATE_PARAMETER_INVALID}，
     * 模型据此能区分「该改参数」还是「该找运维」。
     */
    public QueryRequest resolve(String templateName, Map<String, Object> parameters) {
        ResolvedTemplate template = repository.require(templateName);

        Map<String, String> values = repository.parameterValidator()
                .validateAndNormalize(template, parameters);

        JsonNode filled = TemplatePlaceholder.fill(template.templateJson(), values);

        try {
            return objectMapper.treeToValue(filled, QueryRequest.class);
        } catch (JacksonException ex) {
            // 走到这里说明模板填充后仍无法构成合法 QueryRequest —— 模板本身写错了（配置问题）
            throw new AgentToolException(ToolErrorCode.TEMPLATE_INVALID,
                    "模板 '" + template.name() + "' 填充后不是合法的查询请求：" + ex.getMessage());
        }
    }
}
