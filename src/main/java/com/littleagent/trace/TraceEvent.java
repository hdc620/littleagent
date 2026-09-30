package com.littleagent.trace;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条 trace 事件。Runtime 的每一步（上下文组装、LLM 调用、工具执行、压缩、记忆召回）
 * 都产出一条事件，用于「解释 Agent 为什么这么做」。
 *
 * @param seq        会话内自增序号
 * @param at         发生时间
 * @param sessionId  所属 session
 * @param type       事件类型，见 {@link TraceTypes}
 * @param summary    一行摘要（人读）
 * @param data       结构化明细（机读，JSON 可序列化）
 * @param durationMs 耗时（毫秒），非耗时事件为 0
 */
public record TraceEvent(
        long seq,
        Instant at,
        String sessionId,
        String type,
        String summary,
        Map<String, Object> data,
        long durationMs) {

    public TraceEvent {
        data = data == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(data));
    }

    /** 一行 JSONL 友好的可读形式。 */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(seq).append("] ").append(type);
        if (durationMs > 0) {
            sb.append(" (").append(durationMs).append("ms)");
        }
        if (summary != null && !summary.isBlank()) {
            sb.append(" | ").append(summary);
        }
        if (!data.isEmpty()) {
            sb.append(" | ").append(data);
        }
        return sb.toString();
    }
}
