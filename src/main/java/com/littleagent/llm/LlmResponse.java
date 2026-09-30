package com.littleagent.llm;

import java.util.List;

/**
 * 一次 LLM 调用的原始结果（解析前的「模型输出」）。
 *
 * @param content          正文（可能是最终答案，也可能是工具调用前的说明文字）
 * @param reasoning        思维链 reasoning_content（DeepSeek-R1 等推理模型会返回）
 * @param toolCalls        原生 tool_calls
 * @param finishReason     stop / tool_calls / length
 * @param usage            token 用量
 * @param rawResponseBody  原始响应体（trace 用，默认不落盘以免日志过大）
 */
public record LlmResponse(
        String content,
        String reasoning,
        List<ToolCall> toolCalls,
        String finishReason,
        Usage usage,
        String rawResponseBody) {

    public LlmResponse {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        usage = usage == null ? Usage.ZERO : usage;
    }

    public static LlmResponse text(String content) {
        return new LlmResponse(content, null, List.of(), "stop", Usage.ZERO, null);
    }

    public static LlmResponse withToolCalls(String content, List<ToolCall> calls) {
        return new LlmResponse(content, null, calls, "tool_calls", Usage.ZERO, null);
    }

    public boolean hasNativeToolCalls() {
        return !toolCalls.isEmpty();
    }
}
