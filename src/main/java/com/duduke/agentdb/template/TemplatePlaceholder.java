package com.duduke.agentdb.template;

import com.duduke.agentdb.error.AgentToolException;
import com.duduke.agentdb.error.ToolErrorCode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模板占位符的校验与填充。
 * <p>
 * <b>这里是整个模板功能的安全边界。</b>模板允许携带 {@code ${param}}，但参数来自模型，
 * 所以必须钉死一件事：<b>参数只能落在 {@code where[].values[]} 里，永远不能出现在
 * table / column / operator / groupBy / orderBy / limit 这些位置</b>。
 * 一旦参数能充当标识符，模型就能绕开授权表自己决定查哪张表、哪个列 —— 授权就白做了。
 * <p>
 * 校验是<b>白名单式</b>的：显式列出每个允许出现文本的位置并逐个检查，
 * 而不是「扫描全部字符串、排除几个已知安全的位置」。后者一旦漏掉某个位置就会失守。
 */
final class TemplatePlaceholder {

    /** 占位符形如 ${name}；名字限定为字母数字下划线，避免出现奇怪的表达式语法。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_]+)}");

    /** 用于判断某段文本里是否出现占位符（含未闭合的 ${ 这类非法写法）。 */
    private static final String PLACEHOLDER_MARK = "${";

    private TemplatePlaceholder() {
    }

    /**
     * 校验模板里占位符出现的位置是否合法。不合法直接抛错，不做「过滤掉危险部分」的兜底 ——
     * 静默改写模板会让写模板的人以为它生效了。
     */
    static void validatePlacement(JsonNode template, String templateName) {
        List<String> illegal = new ArrayList<>();

        checkText(template.path("table"), "table", illegal);

        for (JsonNode item : arrayOrEmpty(template, "select")) {
            checkText(item.path("column"), "select[].column", illegal);
            checkText(item.path("aggregate"), "select[].aggregate", illegal);
            checkText(item.path("alias"), "select[].alias", illegal);
        }

        for (JsonNode item : arrayOrEmpty(template, "where")) {
            checkText(item.path("column"), "where[].column", illegal);
            checkText(item.path("operator"), "where[].operator", illegal);
            // where[].values[] 是唯一允许携带占位符的位置 —— 刻意不检查
        }

        for (JsonNode item : arrayOrEmpty(template, "groupBy")) {
            checkText(item, "groupBy[]", illegal);
        }

        for (JsonNode item : arrayOrEmpty(template, "orderBy")) {
            checkText(item.path("column"), "orderBy[].column", illegal);
            checkText(item.path("direction"), "orderBy[].direction", illegal);
        }

        checkText(template.path("limit"), "limit", illegal);

        if (!illegal.isEmpty()) {
            throw new AgentToolException(ToolErrorCode.TEMPLATE_INVALID,
                    "模板 '%s' 的占位符位置不合法：%s。占位符只能出现在 where[].values[] 里，"
                            .formatted(templateName, String.join("、", illegal))
                            + "不能用于表名、列名、操作符、分组或排序 —— 否则参数可以改变查询结构。");
        }
    }

    /**
     * 用参数值填充模板，返回一份新的 JSON。
     * <p>
     * 只在 {@code where[].values[]} 上做替换。替换结果最终会作为<b>绑定值</b>交给 jOOQ，
     * 不会拼进 SQL 文本，所以这里不必做转义 —— 但要明确：<b>这个前提成立于「值不参与语法」，
     * 而 validatePlacement 已经保证了这一点</b>。
     */
    static JsonNode fill(JsonNode template, Map<String, String> values) {
        JsonNode filled = template.deepCopy();

        for (JsonNode condition : arrayOrEmpty(filled, "where")) {
            JsonNode rawValues = condition.path("values");
            if (!rawValues.isArray()) {
                continue;
            }
            ArrayNode target = (ArrayNode) rawValues;
            for (int i = 0; i < target.size(); i++) {
                target.set(i, JsonNodeFactory.instance.stringNode(substitute(target.get(i).asString(), values)));
            }
        }
        return filled;
    }

    /** 收集模板里出现的全部占位符名，用于「声明了但没用」和「用了但没声明」的交叉检查。 */
    static List<String> collectParameterNames(JsonNode template) {
        List<String> names = new ArrayList<>();
        for (JsonNode condition : arrayOrEmpty(template, "where")) {
            for (JsonNode value : arrayOrEmpty(condition, "values")) {
                Matcher matcher = PLACEHOLDER.matcher(value.asString());
                while (matcher.find()) {
                    names.add(matcher.group(1));
                }
            }
        }
        return names;
    }

    private static String substitute(String raw, Map<String, String> values) {
        Matcher matcher = PLACEHOLDER.matcher(raw);

        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String replacement = values.get(name);
            if (replacement == null) {
                throw new AgentToolException(ToolErrorCode.TEMPLATE_PARAMETER_INVALID,
                        "参数 '" + name + "' 没有提供值。");
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static void checkText(JsonNode node, String location, List<String> illegal) {
        if (node.isString() && node.asString().contains(PLACEHOLDER_MARK)) {
            illegal.add(location);
        }
    }

    private static Iterable<JsonNode> arrayOrEmpty(JsonNode parent, String field) {
        JsonNode node = parent.path(field);
        return node.isArray() ? node : List.of();
    }
}
