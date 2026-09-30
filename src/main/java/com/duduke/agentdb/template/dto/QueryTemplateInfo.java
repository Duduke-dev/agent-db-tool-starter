package com.duduke.agentdb.template.dto;

import java.util.List;

/**
 * 给模型看的模板概要。
 * <p>
 * 参数说明是这份 DTO 的重点：模型只有看到「这个模板要传哪些参数、什么类型、取值范围」，
 * 才能一次把 {@code run_query_template} 调对。参数名或类型缺失，模型就只能试错。
 */
public record QueryTemplateInfo(
        String name,
        String description,
        List<ParameterBrief> parameters
) {

    public QueryTemplateInfo {
        parameters = (parameters == null) ? List.of() : List.copyOf(parameters);
    }

    /**
     * 单个参数的说明。
     *
     * @param allowedValues 非空时表示取值只能是这几个之一 —— 模型应当直接照抄，而不是自己编
     */
    public record ParameterBrief(
            String name,
            String type,
            String description,
            boolean required,
            List<String> allowedValues
    ) {

        public ParameterBrief {
            allowedValues = (allowedValues == null) ? List.of() : List.copyOf(allowedValues);
        }
    }
}
