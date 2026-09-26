package com.duduke.agentdb.query.validation;

import com.duduke.agentdb.error.AgentToolException;
import com.duduke.agentdb.error.ToolErrorCode;
import com.duduke.agentdb.query.dsl.OrderItem;
import com.duduke.agentdb.query.dsl.QueryRequest;
import com.duduke.agentdb.query.dsl.SelectItem;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 第三层：语义校验。
 * <p>
 * 拦的是「语法合法但 SQL 语义不成立」的请求，这一类恰恰是模型最容易犯的错：
 * SELECT 里混着聚合列和裸列却不写 GROUP BY，数据库会抛一个很难读的错误，
 * 模型拿到之后往往原地重试同样的写法，来回几轮都出不来。
 * <p>
 * 在这里拦住，并把「把哪些列加进 groupBy」直接告诉它，通常一次就能改对。
 * 这是本层存在的全部意义 —— 把数据库的报错翻译成模型能行动的一句话。
 */
@Component
public class SemanticValidator {

    public void validate(QueryRequest request) {
        boolean hasAggregate = request.select().stream().anyMatch(item -> item.aggregate().isAggregated());
        if (!hasAggregate) {
            return;
        }

        Set<String> grouped = request.groupBy().stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        for (SelectItem item : request.select()) {
            if (item.aggregate().isAggregated() || grouped.contains(item.column().toLowerCase(Locale.ROOT))) {
                continue;
            }
            throw new AgentToolException(ToolErrorCode.NON_GROUPED_COLUMN_IN_SELECT,
                    ("查询里使用了聚合函数，但列 '%s' 既没有被聚合，也不在 groupBy 中，SQL 语义不成立。"
                            + "请二选一：把 '%s' 加入 groupBy（当前 groupBy：%s），或把它改成聚合形式。")
                            .formatted(item.column(), item.column(),
                                    request.groupBy().isEmpty() ? "空" : String.join("、", request.groupBy())));
        }

        for (OrderItem order : request.orderBy()) {
            if (grouped.contains(order.column().toLowerCase(Locale.ROOT)) || isAggregatedAlias(request, order)) {
                continue;
            }
            throw new AgentToolException(ToolErrorCode.NON_GROUPED_COLUMN_IN_SELECT,
                    "排序项 '%s' 既不在 groupBy 中，也不是某个聚合结果的别名，无法排序。"
                            .formatted(order.column()));
        }
    }

    /** 允许按聚合结果的别名排序，例如 SUM(amount) AS total 之后 orderBy total。 */
    private boolean isAggregatedAlias(QueryRequest request, OrderItem order) {
        return request.select().stream()
                .anyMatch(item -> item.aggregate().isAggregated()
                        && item.alias().equalsIgnoreCase(order.column()));
    }
}
