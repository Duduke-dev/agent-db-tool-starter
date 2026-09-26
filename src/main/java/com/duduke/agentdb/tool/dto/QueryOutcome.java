package com.duduke.agentdb.tool.dto;

import com.duduke.agentdb.error.ToolErrorCode;
import com.duduke.agentdb.query.QueryResult;

/**
 * 批量查询里单条查询的结果 —— 要么有 {@code result}，要么有错误，两者必有其一。
 * <p>
 * 为什么不干脆整批成功/整批失败：query 的失败原因比「表不可访问」多得多
 * （列没权限、参数不合法、语义不成立、超时……）。简单跳过的话模型不知道错在哪，
 * 只能盲目重试；把每条的状态分别带回去，它一次调用就能看清哪条要改、怎么改。
 *
 * @param result       成功时的查询结果
 * @param errorCode    失败时的错误码，成功时为 null
 * @param errorMessage 失败原因，成功时为 null
 */
public record QueryOutcome(
        String table,
        QueryResult result,
        String errorCode,
        String errorMessage
) {

    public static QueryOutcome success(QueryResult result) {
        return new QueryOutcome(result.table(), result, null, null);
    }

    public static QueryOutcome failure(String table, ToolErrorCode code, String message) {
        return new QueryOutcome(table, null, code.name(), message);
    }

    public boolean succeeded() {
        return errorCode == null;
    }
}
