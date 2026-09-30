package com.duduke.agentdb.template;

import com.duduke.agentdb.error.AgentToolException;
import com.duduke.agentdb.error.ToolErrorCode;
import com.duduke.agentdb.template.model.ResolvedTemplate;
import com.duduke.agentdb.template.model.TemplateParameter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 校验模型传进来的模板参数值，并把它归一化成「参数名 → 字符串值」。
 * <p>
 * 归一化的意义在于：模板填充只需要处理字符串，不必再关心模型这次把日期写成了什么格式。
 * <p>
 * 校验分四道：<b>未声明的参数</b>（可能是模型在乱试）、<b>缺必填项</b>、
 * <b>类型不符</b>、<b>超出枚举范围</b>。四类都归到 {@code TEMPLATE_PARAMETER_INVALID}，
 * 因为对模型来说都是「参数给错了，改了再来」。
 */
final class TemplateParameterValidator {

    private final ObjectMapper objectMapper;

    TemplateParameterValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 校验并归一化参数。
     *
     * @param template  已解析的模板
     * @param rawParams 模型给的原始参数（值为任意 JSON 类型）
     * @return 参数名 → 字符串值，可直接用于填充
     */
    Map<String, String> validateAndNormalize(ResolvedTemplate template, Map<String, Object> rawParams) {
        Map<String, Object> params = (rawParams == null) ? Map.of() : rawParams;

        rejectUndeclared(template, params);
        Map<String, String> normalized = new LinkedHashMap<>();
        for (TemplateParameter declared : template.parameters()) {
            Object raw = params.get(declared.name());
            if (raw == null) {
                if (declared.required()) {
                    throw invalid(template, "缺少必填参数 '" + declared.name() + "'（" + declared.description() + "）");
                }
                continue;
            }
            normalized.put(declared.name(), normalizeOne(template, declared, raw));
        }
        return normalized;
    }

    /**
     * 拒绝未声明的参数。
     * <p>
     * 这一条容易被当成小事，其实是安全信号：模型如果试图传 {@code table} / {@code column}
     * 这类名字，说明它在试探「能不能改查询结构」。直接拒绝比静默忽略好 ——
     * 静默忽略会让模型以为参数生效了。
     */
    private void rejectUndeclared(ResolvedTemplate template, Map<String, Object> params) {
        Set<String> declared = new LinkedHashSet<>();
        template.parameters().forEach(p -> declared.add(p.name()));

        List<String> undeclared = params.keySet().stream()
                .filter(name -> !declared.contains(name))
                .sorted()
                .toList();

        if (!undeclared.isEmpty()) {
            throw invalid(template,
                    "出现了模板未声明的参数：" + String.join("、", undeclared)
                            + "。本模板只接受：" + String.join("、", declared));
        }
    }

    private String normalizeOne(ResolvedTemplate template, TemplateParameter declared, Object raw) {
        String text = toText(raw);

        if (declared.hasAllowedValues() && !declared.allowedValues().contains(text)) {
            throw invalid(template, "参数 '" + declared.name() + "' 的取值必须是："
                    + String.join("、", declared.allowedValues()) + "，收到的是 '" + text + "'");
        }

        try {
            return switch (declared.type()) {
                case STRING -> text;
                case NUMBER -> String.valueOf(parseNumber(text));
                case DATE -> LocalDate.parse(text).toString();
                case DATETIME -> LocalDateTime.parse(text.replace(' ', 'T')).toString().replace('T', ' ');
                case BOOLEAN -> String.valueOf(parseBoolean(text));
            };
        } catch (IllegalArgumentException ex) {
            throw invalid(template, "参数 '" + declared.name() + "' 期望 " + declared.type()
                    + " 类型的值，收到的是 '" + text + "'");
        }
    }

    private Object parseNumber(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("不是数字", ex);
            }
        }
    }

    private boolean parseBoolean(String text) {
        if ("true".equalsIgnoreCase(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text)) {
            return false;
        }
        throw new IllegalArgumentException("不是布尔值");
    }

    /** 把参数值转成文本。JSON 里的嵌套结构（对象、数组）不能作为模板参数值。 */
    private String toText(Object raw) {
        if (raw instanceof String s) {
            return s;
        }
        if (raw instanceof Number || raw instanceof Boolean) {
            return raw.toString();
        }
        if (raw instanceof JsonNode node) {
            if (node.isObject() || node.isArray()) {
                throw new IllegalArgumentException("参数值不能是对象或数组");
            }
            return node.asString();
        }
        throw new IllegalArgumentException("不支持的参数值类型：" + raw.getClass().getSimpleName());
    }

    private AgentToolException invalid(ResolvedTemplate template, String detail) {
        return new AgentToolException(ToolErrorCode.TEMPLATE_PARAMETER_INVALID,
                "模板 '" + template.name() + "' 的参数有误：" + detail);
    }

    /** 供写模板的人自查：报告「声明了但没用到」和「用了但没声明」的参数。 */
    static List<String> crossCheck(JsonNode templateJson, List<TemplateParameter> parameters) {
        Set<String> used = new LinkedHashSet<>(TemplatePlaceholder.collectParameterNames(templateJson));
        Set<String> declared = new LinkedHashSet<>();
        parameters.forEach(p -> declared.add(p.name()));

        List<String> problems = new ArrayList<>();
        declared.stream().filter(name -> !used.contains(name))
                .forEach(name -> problems.add("声明了参数 '" + name + "' 但模板里没用"));
        used.stream().filter(name -> !declared.contains(name))
                .forEach(name -> problems.add("模板用了 ${" + name + "} 但没声明"));
        return problems;
    }
}
