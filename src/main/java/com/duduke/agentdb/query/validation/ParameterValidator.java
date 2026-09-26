package com.duduke.agentdb.query.validation;

import com.duduke.agentdb.error.AgentToolException;
import com.duduke.agentdb.error.ToolErrorCode;
import com.duduke.agentdb.config.AgentDbProperties;
import com.duduke.agentdb.policy.ResolvedColumn;
import com.duduke.agentdb.policy.ResolvedTable;
import com.duduke.agentdb.query.dsl.Aggregate;
import com.duduke.agentdb.query.dsl.Condition;
import com.duduke.agentdb.query.dsl.Operator;
import com.duduke.agentdb.query.dsl.QueryRequest;
import com.duduke.agentdb.query.dsl.SelectItem;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 第二层：参数校验。
 * <p>
 * 授权过了不等于参数合理。这一层管的是「能不能这么用」：条数上限、聚合与列类型的搭配、
 * 各操作符所需的值个数。值和列类型是否真的兼容留到编译阶段报，因为那里才做类型转换。
 */
@Component
public class ParameterValidator {

    public void validate(ResolvedTable table, QueryRequest request, AgentDbProperties.Limits limits) {
        validateLimit(request, limits);
        validateAggregates(table, request);
        validateConditions(request, limits);
    }

    private void validateLimit(QueryRequest request, AgentDbProperties.Limits limits) {
        Integer limit = request.limit();
        if (limit == null) {
            return;
        }
        if (limit <= 0) {
            throw new AgentToolException(ToolErrorCode.LIMIT_EXCEEDED,
                    "limit 必须大于 0，实际收到 %d".formatted(limit));
        }
        if (limit > limits.maxLimit()) {
            // 这里选择「拒绝并告知」而不是静默截断：静默截断会让模型以为已经拿到了全量数据，
            // 进而在错的结论上继续推理，比直接报错危险得多。
            throw new AgentToolException(ToolErrorCode.LIMIT_EXCEEDED,
                    "limit=%d 超过上限 %d。请改用不超过 %d 的值；若只是想看总量，请用 groupBy + COUNT 做聚合。"
                            .formatted(limit, limits.maxLimit(), limits.maxLimit()));
        }
    }

    private void validateAggregates(ResolvedTable table, QueryRequest request) {
        for (SelectItem item : request.select()) {
            if (item.aggregate() != Aggregate.SUM && item.aggregate() != Aggregate.AVG) {
                continue;
            }
            ResolvedColumn column = table.findColumn(item.column());
            if (column == null || isNumeric(column)) {
                continue;
            }
            throw new AgentToolException(ToolErrorCode.INVALID_AGGREGATE,
                    "列 '%s' 的类型是 %s，不能做 %s 聚合（SUM / AVG 只适用于数值列）"
                            .formatted(column.name(), column.typeName(), item.aggregate()));
        }
    }

    private void validateConditions(QueryRequest request, AgentDbProperties.Limits limits) {
        for (Condition condition : request.where()) {
            Operator operator = condition.operator();
            List<String> values = condition.values();
            switch (operator) {
                case IS_NULL, IS_NOT_NULL -> {
                    // 不需要值，传了也忽略
                }
                case IN -> {
                    if (values.isEmpty()) {
                        throw new AgentToolException(ToolErrorCode.INVALID_VALUE_TYPE,
                                "列 '%s' 使用 IN 时至少需要一个值".formatted(condition.column()));
                    }
                    if (values.size() > limits.maxInValues()) {
                        throw new AgentToolException(ToolErrorCode.IN_CLAUSE_TOO_LARGE,
                                "IN 的元素个数 %d 超过上限 %d".formatted(values.size(), limits.maxInValues()));
                    }
                }
                default -> {
                    if (values.size() != 1) {
                        throw new AgentToolException(ToolErrorCode.INVALID_VALUE_TYPE,
                                "操作符 %s 在列 '%s' 上需要恰好一个值，实际收到 %d 个"
                                        .formatted(operator, condition.column(), values.size()));
                    }
                }
            }
        }
    }

    private boolean isNumeric(ResolvedColumn column) {
        return Number.class.isAssignableFrom(column.field().getDataType().getType());
    }
}
