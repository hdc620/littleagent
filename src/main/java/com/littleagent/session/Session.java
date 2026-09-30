package com.littleagent.session;

import com.littleagent.llm.Message;
import com.littleagent.trace.Tracer;
import com.littleagent.util.TokenEstimator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 一个会话窗口（session）。
 *
 * <p>隔离单位：一个 Session 拥有独立的
 * <ul>
 *   <li><b>消息历史</b>（含工具调用与工具结果）；</li>
 *   <li><b>工作记忆</b>（待办、事实）；</li>
 *   <li><b>压缩摘要与压缩位点</b>；</li>
 *   <li><b>trace</b>。</li>
 * </ul>
 * 因此「用户 A 的窗口 1」和「窗口 2」天然互不影响：它们只是两个 Session 对象。
 *
 * <p>并发：所有状态变更走同一把锁。同一个 Session 被并发提问时不会串历史，
 * 不同 Session 之间则完全并行（锁不共享）。
 */
public final class Session {

    private final String id;
    private final String userId;
    private final Tracer tracer;
    private final Instant createdAt;
    private final AtomicLong seq = new AtomicLong();
    private final ReentrantLock lock = new ReentrantLock();

    private final List<Message> history = new ArrayList<>();
    private final WorkingMemory memory = new WorkingMemory();

    private volatile String title;
    private volatile Instant updatedAt;
    /** 历史压缩摘要（长期记忆），由 ContextManager 维护。 */
    private volatile String summary = "";
    /** history[0, summarizedUpTo) 已被 summary 覆盖。 */
    private volatile int summarizedUpTo = 0;
    private volatile int turnCount = 0;
    /** 最近一次 LLM 调用真实上报的 prompt_tokens —— 压缩触发使用「真实值优先」策略。 */
    private volatile int lastPromptTokens = 0;

    Session(String id, String userId, String title, Tracer tracer) {
        this.id = id;
        this.userId = userId;
        this.title = title == null || title.isBlank() ? "新会话" : title;
        this.tracer = tracer == null ? Tracer.noop() : tracer;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public String id() {
        return id;
    }

    public String userId() {
        return userId;
    }

    public String title() {
        return title;
    }

    public void title(String title) {
        if (title != null && !title.isBlank()) {
            this.title = title;
        }
    }

    public Tracer tracer() {
        return tracer;
    }

    public WorkingMemory memory() {
        return memory;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public int turnCount() {
        return turnCount;
    }

    public String summary() {
        return summary;
    }

    public int summarizedUpTo() {
        return summarizedUpTo;
    }

    /**
     * 最近一次请求的真实输入 token（来自 API usage.prompt_tokens）。
     *
     * <p>启发式估算难免偏差，而 API 返回的 prompt_tokens 是精确值：压缩阈值判断优先信它，
     * 估算值只作为「还没有真实数据时」的兜底。
     *
     * <p><b>注意它必须随上下文缩短而失效</b>：这是一个「只增不减」的字段，如果压缩之后不清掉，
     * 一次大 prompt 会让 signal 永远停在峰值，于是**每一轮都白付一次摘要调用**
     * （见 {@link #clearPromptTokensSignal()}）。
     */
    public int lastPromptTokens() {
        return lastPromptTokens;
    }

    public void lastPromptTokens(int tokens) {
        if (tokens > 0) {
            this.lastPromptTokens = tokens;
        }
    }

    /**
     * 让上一次的真实 prompt_tokens 失效（压缩成功后调用）。
     *
     * <p>压缩改变了上下文形状，之前那次测量不再代表当前上下文；不清零的话
     * {@code max(估算, lastPromptTokens)} 会被过期的峰值长期顶住，导致每轮都触发压缩。
     * 清零后由下一次 LLM 调用重新写入精确值，属于自校正。
     */
    public void clearPromptTokensSignal() {
        this.lastPromptTokens = 0;
    }

    /** 追加消息并打上会话流水号；user 消息会推进轮次计数。 */
    public Message append(Message message) {
        lock.lock();
        try {
            Message stamped = message.withSeq(seq.incrementAndGet());
            history.add(stamped);
            if (stamped.isUser()) {
                turnCount++;
            }
            updatedAt = Instant.now();
            return stamped;
        } finally {
            lock.unlock();
        }
    }

    public List<Message> history() {
        lock.lock();
        try {
            return List.copyOf(history);
        } finally {
            lock.unlock();
        }
    }

    public int historySize() {
        lock.lock();
        try {
            return history.size();
        } finally {
            lock.unlock();
        }
    }

    /** 从 index 开始的历史切片（越界返回空列表）。 */
    public List<Message> historyFrom(int index) {
        lock.lock();
        try {
            if (index >= history.size()) {
                return List.of();
            }
            int from = Math.max(0, index);
            return List.copyOf(history.subList(from, history.size()));
        } finally {
            lock.unlock();
        }
    }

    public void updateSummary(String newSummary, int newSummarizedUpTo) {
        lock.lock();
        try {
            this.summary = newSummary == null ? "" : newSummary;
            this.summarizedUpTo = Math.max(0, newSummarizedUpTo);
            this.updatedAt = Instant.now();
        } finally {
            lock.unlock();
        }
    }

    public int estimatedTokens() {
        return estimatedTokensFrom(0);
    }

    /**
     * 估算 token，但**只统计 {@code [fromIndex, history.size())} 这部分历史**。
     *
     * <p>为什么需要它：压缩只是「把 {@code history[0, summarizedUpTo)} 的内容折进 summary」，
     * 原始消息仍留在 history 里（保留可召回性）。如果估算把整条 history 都算上，
     * 那些已经被摘要覆盖、**永远不会再进请求**的消息就会被重复计数 ——
     * 后果是估算系统性偏高、压缩后估算反而变大，而且只要原始历史超过预算就每轮都触发压缩。
     * 所以压缩阈值的估算必须从 {@code summarizedUpTo} 开始算。
     */
    public int estimatedTokensFrom(int fromIndex) {
        lock.lock();
        try {
            int total = TokenEstimator.estimate(summary) + memory.estimatedTokens();
            int from = Math.max(0, Math.min(fromIndex, history.size()));
            for (int i = from; i < history.size(); i++) {
                total += history.get(i).estimatedTokens();
            }
            return total;
        } finally {
            lock.unlock();
        }
    }

    /** 在会话锁内执行一段逻辑（压缩需要「读-改-写」原子化）。 */
    public <T> T withLock(Supplier<T> body) {
        lock.lock();
        try {
            return body.get();
        } finally {
            lock.unlock();
        }
    }

    /** 供 CLI 展示的一行摘要。 */
    public String brief() {
        return String.format("[%s] %s | 用户=%s | 轮次=%d | 历史=%d 条 | %s",
                id, title, userId, turnCount, historySize(),
                summarizedUpTo > 0 ? "已压缩前 " + summarizedUpTo + " 条" : "未压缩");
    }
}
