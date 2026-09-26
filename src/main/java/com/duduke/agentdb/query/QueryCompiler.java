package com.duduke.agentdb.query;

import com.duduke.agentdb.error.AgentToolException;
import com.duduke.agentdb.error.ToolErrorCode;
import com.duduke.agentdb.policy.PolicyRepository;
import com.duduke.agentdb.policy.model.ResolvedColumn;
import com.duduke.agentdb.policy.model.ResolvedTable;
import com.duduke.agentdb.query.dsl.Aggregate;
import com.duduke.agentdb.query.dsl.Condition;
import com.duduke.agentdb.query.dsl.OrderItem;
import com.duduke.agentdb.query.dsl.QueryRequest;
import com.duduke.agentdb.query.dsl.SelectItem;
import com.duduke.agentdb.query.validation.AuthorizationValidator;
import com.duduke.agentdb.query.validation.ParameterValidator;
import com.duduke.agentdb.query.validation.SemanticValidator;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SelectQuery;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 把结构化 DSL 编译成可执行的 jOOQ 查询。
 * <p>
 * 这里有一个贯穿始终的约束：<b>标识符只从策略里取 jOOQ 的 Field 对象，永远不以字符串形式参与拼接</b>。
 * 带来的两个好处是 —— 注入面直接归零（没有字符串能进入 SQL 的语法层），
 * 以及大小写天然对得上（Field 来自数据库元数据，H2 的大写和 PostgreSQL 的小写都不用手工适配）。
 * <p>
 * 值则一律走 jOOQ 的绑定参数，并且在绑定前按列的 JDBC 类型做一次转换 ——
 * 类型解释权在服务端，不接受「字符串硬比数字列」这种隐式转换，避免索引失效。
 */
@Component
public class QueryCompiler {

    private final DSLContext dsl;
    private final PolicyRepository repository;
    private final AuthorizationValidator authorizationValidator;
    private final ParameterValidator parameterValidator;
    private final SemanticValidator semanticValidator;

    public QueryCompiler(DSLContext dsl,
                         PolicyRepository repository,
                         AuthorizationValidator authorizationValidator,
                         ParameterValidator parameterValidator,
                         SemanticValidator semanticValidator) {
        this.dsl = dsl;
        this.repository = repository;
        this.authorizationValidator = authorizationValidator;
        this.parameterValidator = parameterValidator;
        this.semanticValidator = semanticValidator;
    }

    public CompiledQuery compile(QueryRequest request) {
        ResolvedTable table = repository.require(request.table());

        // 三层校验依次过一遍：能不能碰 -> 能不能这么用 -> 这么写语义成不成立
        authorizationValidator.validate(table, request);
        parameterValidator.validate(table, request, repository.limits());
        semanticValidator.validate(request);

        List<String> outputLabels = new ArrayList<>();
        List<Field<?>> selectFields = buildSelect(table, request, outputLabels);
        if (selectFields.isEmpty()) {
            throw new AgentToolException(ToolErrorCode.COLUMN_NOT_SELECTABLE,
                    "表 '%s' 当前没有任何可输出的列".formatted(table.name()));
        }

        List<org.jooq.Condition> conditions = new ArrayList<>();
        for (Condition condition : request.where()) {
            conditions.add(toJooqCondition(table, condition));
        }

        int limit = (request.limit() != null) ? request.limit() : repository.limits().defaultLimit();

        SelectQuery<Record> query = dsl.selectQuery();
        query.addSelect(selectFields);
        query.addFrom(table.table());
        if (!conditions.isEmpty()) {
            query.addConditions(conditions);
        }
        if (!request.groupBy().isEmpty()) {
            query.addGroupBy(request.groupBy().stream()
                    .map(name -> (Field<?>) table.findColumn(name).field())
                    .toList());
        }
        if (!request.orderBy().isEmpty()) {
            query.addOrderBy(request.orderBy().stream().map(order -> {
                Field<?> field = table.findColumn(order.column()).field();
                return switch (order.direction()) {
                    case ASC -> field.asc();
                    case DESC -> field.desc();
                };
            }).toList());
        }
        // 多取一行用来判断是否被截断，比「行数恰好等于 limit 就猜被截断」准确
        query.addLimit(limit + 1);
        query.queryTimeout((int) Math.max(1, repository.limits().statementTimeout().toSeconds()));

        return new CompiledQuery(table.name(), query, outputLabels, limit);
    }

    private List<Field<?>> buildSelect(ResolvedTable table,
                                       QueryRequest request,
                                       List<String> outputLabels) {
        List<Field<?>> fields = new ArrayList<>();
        if (request.select().isEmpty()) {
            // 没指定输出列时，取策略里所有 selectable 的列 —— 而不是 SELECT *。
            // 这样即便将来给表加了新列，只要没在策略里声明就不会被带出去。
            for (ResolvedColumn column : table.defaultSelectableColumns()) {
                fields.add(column.field());
                outputLabels.add(column.name());
            }
            return fields;
        }
        for (SelectItem item : request.select()) {
            ResolvedColumn column = table.findColumn(item.column());
            fields.add(applyAggregate(column.field(), item.aggregate()).as(item.alias()));
            outputLabels.add(item.alias());
        }
        return fields;
    }

    /**
     * 聚合一律走 DSL 静态工厂，而不是 Field 上的同名便捷方法 —— 后者在 jOOQ 3.21 已被标记为待删除。
     * 这里的原始类型转换是为了适配 sum / avg 只接受 Field&lt;? extends Number&gt; 的签名，
     * 列类型的正确性已由 ParameterValidator 保证（非数值列不允许 SUM / AVG）。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private Field<?> applyAggregate(Field<?> field, Aggregate aggregate) {
        return switch (aggregate) {
            case NONE -> field;
            case COUNT -> DSL.count(field);
            case SUM -> DSL.sum((Field) field);
            case AVG -> DSL.avg((Field) field);
            case MIN -> DSL.min((Field) field);
            case MAX -> DSL.max((Field) field);
        };
    }

    /**
     * 把一条 DSL 条件翻译成 jOOQ 条件。
     * <p>
     * 用原始类型调用比较方法是为了绕开泛型擦除：Field&lt;?&gt; 无法直接调用 eq(T)。
     * 安全性由上游保证 —— 列一定来自策略白名单，值一定按列类型转换过。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private org.jooq.Condition toJooqCondition(ResolvedTable table, Condition condition) {
        ResolvedColumn column = table.findColumn(condition.column());
        Field field = column.field();

        return switch (condition.operator()) {
            case EQ -> field.eq(literal(column, condition.values(), 0));
            case NE -> field.ne(literal(column, condition.values(), 0));
            case GT -> field.gt(literal(column, condition.values(), 0));
            case GE -> field.ge(literal(column, condition.values(), 0));
            case LT -> field.lt(literal(column, condition.values(), 0));
            case LE -> field.le(literal(column, condition.values(), 0));
            case IN -> field.in(condition.values().stream().map(v -> literal(column, v)).toList());
            case CONTAINS -> field.like("%" + escapeLike(text(condition)) + "%", '\\');
            case STARTS_WITH -> field.like(escapeLike(text(condition)) + "%", '\\');
            case ENDS_WITH -> field.like("%" + escapeLike(text(condition)), '\\');
            case IS_NULL -> field.isNull();
            case IS_NOT_NULL -> field.isNotNull();
        };
    }

    private Object literal(ResolvedColumn column, List<String> values, int index) {
        if (index >= values.size()) {
            throw new AgentToolException(ToolErrorCode.INVALID_VALUE_TYPE,
                    "列 '%s' 的条件缺少值".formatted(column.name()));
        }
        return literal(column, values.get(index));
    }

    /**
     * 按列的 Java 类型转换模型给的值。
     * <p>
     * 转换失败时把「列是什么类型」一并告诉模型 —— 只说「参数不合法」它只会原地重试，
     * 说清楚类型它下一次就能改对。
     */
    private Object literal(ResolvedColumn column, String raw) {
        try {
            return convert(column.field().getDataType().getType(), raw);
        } catch (RuntimeException ex) {
            throw new AgentToolException(ToolErrorCode.INVALID_VALUE_TYPE,
                    "列 '%s' 的类型是 %s，无法把值 '%s' 转换成该类型"
                            .formatted(column.name(), column.typeName(), raw));
        }
    }

    /**
     * 自己写而不用 jOOQ 的 {@code DataType.convert}：一来它在 3.21 已废弃，
     * 二来「类型解释权在服务端」这条规则，写在代码里比藏在库的默认行为里更可读。
     * 未覆盖的类型直接透传，交给 JDBC 驱动处理。
     */
    private static Object convert(Class<?> type, String raw) {
        if (type == String.class) {
            return raw;
        }
        if (type == Long.class || type == long.class) {
            return Long.valueOf(raw);
        }
        if (type == Integer.class || type == int.class) {
            return Integer.valueOf(raw);
        }
        if (type == Short.class || type == short.class) {
            return Short.valueOf(raw);
        }
        if (type == BigDecimal.class) {
            return new BigDecimal(raw);
        }
        if (type == Double.class || type == double.class) {
            return Double.valueOf(raw);
        }
        if (type == Float.class || type == float.class) {
            return Float.valueOf(raw);
        }
        if (type == Boolean.class || type == boolean.class) {
            return Boolean.valueOf(raw);
        }
        if (type == LocalDate.class) {
            return LocalDate.parse(raw);
        }
        if (type == LocalDateTime.class) {
            return LocalDateTime.parse(raw.replace(' ', 'T'));
        }
        if (type == LocalTime.class) {
            return LocalTime.parse(raw);
        }
        if (type == java.sql.Date.class) {
            return java.sql.Date.valueOf(raw);
        }
        if (type == java.sql.Timestamp.class) {
            return java.sql.Timestamp.valueOf(raw);
        }
        if (type == UUID.class) {
            return UUID.fromString(raw);
        }
        return raw;
    }

    private String text(Condition condition) {
        return condition.values().isEmpty() ? "" : condition.values().get(0);
    }

    /**
     * 转义 LIKE 的通配符。
     * <p>
     * 这一步不能省：模型传 "50%" 时那个百分号是字面量，不转义就成了通配符，
     * 一条本意精确匹配的查询会退化成全表扫描 —— 既慢，也可能捞出本不该给它的数据。
     */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
