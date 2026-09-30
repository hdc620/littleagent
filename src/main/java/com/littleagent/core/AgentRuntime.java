package com.littleagent.core;

import com.littleagent.context.ContextManager;
import com.littleagent.context.ContextPackage;
import com.littleagent.context.LlmSummarizer;
import com.littleagent.context.Summarizer;
import com.littleagent.llm.LlmClient;
import com.littleagent.llm.LlmException;
import com.littleagent.llm.LlmOutputParser;
import com.littleagent.llm.LlmRequest;
import com.littleagent.llm.LlmResponse;
import com.littleagent.llm.Message;
import com.littleagent.llm.ParsedOutput;
import com.littleagent.llm.ToolCall;
import com.littleagent.llm.Usage;
import com.littleagent.session.Session;
import com.littleagent.session.SessionManager;
import com.littleagent.tool.ToolContext;
import com.littleagent.tool.ToolInvoker;
import com.littleagent.tool.ToolRegistry;
import com.littleagent.tool.ToolResult;
import com.littleagent.tool.impl.DefaultTools;
import com.littleagent.trace.ConsoleTraceSink;
import com.littleagent.trace.JsonlTraceSink;
import com.littleagent.trace.TraceSink;
import com.littleagent.trace.TraceTypes;
import com.littleagent.trace.Tracer;
import com.littleagent.util.Texts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Agent Runtime —— 本项目的核心，自研的 Agent 主循环。
 *
 * <h2>循环步骤（对应题目要求）</h2>
 * <pre>
 *  step 1  接收用户输入            -> run(userId, sessionId, input)，写入 session 历史
 *  step 2  判断直接回复还是调用工具  -> 组装上下文 + 调 LLM + LlmOutputParser 解析
 *  step 3  调用工具                -> ToolInvoker（校验/超时/异常/截断 + trace）
 *  step 4  决定继续 loop 还是收尾   -> 有 tool_calls 则回灌结果继续，否则返回最终答案
 * </pre>
 *
 * <h2>边界与安全网</h2>
 * <ul>
 *   <li>最大步数：达到 {@code maxSteps} 后禁用工具、强制模型收尾；再失败则返回确定性兜底文案；</li>
 *   <li>重复调用保护：同一工具 + 同一参数超过 {@code maxRepeatedToolCalls} 次直接拦截并回灌提示；</li>
 *   <li>空响应修复：模型返回空内容时注入一次系统提醒，连续两次才判定失败；</li>
 *   <li>异常隔离：LLM/上下文/工具异常都被收敛成 {@link AgentResult}，不会把堆栈抛给调用方；</li>
 *   <li>并发语义：同一 session 内串行（保证历史一致），不同 session 完全并行。</li>
 * </ul>
 */
public final class AgentRuntime implements AutoCloseable {

    private final AgentConfig config;
    private final LlmClient llm;
    private final ToolRegistry registry;
    private final ToolInvoker invoker;
    private final SessionManager sessions;
    private final ContextManager contextManager;
    private final AtomicLong runCounter = new AtomicLong();

    private AgentRuntime(Builder b) {
        this.config = b.config;
        this.llm = b.llm;
        this.registry = b.registry;
        this.invoker = new ToolInvoker(registry, config.toolTimeoutMs(), config.maxToolResultChars());
        this.sessions = new SessionManager(sessionId -> buildTracer(config, sessionId));
        this.contextManager = new ContextManager(b.summarizer, config.maxContextTokens(),
                config.keepRecentTurns(), config.recallTopK(),
                config.maxAssistantContentChars(), config.maxHistoricalToolChars(),
                staticOverheadTokens(b.registry, config));
    }

    /**
     * 每轮都会带上的固定开销：system prompt + 全部工具的 Schema。
     *
     * <p>工具 Schema 通常占 1000+ token，如果压缩阈值不算这部分，上下文会「悄悄超预算」。
     */
    private static int staticOverheadTokens(ToolRegistry registry, AgentConfig config) {
        int total = com.littleagent.util.TokenEstimator.estimate(config.systemPrompt());
        for (com.littleagent.llm.ToolSpec spec : registry.specs()) {
            total += com.littleagent.util.TokenEstimator.estimate(spec.name())
                    + com.littleagent.util.TokenEstimator.estimate(spec.description())
                    + com.littleagent.util.TokenEstimator.estimate(String.valueOf(spec.parameters()));
        }
        return total;
    }

    /** 按配置装配完整 Runtime（真实 API 或 mock）。 */
    public static AgentRuntime createDefault(AgentConfig config) {
        LlmClient llm = config.createLlmClient();
        Summarizer summarizer = config.mockMode()
                ? new com.littleagent.context.DeterministicSummarizer()
                : new LlmSummarizer(llm, config.model());
        return builder(config)
                .llm(llm)
                .toolRegistry(DefaultTools.registry(config.knowledgeDir()))
                .summarizer(summarizer)
                .build();
    }

    public static Builder builder(AgentConfig config) {
        return new Builder(config);
    }

    // ------------------------------------------------------------------- API

    /**
     * 处理一次用户输入。
     *
     * @param userId    用户标识（多用户隔离）
     * @param sessionId 会话/窗口标识；null 或空则新建一个会话
     * @param userInput 用户输入
     */
    public AgentResult run(String userId, String sessionId, String userInput) {
        if (Texts.isBlank(userInput)) {
            return AgentResult.failed(sessionId, "输入为空，请提供内容。", List.of(), 0L, Usage.ZERO, "EMPTY_INPUT");
        }
        Session session;
        try {
            session = sessions.getOrCreate(userId, sessionId, deriveTitle(userInput));
        } catch (RuntimeException e) {
            return AgentResult.failed(sessionId, "无法打开会话：" + e.getMessage(), List.of(), 0L, Usage.ZERO, "SESSION_ERROR");
        }
        // 同一会话内串行：保证 history 顺序一致；不同会话使用不同的锁，互不阻塞。
        //
        // 最外层再兜一次异常：run() 的契约是「永不抛给调用方」（只返回 AgentResult）。
        // 锁、executor 生命周期（例如 close() 之后再被调用）这类边界都会在这里被收敛，
        // 否则一次 RejectedExecutionException 会直接穿透到 CLI/HTTP 层。
        try {
            return session.withLock(() -> runLocked(session, userId, userInput));
        } catch (RuntimeException e) {
            session.tracer().failure(TraceTypes.ERROR, "Runtime 出现未预期异常", e);
            return AgentResult.failed(session.id(), "处理这条消息时出现内部错误：" + e.getMessage(),
                    List.of(), 0L, Usage.ZERO, "RUNTIME_ERROR");
        }
    }

    private AgentResult runLocked(Session session, String userId, String userInput) {
        Tracer tracer = session.tracer();
        long startedAt = System.nanoTime();
        long runId = runCounter.incrementAndGet();
        tracer.event(TraceTypes.USER_INPUT, Texts.oneLine(userInput, 80),
                Tracer.data("runId", runId, "userId", userId, "sessionId", session.id(),
                        "historySize", session.historySize(), "llmClient", llm.clientName()));
        session.append(Message.user(userInput));

        List<AgentResult.StepRecord> steps = new ArrayList<>();
        Map<String, Integer> signatureCounts = new HashMap<>();
        Usage totalUsage = Usage.ZERO;
        int emptyResponses = 0;
        // 本轮可用的工具名快照：用于校验「文本兜底解析出来的工具调用」是否真的存在
        Set<String> toolNames = new HashSet<>(registry.names());

        for (int step = 1; step <= config.maxSteps(); step++) {
            // ---- step 2a: 组装上下文（含压缩与记忆召回）----
            ContextPackage context;
            try {
                contextManager.compactIfNeeded(session);
                context = contextManager.build(session, config.systemPrompt(), registry.specs());
            } catch (RuntimeException e) {
                tracer.failure(TraceTypes.ERROR, "上下文组装失败", e);
                return AgentResult.failed(session.id(), "上下文组装失败：" + e.getMessage(), steps,
                        elapsedMs(startedAt), totalUsage, "CONTEXT_ERROR");
            }

            // ---- step 2b: 调用 LLM ----
            LlmResponse response;
            try {
                response = llm.chat(buildRequest(context.messages(), registry.specs()), tracer);
            } catch (LlmException e) {
                tracer.failure(TraceTypes.ERROR, "LLM 调用失败", e);
                return AgentResult.failed(session.id(), "调用模型失败（" + e.code() + "）：" + e.getMessage(),
                        steps, elapsedMs(startedAt), totalUsage, e.code());
            } catch (RuntimeException e) {
                tracer.failure(TraceTypes.ERROR, "LLM 调用出现未预期异常", e);
                return AgentResult.failed(session.id(), "调用模型出现异常：" + e.getMessage(), steps,
                        elapsedMs(startedAt), totalUsage, "LLM_UNEXPECTED_ERROR");
            }
            totalUsage = add(totalUsage, response.usage());
            // 记录真实 prompt_tokens：压缩阈值优先使用这个精确值（见 ContextManager.compactionSignal）
            session.lastPromptTokens(response.usage().promptTokens());

            // ---- step 2c: 解析输出（思考 / 工具调用 / 最终答案）----
            // 传入已注册工具名：文本兜底解析出来的调用必须命中注册表，否则回落成普通正文
            // （否则模型回答里的 {"name": "张三"} 这类 JSON 会被当成工具调用执行）。
            ParsedOutput parsed = LlmOutputParser.parse(response, toolNames);
            tracer.event(TraceTypes.LOOP_STEP, "step " + step + " -> " + parsed.mode(),
                    Tracer.data("step", step, "mode", parsed.mode().name(),
                            "toolCalls", parsed.toolCalls().size(),
                            "hasThought", !Texts.isBlank(parsed.thought()),
                            "hasAnswer", parsed.hasFinalAnswer()));

            // ---- step 3 + 4: 有工具调用则执行并继续循环 ----
            if (parsed.hasToolCalls()) {
                session.append(Message.assistantToolCalls(Texts.oneLine(parsed.thought(), 1200),
                        parsed.thought(), parsed.toolCalls()));
                List<AgentResult.ToolOutcome> outcomes = new ArrayList<>();
                // 工具执行段必须异常隔离，且**保证每个 tool_call_id 都有配对的 tool 消息**：
                // 如果这里抛出去，历史里会留下一条没有 tool 回应的 assistant(tool_calls)，
                // 之后这个 session 的每次请求都会被 API 以 400 拒绝（永久毒化，且无法自愈）。
                int answered = 0;
                try {
                    for (ToolCall call : parsed.toolCalls()) {
                        outcomes.add(executeTool(call, session, userId, tracer, signatureCounts));
                        answered++;
                    }
                } catch (RuntimeException e) {
                    // 兜底路径：ToolInvoker 自己已经把工具故障折叠成结构化结果，
                    // 所以走到这里说明是「工具执行段之外」的问题（例如追加历史失败）。
                    // 无论如何都必须补齐 tool 消息，否则历史里会留下孤儿的 assistant(tool_calls)。
                    tracer.failure(TraceTypes.ERROR, "工具执行段异常，补占位 tool 消息以保住配对不变式", e);
                    appendUnansweredToolPlaceholders(session, parsed.toolCalls(), answered, e);
                    steps.add(new AgentResult.StepRecord(step, java.time.Instant.now(), parsed.thought(),
                            parsed.toolCalls(), outcomes, null, response.usage()));
                    return AgentResult.failed(session.id(), "工具执行阶段出现内部错误：" + e.getMessage(), steps,
                            elapsedMs(startedAt), totalUsage, "TOOL_DISPATCH_ERROR");
                }
                // 这一步产出了有效结果 -> 空响应计数归零（计数器语义是「连续」，不是「累计」）
                emptyResponses = 0;
                steps.add(new AgentResult.StepRecord(step, java.time.Instant.now(), parsed.thought(),
                        parsed.toolCalls(), outcomes, null, response.usage()));
                continue;
            }

            // ---- step 4: 没有工具调用 -> 最终答案，循环结束 ----
            if (parsed.hasFinalAnswer()) {
                String answer = parsed.finalAnswer();
                session.append(Message.assistant(answer, parsed.thought()));
                steps.add(new AgentResult.StepRecord(step, java.time.Instant.now(), parsed.thought(),
                        List.of(), List.of(), answer, response.usage()));
                tracer.event(TraceTypes.FINAL_ANSWER, Texts.oneLine(answer, 100),
                        Tracer.data("steps", step, "answerChars", answer.length(),
                                "latencyMs", elapsedMs(startedAt), "totalTokens", totalUsage.totalTokens()));
                return AgentResult.ok(session, answer, steps, elapsedMs(startedAt), totalUsage);
            }

            // ---- 空响应修复：连续两次才失败（自愈一次）----
            // 注意 emptyResponses 在「有产出的一步」之后必须归零：早期实现漏了归零，
            // 于是统计的是整轮**累计**空响应，导致「空 -> 工具成功 -> 空」这种正常序列被判失败，
            // 而且已经拿到的工具结果被丢弃（与注释/文档写的「连续两次」不符）。
            emptyResponses++;
            tracer.event(TraceTypes.ERROR, "模型返回空内容（连续第 " + emptyResponses + " 次）",
                    Tracer.data("step", step, "emptyResponses", emptyResponses));
            if (emptyResponses >= 2) {
                return AgentResult.failed(session.id(), "模型连续两次返回空内容，请重试或换一个模型。", steps,
                        elapsedMs(startedAt), totalUsage, "EMPTY_MODEL_OUTPUT");
            }
            session.append(Message.assistant("", parsed.thought()));
            session.append(Message.user("[系统] 你上一条回复为空。请直接回答用户的问题，或调用合适的工具。"));
        }

        // ---- 达到最大步数：禁用工具，强制收尾 ----
        return forceWrapUp(session, userInput, steps, totalUsage, startedAt, tracer);
    }

    /**
     * 为「本步中没能产出的工具结果」补一条占位 tool 消息，保住 OpenAI 的配对不变式。
     *
     * <h2>为什么这件事必须做（不变量，不是可选项）</h2>
     * 协议要求：每条带 {@code tool_calls} 的 assistant 消息，后面必须紧跟**每个** {@code tool_call_id}
     * 对应的 tool 消息。如果工具执行段抛异常，历史里就会留下一条「有 tool_calls、没有 tool 回应」的
     * assistant 消息，之后这个 session 的每一次请求都会被 API 以
     * {@code 400 ... must be followed by tool messages responding to each tool_call_id} 拒绝 ——
     * 而且代码里没有任何自愈路径，等于**永久毒化**这个窗口。
     *
     * <p>所以「窗口切片不切断配对」只是第一层保证（{@code ContextManager.verbatimWindowStart}），
     * **写入侧也必须保证**：宁可给模型一条「这条调用没执行」的占位结果，也不能留下孤儿。
     *
     * @param answered 已经成功回灌结果的调用数量；从它开始都算未执行
     */
    static void appendUnansweredToolPlaceholders(Session session, List<ToolCall> calls, int answered,
                                                 RuntimeException cause) {
        for (int i = Math.max(0, answered); i < calls.size(); i++) {
            ToolCall unanswered = calls.get(i);
            session.append(Message.tool(unanswered.id(), unanswered.name(),
                    ToolResult.error("TOOL_DISPATCH_ERROR",
                            "工具调度阶段异常，本条调用未执行（" + cause.getClass().getSimpleName() + "）。").toObservation()));
        }
    }

    /** 执行单个工具调用，带重复调用保护。 */
    private AgentResult.ToolOutcome executeTool(ToolCall call, Session session, String userId,
                                                Tracer tracer, Map<String, Integer> signatureCounts) {
        int count = signatureCounts.merge(call.signature(), 1, Integer::sum);
        long start = System.nanoTime();
        ToolResult result;
        if (count > config.maxRepeatedToolCalls()) {
            result = ToolResult.error("REPEATED_CALL",
                    "重复调用被 Runtime 拦截：`" + call.name() + "` 使用完全相同的参数已调用 "
                            + (count - 1) + " 次。请换参数、换工具，或直接基于已有结果回答用户。");
            tracer.event(TraceTypes.TOOL_BLOCKED, "拦截重复调用 " + call.name(),
                    Tracer.data("tool", call.name(), "attempt", count, "arguments", call.argumentsJson()));
        } else {
            ToolContext toolContext = new ToolContext(userId, session.id(), call.id(), session, tracer);
            result = invoker.invoke(call, toolContext);
        }
        session.append(Message.tool(call.id(), call.name(), result.toObservation()));
        return new AgentResult.ToolOutcome(call.name(), result.ok(), result.errorCode(),
                Texts.oneLine(result.content(), 200), (System.nanoTime() - start) / 1_000_000L);
    }

    /** 达到最大步数后的强制收尾：不再提供工具，要求模型基于已有信息作答。 */
    private AgentResult forceWrapUp(Session session, String userInput, List<AgentResult.StepRecord> steps,
                                    Usage totalUsage, long startedAt, Tracer tracer) {
        tracer.event(TraceTypes.MAX_STEPS_REACHED,
                "达到最大步数 " + config.maxSteps() + "，禁用工具强制收尾",
                Tracer.data("maxSteps", config.maxSteps(), "historySize", session.historySize()));
        try {
            ContextPackage context = contextManager.build(session, config.systemPrompt(), registry.specs());
            List<Message> messages = new ArrayList<>(context.messages());
            messages.add(Message.user("[系统] 已达到工具调用轮次上限。请立刻基于上面已经获得的信息给出最终回答，不要再请求调用工具；"
                    + "若信息不足，请明确说明还缺什么。"));
            LlmResponse response = llm.chat(LlmRequest.builder()
                    .model(config.model())
                    .messages(messages)
                    .tools(List.of())
                    .temperature(config.temperature())
                    .build(), tracer);
            Usage usage = add(totalUsage, response.usage());
            session.lastPromptTokens(response.usage().promptTokens());
            ParsedOutput parsed = LlmOutputParser.parse(response);
            String answer = parsed.hasFinalAnswer() ? parsed.finalAnswer() : parsed.thought();
            if (Texts.isBlank(answer)) {
                answer = deterministicWrapUp(session, userInput);
            }
            session.append(Message.assistant(answer, parsed.thought()));
            steps.add(new AgentResult.StepRecord(config.maxSteps() + 1, java.time.Instant.now(),
                    parsed.thought(), List.of(), List.of(), answer, response.usage()));
            return AgentResult.maxSteps(session, answer, steps, elapsedMs(startedAt), usage);
        } catch (RuntimeException e) {
            tracer.failure(TraceTypes.ERROR, "强制收尾失败，返回确定性兜底文案", e);
            String answer = deterministicWrapUp(session, userInput);
            session.append(Message.assistant(answer, null));
            steps.add(new AgentResult.StepRecord(config.maxSteps() + 1, java.time.Instant.now(),
                    null, List.of(), List.of(), answer, Usage.ZERO));
            return AgentResult.maxSteps(session, answer, steps, elapsedMs(startedAt), totalUsage);
        }
    }

    /** 完全不依赖模型的兜底回答：把本轮拿到的工具结果汇总给用户。 */
    private String deterministicWrapUp(Session session, String userInput) {
        StringBuilder sb = new StringBuilder();
        sb.append("已达到单轮工具调用上限（").append(config.maxSteps()).append(" 次），先把目前拿到的事实汇总给你：\n");
        int index = 1;
        for (Message message : session.history()) {
            if (message.role() == com.littleagent.llm.Role.TOOL) {
                sb.append(index++).append(". [").append(message.toolName()).append("] ")
                        .append(Texts.oneLine(message.content(), 200)).append('\n');
                if (index > 6) {
                    break;
                }
            }
        }
        if (index == 1) {
            sb.append("（本轮没有成功的工具结果）\n");
        }
        sb.append("可以继续追问，或让我换个方式处理「").append(Texts.oneLine(userInput, 40)).append("」。");
        return sb.toString();
    }

    private LlmRequest buildRequest(List<Message> messages, List<com.littleagent.llm.ToolSpec> tools) {
        return LlmRequest.builder()
                .model(config.model())
                .messages(messages)
                .tools(tools)
                .temperature(config.temperature())
                .toolChoice("auto")
                .build();
    }

    private static Usage add(Usage a, Usage b) {
        return new Usage(a.promptTokens() + b.promptTokens(),
                a.completionTokens() + b.completionTokens(),
                a.totalTokens() + b.totalTokens());
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static String deriveTitle(String input) {
        return Texts.oneLine(input, 24);
    }

    /**
     * 一个 session 一个 Tracer：事件既能在内存里回溯（CLI {@code /trace}），也能落 JSONL。
     *
     * <p>注意：即使没有配置任何 sink，也返回「带内存记录」的 Tracer 而不是 noop，
     * 否则关掉日志目录就同时失去了运行时自省能力。
     */
    static Tracer buildTracer(AgentConfig config, String sessionId) {
        List<TraceSink> sinks = new ArrayList<>();
        if (config.traceConsole()) {
            sinks.add(new ConsoleTraceSink(false));
        }
        if (config.logDir() != null) {
            sinks.add(new JsonlTraceSink(config.logDir(), sessionId));
        }
        return Tracer.of(sessionId, sinks.toArray(new TraceSink[0]));
    }

    // ------------------------------------------------------------- 访问器

    public AgentConfig config() {
        return config;
    }

    public SessionManager sessions() {
        return sessions;
    }

    public ToolRegistry tools() {
        return registry;
    }

    public ContextManager contextManager() {
        return contextManager;
    }

    public LlmClient llmClient() {
        return llm;
    }

    @Override
    public void close() {
        invoker.close();
    }

    public static final class Builder {
        private final AgentConfig config;
        private LlmClient llm;
        private ToolRegistry registry;
        private Summarizer summarizer;

        private Builder(AgentConfig config) {
            this.config = config;
        }

        public Builder llm(LlmClient llm) {
            this.llm = llm;
            return this;
        }

        public Builder toolRegistry(ToolRegistry registry) {
            this.registry = registry;
            return this;
        }

        public Builder summarizer(Summarizer summarizer) {
            this.summarizer = summarizer;
            return this;
        }

        public AgentRuntime build() {
            if (llm == null) {
                llm = config.createLlmClient();
            }
            if (registry == null) {
                registry = DefaultTools.registry(config.knowledgeDir());
            }
            if (summarizer == null) {
                summarizer = config.mockMode()
                        ? new com.littleagent.context.DeterministicSummarizer()
                        : new LlmSummarizer(llm, config.model());
            }
            return new AgentRuntime(this);
        }
    }
}
