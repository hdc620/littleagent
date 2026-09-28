package com.miniagent.llm;

import com.miniagent.util.Texts;
import com.miniagent.util.TokenEstimator;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 会话中的一条消息。除了 OpenAI 标准字段外，额外携带 Runtime 自用的元信息：
 *
 * @param reasoning  模型的思维链（reasoning_content）。<b>永不回灌给 API</b>，只用于 UI/trace/记忆召回。
 * @param toolCalls  assistant 消息请求的工具调用
 * @param toolCallId tool 消息对应的调用 id
 * @param toolName   tool 消息对应的工具名（OpenAI 里是可选字段，这里用于日志与召回）
 * @param seq        会话内自增序号，用于窗口切分与时间衰减打分
 * @param at         产生时间
 */
public record Message(
        Role role,
        String content,
        String reasoning,
        List<ToolCall> toolCalls,
        String toolCallId,
        String toolName,
        long seq,
        Instant at) {

    public Message {
        Objects.requireNonNull(role, "role");
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        at = at == null ? Instant.now() : at;
    }

    public static Message system(String content) {
        return new Message(Role.SYSTEM, content, null, List.of(), null, null, 0, Instant.now());
    }

    public static Message user(String content) {
        return new Message(Role.USER, content, null, List.of(), null, null, 0, Instant.now());
    }

    public static Message assistant(String content, String reasoning) {
        return new Message(Role.ASSISTANT, content, reasoning, List.of(), null, null, 0, Instant.now());
    }

    public static Message assistantToolCalls(String content, String reasoning, List<ToolCall> calls) {
        return new Message(Role.ASSISTANT, content, reasoning, calls, null, null, 0, Instant.now());
    }

    public static Message tool(String toolCallId, String toolName, String content) {
        return new Message(Role.TOOL, content, null, List.of(), toolCallId, toolName, 0, Instant.now());
    }

    /** 复制并带上会话流水号。 */
    public Message withSeq(long newSeq) {
        return new Message(role, content, reasoning, toolCalls, toolCallId, toolName, newSeq, at);
    }

    public Message withContent(String newContent) {
        return new Message(role, newContent, reasoning, toolCalls, toolCallId, toolName, seq, at);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    public boolean isUser() {
        return role == Role.USER;
    }

    /** 该消息占用的估算 token（含参数与思维链）。 */
    public int estimatedTokens() {
        int total = TokenEstimator.estimate(content) + TokenEstimator.estimate(reasoning);
        for (ToolCall call : toolCalls) {
            total += TokenEstimator.estimate(call.name()) + TokenEstimator.estimate(call.argumentsJson());
        }
        return total;
    }

    /** 用于 trace / 日志的一行摘要。 */
    public String brief() {
        if (hasToolCalls()) {
            StringBuilder sb = new StringBuilder("assistant -> tools[");
            for (int i = 0; i < toolCalls.size(); i++) {
                sb.append(i > 0 ? ", " : "").append(toolCalls.get(i).name());
            }
            return sb.append(']').toString();
        }
        return role.wireName() + ": " + Texts.oneLine(content, 80);
    }

    @Override
    public String toString() {
        return brief();
    }
}
