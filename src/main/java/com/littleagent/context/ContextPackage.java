package com.littleagent.context;

import com.littleagent.llm.Message;

import java.util.List;

/**
 * 一次上下文组装的产物。
 *
 * @param messages 最终发给 LLM 的消息序列
 * @param stats    组装统计（用于 trace 与测试断言）
 */
public record ContextPackage(List<Message> messages, ContextStats stats) {

    public ContextPackage {
        messages = List.copyOf(messages);
    }

    public int estimatedTokens() {
        return stats.estimatedTokens();
    }

    /** 组装统计：解释「这轮上下文里到底放了什么」。 */
    public record ContextStats(
            int historyMessages,
            int verbatimMessages,
            int recalledFragments,
            int droppedMessages,
            int summaryTokens,
            int workingMemoryTokens,
            int estimatedTokens,
            boolean hasSummary,
            boolean hasWorkingMemory) {

        public static ContextStats of(List<Message> assembled, int historyMessages, int verbatimMessages,
                                      int recalledFragments, int droppedMessages, int summaryTokens,
                                      int workingMemoryTokens) {
            int total = 0;
            for (Message m : assembled) {
                total += m.estimatedTokens();
            }
            boolean hasSummary = summaryTokens > 0;
            boolean hasWorkingMemory = workingMemoryTokens > 0;
            return new ContextStats(historyMessages, verbatimMessages, recalledFragments, droppedMessages,
                    summaryTokens, workingMemoryTokens, total, hasSummary, hasWorkingMemory);
        }
    }
}
