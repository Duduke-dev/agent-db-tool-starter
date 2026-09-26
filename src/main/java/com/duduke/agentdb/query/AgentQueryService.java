package com.duduke.agentdb.query;

import com.duduke.agentdb.policy.PolicyRepository;
import com.duduke.agentdb.query.dsl.QueryRequest;
import org.jooq.Record;
import org.jooq.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 查询的执行入口：编译 -> 执行 -> 截断。
 * <p>
 * 把这条链收口在一个地方，是为了让「每一次查询都必然经过全部校验」成为结构性保证，
 * 而不是靠每个调用点自觉记得。
 */
@Service
public class AgentQueryService {

    private static final Logger log = LoggerFactory.getLogger(AgentQueryService.class);

    private final QueryCompiler compiler;
    private final PolicyRepository repository;

    public AgentQueryService(QueryCompiler compiler, PolicyRepository repository) {
        this.compiler = compiler;
        this.repository = repository;
    }

    public QueryResult query(QueryRequest request) {
        CompiledQuery compiled = compiler.compile(request);

        long started = System.nanoTime();
        Result<Record> records = compiled.query().fetch();
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        boolean truncated = records.size() > compiled.effectiveLimit();
        int rowCount = Math.min(records.size(), compiled.effectiveLimit());
        long maxBytes = repository.limits().maxResultBytes();

        List<Map<String, Object>> rows = new ArrayList<>(rowCount);
        long bytes = 0;
        for (int i = 0; i < rowCount; i++) {
            Record record = records.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            // 按位置取值而不是按列名：默认输出列用的是数据库原始列名（H2 里是大写），
            // 按名取会因大小写不一致而失败。位置与编译时的 select 顺序严格一一对应，无歧义。
            for (int c = 0; c < compiled.outputLabels().size(); c++) {
                Object value = record.get(c);
                row.put(compiled.outputLabels().get(c), value);
                bytes += estimateBytes(value);
            }
            if (bytes > maxBytes) {
                // 字节上限比行数上限更早触发：一行里有超长文本列时，行数限制是拦不住的
                truncated = true;
                break;
            }
            rows.add(row);
        }

        log.info("查询 | table={} rows={} truncated={} elapsed={}ms",
                compiled.table(), rows.size(), truncated, elapsedMillis);

        return new QueryResult(
                compiled.table(),
                compiled.outputLabels(),
                rows,
                rows.size(),
                truncated,
                truncated ? "结果已达行数或大小上限，仅返回了前面部分。请增加过滤条件，或用 groupBy 聚合后再查询。" : null);
    }

    private long estimateBytes(Object value) {
        if (value == null) {
            return 4;
        }
        if (value instanceof Number) {
            return 8;
        }
        return String.valueOf(value).getBytes(StandardCharsets.UTF_8).length;
    }
}
