package com.littleagent.context;

import com.littleagent.llm.Message;
import com.littleagent.trace.Tracer;

import java.util.List;

/**
 * 历史压缩器。输入「已有摘要 + 待压缩消息 + 本次调用的 tracer」，输出「合并后的新摘要」。
 *
 * <p>两种实现：
 * <ul>
 *   <li>{@link LlmSummarizer}：用真实 LLM 做有损但高质量的摘要（带确定性降级）；</li>
 *   <li>{@link DeterministicSummarizer}：纯代码抽取（用户诉求 + 工具结论），永不失败。</li>
 * </ul>
 * 压缩失败绝不能让对话中断 —— 这是 Runtime 的硬约束。
 *
 * <p><b>为什么签名里有 {@link Tracer}</b>：压缩本身要调一次 LLM（真实模式下是一次网络请求，
 * 慢的时候 1s 以上）。早期实现传的是 {@code Tracer.noop()}，于是这次调用**既不产生 trace 事件、
 * usage 也被丢掉** —— 实测一次 10 轮对话里 19 次真实 API 调用有 6 次在 trace 里查不到，
 * 「全链路可观测」和「成本上界」两个承诺都不成立。现在由调用方把会话级 tracer 传进来，
 * 压缩调用与主循环调用在 trace 里同等可见（含 prompt/completion tokens）。
 */
public interface Summarizer {

    /**
     * @param previousSummary       已有摘要（增量合并的基准），可为空
     * @param messagesToCompress    待压缩的消息区间
     * @param tracer                会话级 tracer；实现不得依赖它非空（{@code null} 视为 noop）
     */
    String summarize(String previousSummary, List<Message> messagesToCompress, Tracer tracer);

    default String name() {
        return getClass().getSimpleName();
    }
}
