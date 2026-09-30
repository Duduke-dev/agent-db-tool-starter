package com.duduke.agentdb.template;

import com.duduke.agentdb.error.AgentToolException;
import com.duduke.agentdb.error.ToolErrorCode;
import com.duduke.agentdb.template.dto.QueryTemplateInfo;
import com.duduke.agentdb.template.model.ParameterType;
import com.duduke.agentdb.template.model.ResolvedTemplate;
import com.duduke.agentdb.template.model.TemplateParameter;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

import static com.duduke.agentdb.template.QueryTemplateSchema.QT_DESCRIPTION;
import static com.duduke.agentdb.template.QueryTemplateSchema.QT_NAME;
import static com.duduke.agentdb.template.QueryTemplateSchema.QT_PARAMETERS_JSON;
import static com.duduke.agentdb.template.QueryTemplateSchema.QT_TEMPLATE_JSON;
import static com.duduke.agentdb.template.QueryTemplateSchema.QT_VISIBLE;
import static com.duduke.agentdb.template.QueryTemplateSchema.QUERY_TEMPLATE;

/**
 * 读取查询模板。
 * <p>
 * 和授权策略一样，模板<b>每次调用实时从库里读</b>，不做缓存 —— 加模板、改模板不需要重启。
 * 模板数量有限，几次轻查询的成本可以接受。
 * <p>
 * 解析模板时用的是<b>独立的严格 ObjectMapper</b>：开启 {@code FAIL_ON_UNKNOWN_PROPERTIES}。
 * 这和安全性直接相关 —— 如果允许未知字段，有人在模板里塞一个
 * {@code "evil": "${x}"} 就能把占位符藏进 {@link TemplatePlaceholder} 校验不到的位置。
 * 严格模式让这种模板在加载时就报错。
 */
@Component
public class QueryTemplateRepository {

    private static final Logger log = LoggerFactory.getLogger(QueryTemplateRepository.class);

    /** 只用于解析模板，不复用应用的 ObjectMapper —— 那些是宽松配置，这里需要严格。 */
    private final ObjectMapper strictMapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final DataSource dataSource;
    private final DSLContext dsl;
    private final TemplateParameterValidator parameterValidator;

    public QueryTemplateRepository(DataSource dataSource, DSLContext dsl) {
        this.dataSource = dataSource;
        this.dsl = dsl;
        this.parameterValidator = new TemplateParameterValidator(strictMapper);
    }

    /** 列出全部可见模板。只读 template_name / description / parameters_json，不解析模板主体。 */
    public List<QueryTemplateInfo> visibleTemplates() {
        return dsl
                .select(QT_NAME, QT_DESCRIPTION, QT_PARAMETERS_JSON)
                .from(QUERY_TEMPLATE)
                .where(QT_VISIBLE.isTrue())
                .orderBy(QT_NAME.asc())
                .fetch()
                .stream()
                .map(this::toInfo)
                .toList();
    }

    /**
     * 按名加载并编译一个模板。
     * <p>
     * 未登记、visible=false、模板内容非法，走不同的错误码：前两者是 {@code TEMPLATE_NOT_VISIBLE}
     * （且不区分，避免泄露模板存在性），后者是 {@code TEMPLATE_INVALID}（配置问题，运维需要知道）。
     */
    public ResolvedTemplate require(String templateName) {
        Record row = dsl
                .select(QT_NAME, QT_DESCRIPTION, QT_TEMPLATE_JSON, QT_PARAMETERS_JSON)
                .from(QUERY_TEMPLATE)
                .where(QT_NAME.equalIgnoreCase(templateName))
                .and(QT_VISIBLE.isTrue())
                .fetchOne();

        if (row == null) {
            throw new AgentToolException(ToolErrorCode.TEMPLATE_NOT_VISIBLE,
                    "模板 '" + templateName + "' 不在可用范围内。可用模板见 list_query_templates 的结果。");
        }

        String actualName = row.get(QT_NAME);
        try {
            JsonNode templateJson = strictMapper.readTree(row.get(QT_TEMPLATE_JSON));
            List<TemplateParameter> parameters = parseParameters(row.get(QT_PARAMETERS_JSON));

            TemplatePlaceholder.validatePlacement(templateJson, actualName);

            List<String> problems = TemplateParameterValidator.crossCheck(templateJson, parameters);
            if (!problems.isEmpty()) {
                throw new AgentToolException(ToolErrorCode.TEMPLATE_INVALID,
                        "模板 '" + actualName + "' 的参数声明与用法对不上：" + String.join("；", problems)
                                + "。请修正后重新登记。");
            }
            return new ResolvedTemplate(actualName, row.get(QT_DESCRIPTION), templateJson, parameters);
        } catch (JacksonException ex) {
            throw new AgentToolException(ToolErrorCode.TEMPLATE_INVALID,
                    "模板 '" + actualName + "' 的内容不是合法 JSON：" + ex.getMessage());
        }
    }

    public TemplateParameterValidator parameterValidator() {
        return parameterValidator;
    }

    private List<TemplateParameter> parseParameters(String parametersJson) {
        JsonNode root = strictMapper.readTree(parametersJson);
        if (!root.isArray()) {
            throw new AgentToolException(ToolErrorCode.TEMPLATE_INVALID,
                    "parameters_json 必须是一个数组，每项形如 {\"name\":...,\"type\":...}。");
        }

        List<TemplateParameter> parameters = new ArrayList<>();
        for (JsonNode item : root) {
            String name = item.path("name").asString("");
            parameters.add(new TemplateParameter(
                    name,
                    parseType(name, item.path("type").asString("")),
                    item.path("description").asString(""),
                    item.path("required").asBoolean(false),
                    stringList(item.path("allowedValues"))));
        }
        return parameters;
    }

    private ParameterType parseType(String parameterName, String type) {
        try {
            return ParameterType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new AgentToolException(ToolErrorCode.TEMPLATE_INVALID,
                    "参数 '" + parameterName + "' 的类型 '" + type + "' 不受支持，"
                            + "只能是 STRING / NUMBER / DATE / DATETIME / BOOLEAN 之一。");
        }
    }

    private List<String> stringList(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> values.add(item.asString()));
        return values;
    }

    private QueryTemplateInfo toInfo(Record row) {
        String name = row.get(QT_NAME);
        List<QueryTemplateInfo.ParameterBrief> briefs;
        try {
            briefs = parseParameters(row.get(QT_PARAMETERS_JSON)).stream()
                    .map(p -> new QueryTemplateInfo.ParameterBrief(
                            p.name(), p.type().name(), p.description(), p.required(), p.allowedValues()))
                    .toList();
        } catch (JacksonException | AgentToolException ex) {
            // 参数声明有问题时不让整个列表挂掉：把这条模板标出来，其余照常返回。
            log.warn("模板 '{}' 的参数声明无法解析，已跳过：{}", name, ex.getMessage());
            briefs = List.of();
        }
        return new QueryTemplateInfo(name, row.get(QT_DESCRIPTION), briefs);
    }
}
