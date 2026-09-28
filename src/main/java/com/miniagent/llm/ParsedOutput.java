package com.miniagent.llm;

import java.util.List;

/**
 * LLM 原始输出经解析后的结构化结果 —— Runtime 判断「继续 loop 还是收尾」的唯一依据。
 *
 * @param thought     思维过程（reasoning_content 或正文里抽出的 Thought 段）
 * @param finalAnswer 最终答案；有工具调用时为 null
 * @param toolCalls   本轮要执行的工具调用
 * @param mode        解析来源，用于 trace 与测试断言
 */
public record ParsedOutput(String thought, String finalAnswer, List<ToolCall> toolCalls, Mode mode) {

    public enum Mode {
        /** 模型通过原生 tool_calls 字段发起调用 */
        NATIVE_TOOL_CALLS,
        /** 模型把工具调用写在正文里（JSON / ReAct 文本），由 Runtime 兜底解析出来 */
        TEXT_TOOL_CALLS,
        /** 最终答案 */
        FINAL_ANSWER,
        /** 空响应 */
        EMPTY
    }

    public ParsedOutput {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static ParsedOutput finalAnswer(String thought, String answer) {
        return new ParsedOutput(thought, answer, List.of(), Mode.FINAL_ANSWER);
    }

    public static ParsedOutput toolCalls(String thought, List<ToolCall> calls, Mode mode) {
        return new ParsedOutput(thought, null, calls, mode);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    public boolean hasFinalAnswer() {
        return finalAnswer != null && !finalAnswer.isBlank();
    }
}
