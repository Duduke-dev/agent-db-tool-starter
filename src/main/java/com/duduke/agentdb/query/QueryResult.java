package com.duduke.agentdb.query;

import java.util.List;
import java.util.Map;

/**
 * 回给模型的查询结果。
 *
 * @param columns   结果列名
 * @param rows      行数据
 * @param rowCount  实际返回行数
 * @param truncated 是否因行数或字节上限被截断
 * @param hint      给模型的下一步建议，例如提示它缩小范围
 */
public record QueryResult(
        String table,
        List<String> columns,
        List<Map<String, Object>> rows,
        int rowCount,
        boolean truncated,
        String hint
) {
}
