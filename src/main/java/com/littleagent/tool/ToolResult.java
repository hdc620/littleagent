package com.littleagent.tool;

/**
 * 工具执行结果。
 *
 * <p>无论成功失败都会作为 observation 回灌给模型：失败时模型能读到错误原因并自我修正。
 * 只有「未知工具」这类 Runtime 级错误才需要额外提醒模型可用工具列表（由 ToolInvoker 附加）。
 *
 * @param ok        是否成功
 * @param content   结果正文（成功=数据，失败=可读的错误说明）
 * @param errorCode 失败时的稳定错误码（成功为 null）
 */
public record ToolResult(boolean ok, String content, String errorCode) {

    public static ToolResult ok(String content) {
        return new ToolResult(true, content == null ? "" : content, null);
    }

    public static ToolResult error(String errorCode, String message) {
        return new ToolResult(false, message == null ? "工具执行失败" : message, errorCode);
    }

    /** 回灌给模型时统一加前缀，便于模型（和我们自己）一眼看出这是失败结果。 */
    public String toObservation() {
        return ok ? content : "[TOOL_ERROR] " + content;
    }
}
