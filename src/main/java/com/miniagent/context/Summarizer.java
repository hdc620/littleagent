package com.miniagent.context;

import com.miniagent.llm.Message;

import java.util.List;

/**
 * 历史压缩器。输入「已有摘要 + 待压缩消息」，输出「合并后的新摘要」。
 *
 * <p>两种实现：
 * <ul>
 *   <li>{@link LlmSummarizer}：用真实 LLM 做有损但高质量的摘要（带确定性降级）；</li>
 *   <li>{@link DeterministicSummarizer}：纯代码抽取（用户诉求 + 工具结论），永不失败。</li>
 * </ul>
 * 压缩失败绝不能让对话中断 —— 这是 Runtime 的硬约束。
 */
public interface Summarizer {

    String summarize(String previousSummary, List<Message> messagesToCompress);

    default String name() {
        return getClass().getSimpleName();
    }
}
