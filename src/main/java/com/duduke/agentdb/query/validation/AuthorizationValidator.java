package com.duduke.agentdb.query.validation;

import com.duduke.agentdb.error.AgentToolException;
import com.duduke.agentdb.error.ToolErrorCode;
import com.duduke.agentdb.policy.ResolvedColumn;
import com.duduke.agentdb.policy.ResolvedTable;
import com.duduke.agentdb.query.dsl.Condition;
import com.duduke.agentdb.query.dsl.OrderItem;
import com.duduke.agentdb.query.dsl.QueryRequest;
import com.duduke.agentdb.query.dsl.SelectItem;
import org.springframework.stereotype.Component;

import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 第一层：授权校验。
 * <p>
 * 回答「这个标识符到底能不能碰」。所有列都必须先在策略里登记过 ——
 * 这就是「默认拒绝」真正落地的地方：没写进 YAML 的列，模型怎么拼都进不来。
 */
@Component
public class AuthorizationValidator {

    public void validate(ResolvedTable table, QueryRequest request) {
        if (!table.queryable()) {
            throw new AgentToolException(ToolErrorCode.TABLE_NOT_QUERYABLE,
                    "表 '%s' 只对 agent 开放了结构说明，不允许查询数据".formatted(table.name()));
        }
        for (SelectItem item : request.select()) {
            ResolvedColumn column = requireColumn(table, item.column());
            if (!column.selectable()) {
                throw new AgentToolException(ToolErrorCode.COLUMN_NOT_SELECTABLE,
                        "列 '%s' 不能被查询输出。表 '%s' 当前可输出的列：%s"
                                .formatted(item.column(), table.name(), columnsWith(table, ResolvedColumn::selectable)));
            }
        }
        for (Condition condition : request.where()) {
            ResolvedColumn column = requireColumn(table, condition.column());
            if (!column.filterable()) {
                throw new AgentToolException(ToolErrorCode.COLUMN_NOT_FILTERABLE,
                        "列 '%s' 不能作为过滤条件。表 '%s' 当前可筛选的列：%s"
                                .formatted(column.name(), table.name(), columnsWith(table, ResolvedColumn::filterable)));
            }
        }
        for (String groupBy : request.groupBy()) {
            ResolvedColumn column = requireColumn(table, groupBy);
            if (!column.groupable()) {
                throw new AgentToolException(ToolErrorCode.COLUMN_NOT_GROUPABLE,
                        "列 '%s' 不能用于分组。表 '%s' 当前可分组的列：%s"
                                .formatted(groupBy, table.name(), columnsWith(table, ResolvedColumn::groupable)));
            }
        }
        for (OrderItem order : request.orderBy()) {
            ResolvedColumn column = requireColumn(table, order.column());
            if (!column.sortable()) {
                throw new AgentToolException(ToolErrorCode.COLUMN_NOT_SORTABLE,
                        "列 '%s' 不能用于排序。表 '%s' 当前可排序的列：%s"
                                .formatted(order.column(), table.name(), columnsWith(table, ResolvedColumn::sortable)));
            }
        }
    }

    /**
     * 找不到列时统一报 COLUMN_NOT_VISIBLE，不区分「列不存在」与「列未授权」。
     * 一旦区分，agent 就能靠错误码差异把表结构枚举出来。
     */
    private ResolvedColumn requireColumn(ResolvedTable table, String columnName) {
        ResolvedColumn column = table.findColumn(columnName);
        if (column == null) {
            throw new AgentToolException(ToolErrorCode.COLUMN_NOT_VISIBLE,
                    "列 '%s' 不在表 '%s' 的可访问范围内。当前可访问的列：%s"
                            .formatted(columnName, table.name(), columnsWith(table, c -> true)));
        }
        return column;
    }

    private String columnsWith(ResolvedTable table, Predicate<ResolvedColumn> capability) {
        String joined = table.orderedColumns().values().stream()
                .filter(capability)
                .map(ResolvedColumn::name)
                .collect(Collectors.joining("、"));
        return joined.isEmpty() ? "（无）" : joined;
    }
}
