package com.duduke.agentdb.template.model;

import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * 一条已解析并验证过的查询模板。
 * <p>
 * 构造过程本身就是一道关卡：{@code templateJson} 在变成这个对象之前，
 * 已经过占位符位置校验（只允许出现在 values 里）。所以拿到 {@code ResolvedTemplate}
 * 就意味着「这份模板不可能通过参数改变查询结构」。
 *
 * @param name         模板名
 * @param description  给模型看的用途说明
 * @param templateJson QueryRequest 的 JSON 骨架，含 ${param} 占位符
 * @param parameters   参数声明
 */
public record ResolvedTemplate(
        String name,
        String description,
        JsonNode templateJson,
        List<TemplateParameter> parameters
) {

    public ResolvedTemplate {
        parameters = (parameters == null) ? List.of() : List.copyOf(parameters);
    }

    public TemplateParameter findParameter(String parameterName) {
        return parameters.stream()
                .filter(p -> p.name().equals(parameterName))
                .findFirst()
                .orElse(null);
    }
}
