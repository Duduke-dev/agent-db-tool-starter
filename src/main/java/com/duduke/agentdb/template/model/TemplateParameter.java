package com.duduke.agentdb.template.model;

import java.util.List;

/**
 * 模板声明的一个参数。
 * <p>
 * 这份声明有两个用途：给模型看（生成「这个模板要传什么」的说明），
 * 以及在填模板之前校验模型给的值的合法性。
 *
 * @param name          参数名，对应模板里的 ${name}
 * @param type          期望的类型
 * @param description   给模型看的业务说明
 * @param required      是否必填
 * @param allowedValues 枚举约束；非空时取值必须落在这个集合里
 */
public record TemplateParameter(
        String name,
        ParameterType type,
        String description,
        boolean required,
        List<String> allowedValues
) {

    public TemplateParameter {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("参数名不能为空");
        }
        if (type == null) {
            throw new IllegalArgumentException("参数 '" + name + "' 未声明类型");
        }
        description = (description == null) ? "" : description;
        allowedValues = (allowedValues == null) ? List.of() : List.copyOf(allowedValues);
    }

    public boolean hasAllowedValues() {
        return !allowedValues.isEmpty();
    }
}
