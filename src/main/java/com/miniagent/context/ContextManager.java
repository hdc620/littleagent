package com.miniagent.context;

import com.miniagent.llm.Message;
import com.miniagent.llm.Role;
import com.miniagent.llm.ToolSpec;
import com.miniagent.session.Session;
import com.miniagent.trace.TraceTypes;
import com.miniagent.trace.Tracer;
import com.miniagent.util.Texts;
import com.miniagent.util.TokenEstimator;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文管理器：Agent 的「记忆系统」实现。
 *
 * <h2>每次调用 LLM 前，上下文是怎么拼出来的</h2>
 * <pre>
 *  ┌─ system: 角色与规则（ReAct 约束、工具使用纪律）
 *  ├─ system: 【长期记忆】历史压缩摘要        ← 有则必带（不随窗口滑动丢失）
 *  ├─ system: 【工作记忆】待办/关键事实        ← 有则必带（结构化，体量小）
 *  ├─ system: 【相关历史片段】Top-K 召回       ← 与当前问题相关才带（情节记忆）
 *  └─ 对话消息: 最近 keepRecentTurns 轮原文（保序、完整轮次切分）
 *       └─ 本轮（最后一条 user 之后）保持原样；历史轮做「思考截断 / 工具结果收紧」
 * </pre>
 *
 * <h2>记忆的写入与召回时机</h2>
 * <ul>
 *   <li><b>写入</b>：用户输入与模型输出每轮追加进 session.history；工具把关键结论写入
 *       session.workingMemory（结构化，如待办）；超过预算的旧历史被压缩进 session.summary。</li>
 *   <li><b>召回</b>：只发生在 build() —— 即每次请求 LLM 之前。召回 query = 本轮用户输入。
 *       摘要与工作记忆是「无条件注入」，历史片段是「按相关度注入」。</li>
 *   <li><b>放置</b>：全部以 system 消息放在最前面，对话原文保序放在后面。
 *       这样既满足 OpenAI 对 tool 消息必须紧跟 assistant(tool_calls) 的顺序约束，
 *       也让模型的注意力优先落在「规则 + 记忆」上。</li>
 * </ul>
 *
 * <h2>为什么这样设计</h2>
 * <ul>
 *   <li>把「思考过程」从历史上下文中剔除：思维链只对本轮有价值，回灌历史既烧钱又干扰推理；</li>
 *   <li>按<b>完整轮次</b>切窗口：assistant(tool_calls) 与 tool 消息必须成对出现，从中间切断会导致 API 400；</li>
 *   <li>压缩带「压缩位点」：summary 覆盖 history[0, summarizedUpTo)，可以增量合并，不会重复压缩。</li>
 * </ul>
 */
public final class ContextManager {

    /** 工作记忆块标题（测试与文档共用常量）。 */
    public static final String WORKING_MEMORY_HEADER = "【工作记忆｜结构化状态，每轮都会带上，请不要丢失】";
    public static final String SUMMARY_HEADER = "【长期记忆｜历史对话摘要】";

    private final Summarizer summarizer;
    private final MemoryRecaller recaller;
    private final int maxContextTokens;
    private final int keepRecentTurns;
    private final int recallTopK;
    private final int maxAssistantContentChars;
    private final int maxHistoricalToolChars;
    /** 每轮固定开销的估算（system prompt + 工具 Schema）。工具 Schema 往往上千 token，漏算会导致压缩触发过晚。 */
    private final int staticOverheadTokens;

    public ContextManager(Summarizer summarizer, int maxContextTokens, int keepRecentTurns, int recallTopK) {
        this(summarizer, maxContextTokens, keepRecentTurns, recallTopK, 400, 800, 0);
    }

    public ContextManager(Summarizer summarizer, int maxContextTokens, int keepRecentTurns, int recallTopK,
                          int maxAssistantContentChars, int maxHistoricalToolChars) {
        this(summarizer, maxContextTokens, keepRecentTurns, recallTopK, maxAssistantContentChars,
                maxHistoricalToolChars, 0);
    }

    public ContextManager(Summarizer summarizer, int maxContextTokens, int keepRecentTurns, int recallTopK,
                          int maxAssistantContentChars, int maxHistoricalToolChars, int staticOverheadTokens) {
        this.summarizer = summarizer == null ? new DeterministicSummarizer() : summarizer;
        this.recaller = new MemoryRecaller();
        this.maxContextTokens = Math.max(500, maxContextTokens);
        this.keepRecentTurns = Math.max(1, keepRecentTurns);
        this.recallTopK = Math.max(0, recallTopK);
        this.maxAssistantContentChars = Math.max(80, maxAssistantContentChars);
        this.maxHistoricalToolChars = Math.max(100, maxHistoricalToolChars);
        this.staticOverheadTokens = Math.max(0, staticOverheadTokens);
    }

    // ------------------------------------------------------------------ build

    public ContextPackage build(Session session, String systemPrompt, List<ToolSpec> tools) {
        List<Message> history = session.history();
        Tracer tracer = session.tracer();
        List<Message> assembled = new ArrayList<>(history.size() + 4);
        assembled.add(Message.system(systemPrompt));

        int summaryTokens = 0;
        String summary = session.summary();
        if (!Texts.isBlank(summary)) {
            String block = SUMMARY_HEADER + "\n" + summary.strip();
            assembled.add(Message.system(block));
            summaryTokens = TokenEstimator.estimate(block);
        }

        int workingMemoryTokens = 0;
        String memory = session.memory().render();
        if (!Texts.isBlank(memory)) {
            String block = WORKING_MEMORY_HEADER + "\n" + memory;
            assembled.add(Message.system(block));
            workingMemoryTokens = TokenEstimator.estimate(block);
        }

        int turnStart = lastUserIndex(history);
        int verbatimStart = verbatimWindowStart(history, keepRecentTurns);
        int recallUpTo = Math.min(verbatimStart, turnStart < 0 ? history.size() : turnStart);
        String query = turnStart >= 0 ? history.get(turnStart).content() : "";

        List<Message> recallPool = recallUpTo > 0 ? history.subList(0, recallUpTo) : List.of();
        List<Message> recalled = recaller.recall(recallPool, query, recallTopK);
        if (!recalled.isEmpty()) {
            assembled.add(Message.system(recaller.render(recalled)));
            tracer.event(TraceTypes.MEMORY_RECALL,
                    "召回 " + recalled.size() + " 条历史片段（候选池 " + recallPool.size() + " 条）",
                    Tracer.data("query", Texts.oneLine(query, 60),
                            "recalledSeqs", recalled.stream().map(Message::seq).toList(),
                            "poolSize", recallPool.size()));
        }

        int verbatimCount = 0;
        for (int i = verbatimStart; i < history.size(); i++) {
            boolean live = turnStart < 0 || i >= turnStart;
            assembled.add(trim(history.get(i), live));
            verbatimCount++;
        }

        ContextPackage.ContextStats stats = ContextPackage.ContextStats.of(assembled, history.size(),
                verbatimCount, recalled.size(), history.size() - verbatimCount, summaryTokens, workingMemoryTokens);

        tracer.event(TraceTypes.CONTEXT_BUILT,
                "组装 " + assembled.size() + " 条消息 / 约 " + stats.estimatedTokens() + " tokens",
                Tracer.data("historyMessages", stats.historyMessages(),
                        "verbatimMessages", stats.verbatimMessages(),
                        "recalledFragments", stats.recalledFragments(),
                        "droppedMessages", stats.droppedMessages(),
                        "summaryTokens", stats.summaryTokens(),
                        "workingMemoryTokens", stats.workingMemoryTokens(),
                        "estimatedTokens", stats.estimatedTokens(),
                        "tools", tools == null ? 0 : tools.size()));
        return new ContextPackage(assembled, stats);
    }

    // -------------------------------------------------------------- 压缩策略

    /** 超过预算才压缩。@return 是否真的压缩了 */
    public boolean compactIfNeeded(Session session) {
        if (compactionSignal(session) <= maxContextTokens) {
            return false;
        }
        return compact(session, false);
    }

    /**
     * 压缩触发信号（token）。
     *
     * <p>优先使用 API 上报的真实 prompt_tokens（精确），没有时退回「本地估算 + 固定开销」。
     * 这是踩过坑的修正：只看本地估算会低估工具 Schema 的开销（实测低估约 2 倍），
     * 导致上下文早就超预算却不触发压缩。
     */
    public int compactionSignal(Session session) {
        return Math.max(estimate(session), session.lastPromptTokens());
    }

    /**
     * 把「最近 keepRecentTurns 轮之外」的历史压进摘要。
     *
     * @param force true 时忽略 token 阈值（CLI 的 /compact 用）
     * @return 是否真的压缩了
     */
    public boolean compact(Session session, boolean force) {
        return session.withLock(() -> {
            List<Message> history = session.history();
            int summarizedUpTo = session.summarizedUpTo();
            int keepFrom = verbatimWindowStart(history, keepRecentTurns);
            if (keepFrom <= summarizedUpTo) {
                return false;
            }
            int estimatedBefore = estimate(session);
            int signal = compactionSignal(session);
            if (!force && signal <= maxContextTokens) {
                return false;
            }
            List<Message> toCompress = new ArrayList<>(history.subList(summarizedUpTo, keepFrom));
            Tracer tracer = session.tracer();
            long start = System.nanoTime();
            String merged;
            try {
                merged = summarizer.summarize(session.summary(), toCompress, tracer);
            } catch (RuntimeException e) {
                tracer.failure(TraceTypes.CONTEXT_COMPRESS_FAILED, "摘要生成失败，改用确定性压缩", e);
                merged = new DeterministicSummarizer().summarize(session.summary(), toCompress, tracer);
            }
            if (Texts.isBlank(merged)) {
                merged = session.summary();
            }
            session.updateSummary(merged, keepFrom);
            // 压缩改变了上下文形状：上次测量的真实 prompt_tokens 不再代表当前上下文。
            // 不清掉的话 max(估算, lastPromptTokens) 会被这个过期峰值长期顶住 ——
            // 实测「一次 9000 token 的 prompt」会让之后每一轮都触发压缩（即使估算只剩 538），
            // 每轮白付一次摘要调用。清零后由下一次 LLM 调用写入新的精确值，属于自校正。
            session.clearPromptTokensSignal();
            long costMs = (System.nanoTime() - start) / 1_000_000L;
            int estimatedAfter = estimate(session);
            tracer.event(TraceTypes.CONTEXT_COMPRESSED,
                    "压缩 " + toCompress.size() + " 条历史（" + estimatedBefore + " -> " + estimatedAfter + " tokens）",
                    Tracer.data("summarizer", summarizer.name(),
                            "compressedMessages", toCompress.size(),
                            "summarizedUpTo", keepFrom,
                            "tokensBefore", estimatedBefore,
                            "tokensAfter", estimatedAfter,
                            "shrank", estimatedAfter < estimatedBefore,
                            "promptTokensSignal", signal,
                            "maxContextTokens", maxContextTokens,
                            "summaryChars", merged.length(),
                            "latencyMs", costMs));
            return true;
        });
    }

    /**
     * 当前会话的估算 token = **未压缩的**历史 + 摘要 + 工作记忆 + 固定开销。
     *
     * <p>必须从 {@code summarizedUpTo} 开始统计：{@code history[0, summarizedUpTo)} 已经被折进摘要，
     * 不会再进请求，算两次会让估算系统性偏高（实测表现为「压缩后估算反而变大」，
     * 且原始历史一旦超预算就每轮都触发压缩）。
     */
    public int estimate(Session session) {
        return session.estimatedTokensFrom(session.summarizedUpTo()) + staticOverheadTokens;
    }

    /** 固定开销估算（system prompt + 工具 Schema），测试与文档用。 */
    public int staticOverheadTokens() {
        return staticOverheadTokens;
    }

    public int maxContextTokens() {
        return maxContextTokens;
    }

    public int keepRecentTurns() {
        return keepRecentTurns;
    }

    // ------------------------------------------------------------------ 内部

    /** 最近 keepRecentTurns 轮的起始下标（按 user 消息切完整轮次）。 */
    static int verbatimWindowStart(List<Message> history, int keepRecentTurns) {
        List<Integer> userIndices = new ArrayList<>();
        for (int i = 0; i < history.size(); i++) {
            if (history.get(i).role() == Role.USER) {
                userIndices.add(i);
            }
        }
        if (userIndices.isEmpty()) {
            return 0;
        }
        if (userIndices.size() <= keepRecentTurns) {
            return 0;
        }
        return userIndices.get(userIndices.size() - keepRecentTurns);
    }

    static int lastUserIndex(List<Message> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i).role() == Role.USER) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 历史消息瘦身：本轮保持原样（模型刚说的话与工具结果要完整），
     * 历史轮则截断 assistant 正文（思维过程）与过长的工具结果。
     */
    private Message trim(Message message, boolean live) {
        if (live) {
            return message;
        }
        if (message.role() == Role.ASSISTANT && message.content().length() > maxAssistantContentChars) {
            return message.withContent(Texts.truncate(message.content(), maxAssistantContentChars));
        }
        if (message.role() == Role.TOOL && message.content().length() > maxHistoricalToolChars) {
            return message.withContent(Texts.truncate(message.content(), maxHistoricalToolChars));
        }
        return message;
    }
}
