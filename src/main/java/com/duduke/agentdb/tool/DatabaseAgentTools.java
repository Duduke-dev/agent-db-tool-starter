package com.duduke.agentdb.tool;

import com.duduke.agentdb.error.AgentToolException;
import com.duduke.agentdb.error.ToolErrorCode;
import com.duduke.agentdb.policy.PolicyRepository;
import com.duduke.agentdb.policy.model.ResolvedTable;
import com.duduke.agentdb.query.AgentQueryService;
import com.duduke.agentdb.query.dsl.QueryRequest;
import com.duduke.agentdb.tool.dto.ColumnDescriptor;
import com.duduke.agentdb.tool.dto.QueryOutcome;
import com.duduke.agentdb.tool.dto.TableDescriptor;
import com.duduke.agentdb.tool.dto.TableSummary;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 暴露给模型的三个工具。
 * <p>
 * 这些 description 不是装饰，而是设计的一部分：把「不能 JOIN」「聚合要配 groupBy」
 * 这些边界直接写在工具说明里，模型第一次调用就能避开，而不是撞一次墙、
 * 收到一个错误码、再试一次。边界写清楚比错误信息写清楚更省往返。
 */
@Component
public class DatabaseAgentTools {

    private final PolicyRepository repository;
    private final AgentQueryService queryService;

    public DatabaseAgentTools(PolicyRepository repository, AgentQueryService queryService) {
        this.repository = repository;
        this.queryService = queryService;
    }

    @Tool(name = "list_tables", description = """
            列出当前可访问的数据库表，返回表名、用途说明和可访问的列数量。
            这里只给出表名，不含列结构；要了解某张表有哪些列、能否筛选或排序，请接着调用 describe_table。
            """)
    public List<TableSummary> listTables() {
        return repository.visibleTableOverviews().stream()
                .map(overview -> new TableSummary(overview.name(), overview.description(), overview.columnCount()))
                .toList();
    }

    @Tool(name = "describe_table", description = """
            查看一张或多张表的完整可访问列结构：列名、类型、业务含义，以及四个能力位。
            selectable 表示能否作为输出列，filterable 表示能否作为过滤条件，
            sortable 表示能否排序，groupable 表示能否分组。
            可以在一次调用里传多张表名批量查看，能省下往返；不在可访问范围内的表会被跳过。
            构造 query 参数之前请先调用本工具确认列名与能力位。
            """)
    public List<TableDescriptor> describeTable(
            @ToolParam(description = "表名列表，取值来自 list_tables 的返回结果") List<String> tables) {

        List<String> requested = normalize(tables);
        int max = repository.limits().maxTablesPerDescribe();
        if (requested.size() > max) {
            throw new AgentToolException(ToolErrorCode.TOO_MANY_TABLES,
                    "一次最多查看 %d 张表，本次收到 %d 张，请分批调用".formatted(max, requested.size()));
        }

        List<TableDescriptor> descriptors = requested.stream()
                .map(repository::findOrNull)
                .filter(Objects::nonNull)
                .map(this::toDescriptor)
                .toList();

        if (descriptors.isEmpty()) {
            // 一张都没拿到时，借 require() 抛出标准错误（里面带着「当前可访问的表」清单），
            // 而不是返回空列表让模型自己猜是拼错了还是没权限
            repository.require(requested.getFirst());
        }
        return descriptors;
    }

    /** 去掉空值、去重，并保持传入顺序。 */
    private List<String> normalize(List<String> tables) {
        if (tables == null) {
            throw new AgentToolException(ToolErrorCode.INVALID_IDENTIFIER, "至少要指定一张表");
        }
        List<String> cleaned = tables.stream()
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList();
        if (cleaned.isEmpty()) {
            throw new AgentToolException(ToolErrorCode.INVALID_IDENTIFIER, "至少要指定一张表");
        }
        return cleaned;
    }

    private TableDescriptor toDescriptor(ResolvedTable resolved) {
        List<ColumnDescriptor> columns = resolved.orderedColumns().values().stream()
                .map(column -> new ColumnDescriptor(
                        column.name(),
                        column.typeName(),
                        column.description(),
                        column.selectable(),
                        column.filterable(),
                        column.sortable(),
                        column.groupable()))
                .toList();
        return new TableDescriptor(resolved.name(), resolved.description(), columns);
    }

    @Tool(name = "query", description = """
            以结构化条件查询数据库表并返回行数据。
            可以一次提交多条查询，每条独立执行、独立返回：某条失败不会影响其他条，
            失败的那条会带着 errorCode 和 errorMessage 一起返回，看错误码就知道该改什么。
            限制说明：不支持 JOIN、子查询和 UNION，一条查询只能针对一张表；
            表名与列名必须来自 list_tables / describe_table；
            select 中一旦出现聚合（COUNT/SUM/AVG/MIN/MAX），未被聚合的列必须同时出现在 groupBy 中；
            limit 超过策略上限会被拒绝，如需统计总量请改用 groupBy 加 COUNT。
            返回结果受行数与大小上限约束，被截断时 truncated 为 true。
            """)
    public List<QueryOutcome> query(
            @ToolParam(description = "查询请求列表，每个元素查一张表") List<QueryRequest> requests) {

        List<QueryRequest> normalized = normalizeRequests(requests);
        int max = repository.limits().maxQueriesPerCall();
        if (normalized.size() > max) {
            throw new AgentToolException(ToolErrorCode.TOO_MANY_QUERIES,
                    "一次最多提交 %d 条查询，本次收到 %d 条，请分批调用".formatted(max, normalized.size()));
        }

        List<QueryOutcome> outcomes = new ArrayList<>(normalized.size());
        for (QueryRequest request : normalized) {
            try {
                outcomes.add(QueryOutcome.success(queryService.query(request)));
            } catch (AgentToolException ex) {
                // 设计内的拒绝（越权 / 参数不合法 / 语义不成立）作为这一条的结果带回去，不影响其他条。
                // 其他 RuntimeException 不在这里吞掉 —— 那是 bug，应该冒泡暴露出来。
                outcomes.add(QueryOutcome.failure(request.table(), ex.code(), ex.getMessage()));
            }
        }
        return outcomes;
    }

    private List<QueryRequest> normalizeRequests(List<QueryRequest> requests) {
        if (requests == null) {
            throw new AgentToolException(ToolErrorCode.INVALID_IDENTIFIER, "至少要提交一条查询");
        }
        List<QueryRequest> cleaned = requests.stream().filter(Objects::nonNull).toList();
        if (cleaned.isEmpty()) {
            throw new AgentToolException(ToolErrorCode.INVALID_IDENTIFIER, "至少要提交一条查询");
        }
        return cleaned;
    }
}
