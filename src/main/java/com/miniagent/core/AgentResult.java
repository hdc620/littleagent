package com.miniagent.core;

import com.miniagent.llm.ToolCall;
import com.miniagent.llm.Usage;
import com.miniagent.session.Session;

import java.time.Instant;
import java.util.List;

/**
 * 一次用户输入的完整处理结果。CLI / HTTP / 测试都只依赖这个结构。
 *
 * @param sessionId       会话 id
 * @param answer          给用户的最终回答（失败时是错误说明）
 * @param status          结束原因
 * @param steps           循环逐步记录（含每步的思考、工具调用与结果）
 * @param totalUsage      本轮累计 token
 * @param latencyMs       总耗时
 * @param errorCode       失败时的错误码
 */
public record AgentResult(
        String sessionId,
        String answer,
        Status status,
        List<StepRecord> steps,
        Usage totalUsage,
        long latencyMs,
        String errorCode) {

    public enum Status {
        /** 模型给出了最终回答 */
        OK,
        /** 达到最大步数，由 Runtime 强制收尾 */
        MAX_STEPS,
        /** 出现不可恢复错误（LLM 失败 / 上下文组装失败） */
        FAILED
    }

    public AgentResult {
        steps = steps == null ? List.of() : List.copyOf(steps);
        totalUsage = totalUsage == null ? Usage.ZERO : totalUsage;
    }

    public static AgentResult ok(Session session, String answer, List<StepRecord> steps, long latencyMs, Usage usage) {
        return new AgentResult(session.id(), answer, Status.OK, steps, usage, latencyMs, null);
    }

    public static AgentResult maxSteps(Session session, String answer, List<StepRecord> steps, long latencyMs, Usage usage) {
        return new AgentResult(session.id(), answer, Status.MAX_STEPS, steps, usage, latencyMs, "MAX_STEPS_REACHED");
    }

    public static AgentResult failed(String sessionId, String message, List<StepRecord> steps, long latencyMs, Usage usage, String errorCode) {
        return new AgentResult(sessionId, message, Status.FAILED, steps, usage, latencyMs, errorCode);
    }

    public boolean failed() {
        return status == Status.FAILED;
    }

    public boolean ok() {
        return status != Status.FAILED;
    }

    public int toolCallCount() {
        return steps.stream().mapToInt(s -> s.toolCalls().size()).sum();
    }

    /** 一步循环的记录。 */
    public record StepRecord(
            int index,
            Instant at,
            String thought,
            List<ToolCall> toolCalls,
            List<ToolOutcome> outcomes,
            String finalAnswer,
            Usage usage) {

        public StepRecord {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
            at = at == null ? Instant.now() : at;
            usage = usage == null ? Usage.ZERO : usage;
        }

        public boolean isFinal() {
            return finalAnswer != null && !finalAnswer.isBlank();
        }
    }

    /** 一次工具执行的结果摘要（给 CLI / trace 展示）。 */
    public record ToolOutcome(String tool, boolean ok, String errorCode, String resultPreview, long latencyMs) {
    }
}
