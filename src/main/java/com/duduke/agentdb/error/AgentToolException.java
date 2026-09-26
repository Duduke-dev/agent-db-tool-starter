package com.duduke.agentdb.error;

/**
 * 工具层拒绝执行时抛出的异常。
 * <p>
 * 消息刻意写成「可读、可行动」的形式，因为这段文字会直接回到模型手里：
 * 与其把 PostgreSQL 的原始报错丢过去，不如说清楚是哪个参数不合法、允许的取值范围是什么。
 */
public class AgentToolException extends RuntimeException {

    private final ToolErrorCode code;

    public AgentToolException(ToolErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ToolErrorCode code() {
        return code;
    }
}
